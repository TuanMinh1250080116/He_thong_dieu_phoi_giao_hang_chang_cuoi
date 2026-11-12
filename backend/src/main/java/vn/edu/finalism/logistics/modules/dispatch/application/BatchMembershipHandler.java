package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
public class BatchMembershipHandler {

	private final BatchRepository batches;
	private final OrderLifecycleService orderLifecycle;
	private final AuditWriter audit;
	private final ClockPort clock;

	BatchMembershipHandler(BatchRepository batches, OrderLifecycleService orderLifecycle, AuditWriter audit,
			ClockPort clock) {
		this.batches = batches;
		this.orderLifecycle = orderLifecycle;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public DeliveryBatch handle(ActorContext actor, UUID batchId, long expectedVersion, List<UUID> addIds,
			List<UUID> removeIds) {
		actor.requirePermission("dispatch.batch.manage");
		List<UUID> add = addIds == null ? List.of() : addIds;
		List<UUID> remove = removeIds == null ? List.of() : removeIds;
		if (add.isEmpty() && remove.isEmpty()) {
			throw BusinessException.validation("addOrderIds", "REQUIRED", "Nothing to add or remove.");
		}
		if (new HashSet<>(add).size() != add.size() || new HashSet<>(remove).size() != remove.size()
				|| add.stream().anyMatch(remove::contains)) {
			throw BusinessException.validation("addOrderIds", "DUPLICATE",
					"An order can appear only once, either in the add list or in the remove list.");
		}

		DeliveryBatch batch = batches.findByIdForUpdate(batchId)
				.orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (batch.version() != expectedVersion) {
			throw BusinessException.versionConflict(batch.version());
		}

		Set<UUID> current = new LinkedHashSet<>(batch.orderIds());
		List<UUID> notMembers = remove.stream().filter(id -> !current.contains(id)).toList();
		if (!notMembers.isEmpty()) {
			throw invalid("Some orders are not in this batch.", notMembers);
		}
		List<UUID> alreadyIn = add.stream().filter(current::contains).toList();
		if (!alreadyIn.isEmpty()) {
			throw invalid("Some orders are already in this batch.", alreadyIn);
		}

		Set<UUID> next = new LinkedHashSet<>(current);
		next.removeAll(remove);
		next.addAll(add);
		if (next.isEmpty()) {
			throw BusinessException.validation("removeOrderIds", "LAST_ORDER",
					"A batch must keep at least one order. Cancel the batch instead.");
		}

		List<OrderView> views = orderLifecycle.lockAndGet(next);
		List<UUID> unusable = views.stream()
				.filter(o -> add.contains(o.id())
						? o.status() != OrderStatus.READY_FOR_DISPATCH || !batch.zoneId().equals(o.zoneId())
						: o.status() != OrderStatus.BATCHED)
				.map(OrderView::id).toList();
		if (!unusable.isEmpty()) {
			throw invalid("Some orders are not ready for dispatch in this zone.", unusable);
		}

		long weight = views.stream().mapToLong(OrderView::weightG).sum();
		long volume = views.stream().mapToLong(OrderView::volumeCm3).sum();
		DeliveryBatch updated = batch.withMembers(new ArrayList<>(next), weight, volume);

		Instant now = clock.now();
		batches.applyMembership(updated, add, remove, actor.userId(), now);
		if (!remove.isEmpty()) {
			orderLifecycle.apply(remove, Transition.RELEASE_FROM_BATCH);
		}
		if (!add.isEmpty()) {
			orderLifecycle.apply(add, Transition.ATTACH_TO_BATCH);
		}

		audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_MEMBERSHIP_CHANGED, "BATCH", batchId,
				batch.zoneId(), Map.of("orderCount", current.size()),
				Map.of("orderCount", next.size(), "added", add.stream().map(UUID::toString).toList(),
						"removed", remove.stream().map(UUID::toString).toList()),
				null, null));
		return batches.findById(batchId).orElseThrow();
	}

	private static BusinessException invalid(String message, List<UUID> ids) {
		return new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED, message,
				Map.of("invalidOrderIds", ids.stream().map(UUID::toString).toList()));
	}
}
