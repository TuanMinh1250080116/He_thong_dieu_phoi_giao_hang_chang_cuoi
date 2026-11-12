package vn.edu.finalism.logistics.modules.administration.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ConfigAdminRepository {

	enum Kind {
		RULE("dispatch_rule_versions"), POD_POLICY("pod_policy_versions");

		private final String table;

		Kind(String table) {
			this.table = table;
		}

		public String table() {
			return table;
		}
	}

	void lockZoneActivation();

	List<ZoneOption> zoneOptions();

	List<ZoneRow> listZones(Set<UUID> visibleZoneIds);

	Optional<ZoneRow> findZone(UUID id);

	boolean zoneCodeExists(String code);

	void insertZone(UUID id, String code, String name, Instant now);

	GeometryCheck checkGeometry(String geoJson);

	int nextZoneVersionNo(UUID zoneId);

	void insertZoneVersion(UUID id, UUID zoneId, int versionNo, String geoJson, UUID createdBy, Instant now);

	List<ZoneVersionRow> listZoneVersions(UUID zoneId);

	Optional<ZoneVersionRow> findZoneVersionForUpdate(UUID id);

	List<UUID> overlappingActiveZones(UUID versionId);

	Optional<Integer> retireActiveZoneVersion(UUID zoneId, Instant now);

	void activateZoneVersion(UUID id, Instant now);

	void touchZone(UUID zoneId, Instant now);

	boolean setZoneActive(UUID zoneId, boolean active, Instant now);

	ZoneUsage zoneUsage(UUID zoneId);

	record ZoneUsage(int openOrders, int openBatches) {
	}

	boolean zoneExists(UUID zoneId);

	List<RuleRow> listRules(Set<UUID> visibleZoneIds, String status);

	void insertRule(RuleRow rule, UUID createdBy, Instant now);

	List<PolicyRow> listPolicies(Set<UUID> visibleZoneIds, String status);

	void insertPolicy(PolicyRow policy, UUID createdBy, Instant now);

	int nextVersionNo(Kind kind, UUID zoneId);

	Optional<VersionHeader> findHeaderForUpdate(Kind kind, UUID id);

	Optional<Integer> retireActive(Kind kind, UUID zoneId, Instant now);

	void activate(Kind kind, UUID id, Instant now);

	boolean schedule(Kind kind, UUID id, Instant effectiveFrom);

	boolean unschedule(Kind kind, UUID id, Instant now);

	boolean scheduledExists(Kind kind, UUID zoneId);

	void lockVersionActivation();

	List<DuePromotion> promoteDue(Kind kind, Instant now);

	record DuePromotion(UUID id, UUID zoneId, int versionNo, Integer previousVersionNo) {
	}

	Optional<RuleRow> findRule(UUID id);

	Optional<PolicyRow> findPolicy(UUID id);

	record ZoneOption(UUID id, String code, String name) {
	}

	record GeometryCheck(boolean valid, String reason, String type, double minLng, double maxLng, double minLat,
			double maxLat, double areaSqM) {
	}

	record ZoneRow(UUID id, String code, String name, boolean active, Integer activeVersionNo, UUID activeVersionId,
			int versionCount, long version) {
	}

	record ZoneVersionRow(UUID id, UUID zoneId, int versionNo, String status, Instant effectiveFrom,
			Instant activatedAt, Instant retiredAt, Instant createdAt, String boundaryGeoJson) {
	}

	record VersionHeader(UUID id, UUID zoneId, int versionNo, String status, String name) {
	}

	record RuleRow(UUID id, UUID zoneId, int versionNo, String name, String status, BigDecimal wDistance,
			BigDecimal wCapacityFit, BigDecimal wAvailability, BigDecimal wZoneCompat, int ttlSeconds,
			int searchRadiusM, int maxLocationAgeSeconds, int persistTopN, Instant effectiveFrom, Instant activatedAt,
			Instant retiredAt, Instant createdAt) {
	}

	record PolicyRow(UUID id, UUID zoneId, int versionNo, String name, String status, String evidenceOp,
			List<String> evidenceItems, Integer maxGpsAccuracyM, String recipientNameRule, Instant effectiveFrom,
			Instant activatedAt, Instant retiredAt, Instant createdAt) {
	}
}
