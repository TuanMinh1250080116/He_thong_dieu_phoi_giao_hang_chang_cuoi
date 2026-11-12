package vn.edu.finalism.logistics.modules.dispatch.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.BatchStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;

@Repository
class JdbcBatchRepository implements BatchRepository {

	private static final String SELECT = """
			SELECT id, code, zone_id, status, lower(planned_window) AS planned_start, upper(planned_window) AS planned_end,
			       total_weight_g, total_volume_cm3, created_by, version
			FROM dispatch.delivery_batches
			""";

	private final JdbcClient jdbc;

	JdbcBatchRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public String nextCode() {
		Long next = jdbc.sql("SELECT nextval('dispatch.batch_code_seq')").query(Long.class).single();
		return String.format("BAT-%06d", next);
	}

	@Override
	public void insert(DeliveryBatch batch, Instant now) {
		jdbc.sql("""
				INSERT INTO dispatch.delivery_batches
				  (id, code, zone_id, status, order_count, total_weight_g, total_volume_cm3, created_by,
				   created_at, updated_at, version)
				VALUES (:id, :code, :zone, :status, :count, :weight, :volume, :by, :now, :now, 0)
				""")
				.param("id", batch.id()).param("code", batch.code()).param("zone", batch.zoneId())
				.param("status", batch.status().name()).param("count", batch.orderIds().size())
				.param("weight", batch.totalWeightG()).param("volume", batch.totalVolumeCm3())
				.param("by", batch.createdBy()).param("now", utc(now))
				.update();
	}

	@Override
	public void addMembers(UUID batchId, Collection<UUID> orderIds, UUID addedBy, Instant now) {
		for (UUID orderId : orderIds) {
			jdbc.sql("""
					INSERT INTO dispatch.batch_orders (id, batch_id, order_id, added_at, added_by)
					VALUES (:id, :batch, :order, :now, :by)
					""")
					.param("id", UUID.randomUUID()).param("batch", batchId).param("order", orderId)
					.param("now", utc(now)).param("by", addedBy)
					.update();
		}
	}

	@Override
	public Optional<DeliveryBatch> findById(UUID id) {
		return find(SELECT + " WHERE id = :id", id);
	}

	@Override
	public Optional<DeliveryBatch> findByIdForUpdate(UUID id) {
		return find(SELECT + " WHERE id = :id FOR UPDATE", id);
	}

	@Override
	public void markLocked(DeliveryBatch locked, UUID lockedBy, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.delivery_batches
				SET status = 'LOCKED', planned_window = tstzrange(:start, :end, '[)'),
				    locked_at = :now, locked_by = :by, updated_at = :now, version = version + 1
				WHERE id = :id
				""")
				.param("start", utc(locked.plannedStart())).param("end", utc(locked.plannedEnd()))
				.param("now", utc(now)).param("by", lockedBy).param("id", locked.id())
				.update();
	}

	@Override
	public void markCompleted(UUID batchId, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.delivery_batches SET status = 'COMPLETED', updated_at = :now, version = version + 1
				WHERE id = :id AND status = 'LOCKED'
				""")
				.param("now", utc(now)).param("id", batchId).update();
		jdbc.sql("""
				UPDATE dispatch.batch_orders SET released_at = :now, release_reason = 'BATCH_COMPLETED'
				WHERE batch_id = :id AND released_at IS NULL
				""")
				.param("now", utc(now)).param("id", batchId).update();
	}

	@Override
	public void applyMembership(DeliveryBatch updated, Collection<UUID> added, Collection<UUID> removed, UUID by,
			Instant now) {
		if (!removed.isEmpty()) {
			jdbc.sql("""
					UPDATE dispatch.batch_orders SET released_at = :now, release_reason = 'REMOVED'
					WHERE batch_id = :id AND released_at IS NULL AND order_id IN (:orders)
					""")
					.param("now", utc(now)).param("id", updated.id()).param("orders", removed).update();
		}
		addMembers(updated.id(), added, by, now);
		jdbc.sql("""
				UPDATE dispatch.delivery_batches
				SET order_count = :count, total_weight_g = :weight, total_volume_cm3 = :volume,
				    updated_at = :now, version = version + 1
				WHERE id = :id
				""")
				.param("count", updated.orderIds().size()).param("weight", updated.totalWeightG())
				.param("volume", updated.totalVolumeCm3()).param("now", utc(now)).param("id", updated.id())
				.update();
	}

	@Override
	public void markReopened(UUID batchId, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.delivery_batches
				SET status = 'DRAFT', planned_window = NULL, locked_at = NULL, locked_by = NULL,
				    reopen_count = reopen_count + 1, updated_at = :now, version = version + 1
				WHERE id = :id AND status = 'LOCKED'
				""")
				.param("now", utc(now)).param("id", batchId).update();
	}

	@Override
	public void markCancelled(UUID batchId, String reason, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.batch_orders SET released_at = :now, release_reason = 'BATCH_CANCELLED'
				WHERE batch_id = :id AND released_at IS NULL
				""")
				.param("now", utc(now)).param("id", batchId).update();
		jdbc.sql("""
				UPDATE dispatch.delivery_batches
				SET status = 'CANCELLED', cancel_reason = :reason, updated_at = :now, version = version + 1
				WHERE id = :id AND status IN ('DRAFT','LOCKED')
				""")
				.param("reason", reason).param("now", utc(now)).param("id", batchId).update();
	}

	@Override
	public Optional<UUID> findActiveBatchIdOfOrder(UUID orderId) {
		return jdbc.sql("SELECT batch_id FROM dispatch.batch_orders WHERE order_id = :id AND released_at IS NULL")
				.param("id", orderId).query((rs, i) -> rs.getObject(1, UUID.class)).optional();
	}

	private Optional<DeliveryBatch> find(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query((rs, i) -> map(rs)).optional()
				.map(batch -> new DeliveryBatch(batch.id(), batch.code(), batch.zoneId(), batch.status(),
						batch.plannedStart(), batch.plannedEnd(), members(batch.id()), batch.totalWeightG(),
						batch.totalVolumeCm3(), batch.createdBy(), batch.version()));
	}

	private List<UUID> members(UUID batchId) {
		return jdbc.sql("""
				SELECT order_id FROM dispatch.batch_orders
				WHERE batch_id = :id AND released_at IS NULL ORDER BY added_at, order_id
				""")
				.param("id", batchId)
				.query((rs, i) -> rs.getObject(1, UUID.class)).list();
	}

	private static DeliveryBatch map(ResultSet rs) throws SQLException {
		OffsetDateTime start = rs.getObject("planned_start", OffsetDateTime.class);
		OffsetDateTime end = rs.getObject("planned_end", OffsetDateTime.class);
		return new DeliveryBatch(
				rs.getObject("id", UUID.class),
				rs.getString("code"),
				rs.getObject("zone_id", UUID.class),
				BatchStatus.valueOf(rs.getString("status")),
				start == null ? null : start.toInstant(),
				end == null ? null : end.toInstant(),
				List.of(),
				rs.getLong("total_weight_g"),
				rs.getLong("total_volume_cm3"),
				rs.getObject("created_by", UUID.class),
				rs.getLong("version"));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
