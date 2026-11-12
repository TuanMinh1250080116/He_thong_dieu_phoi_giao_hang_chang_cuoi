package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;

@Service
public class BatchLifecycleHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final OrderLifecycleService orderLifecycle;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ClockPort clock;

	BatchLifecycleHandler(BatchRepository batches, DispatchRepository dispatch, OrderLifecycleService orderLifecycle,
			IdempotencyService idempotency, RequestFingerprinter fingerprinter, AuditWriter audit, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.orderLifecycle = orderLifecycle;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public Outcome<DeliveryBatch> reopen(ActorContext actor, UUID batchId, IdempotencyKey key, long expectedVersion,
			String reason) {
		actor.requirePermission("dispatch.batch.manage");
		String text = requireReason(reason, "reopen");
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "REOPEN_BATCH",
				fingerprinter.fingerprint("POST /dispatch/batches/" + batchId + "/reopen",
						String.valueOf(expectedVersion), Map.of("reason", text)));
		if (claim instanceof IdempotencyClaim.Replay) {
			return Outcome.replay(batches.findById(batchId).orElseThrow(() -> BusinessException.notFound("Batch")));
		}

		DeliveryBatch batch = load(actor, batchId, expectedVersion);
		batch.requireReopenable();
		Instant now = clock.now();
		Optional<DispatchDecision> decision = dispatch.findOpenDecisionByBatchForUpdate(batchId);
		if (decision.isPresent()) {
			if (decision.get().status() != DecisionStatus.PENDING_DECISION) {
				throw BusinessException.stateNotAllowed(
						"A driver is already assigned to this batch. Cancel the dispatch before reopening it.");
			}
			dispatch.invalidateActiveSnapshots(decision.get().id(), "BATCH_REOPENED", now);
		}
		batches.markReopened(batchId, now);
		audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_REOPENED, "BATCH", batchId, batch.zoneId(),
				Map.of("status", "LOCKED"), Map.of("status", "DRAFT"), text, null));
		idempotency.complete(actor.userId(), key, "BATCH", batchId, expectedVersion + 1);
		return Outcome.fresh(batches.findById(batchId).orElseThrow());
	}

	@Transactional
	public Outcome<DeliveryBatch> cancel(ActorContext actor, UUID batchId, IdempotencyKey key, long expectedVersion,
			String reason) {
		actor.requirePermission("dispatch.cancel");
		String text = requireReason(reason, "cancel");
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CANCEL_BATCH",
				fingerprinter.fingerprint("POST /dispatch/batches/" + batchId + "/cancel",
						String.valueOf(expectedVersion), Map.of("reason", text)));
		if (claim instanceof IdempotencyClaim.Replay) {
			return Outcome.replay(batches.findById(batchId).orElseThrow(() -> BusinessException.notFound("Batch")));
		}

		DeliveryBatch batch = load(actor, batchId, expectedVersion);
		batch.requireCancellable();
		Instant now = clock.now();
		Optional<DispatchDecision> decision = dispatch.findOpenDecisionByBatchForUpdate(batchId);
		if (decision.isPresent()) {
			DispatchDecision open = decision.get();
			if (open.status() != DecisionStatus.PENDING_DECISION) {
				throw BusinessException.stateNotAllowed(
						"A driver is already assigned to this batch. Cancel the dispatch before cancelling the batch.");
			}
			dispatch.invalidateActiveSnapshots(open.id(), "BATCH_CANCELLED", now);
			dispatch.cancelDecision(open.id(), text, open.version(), now);
		}
		orderLifecycle.apply(batch.orderIds(), Transition.RELEASE_FROM_BATCH);
		batches.markCancelled(batchId, text, now);
		audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_CANCELLED, "BATCH", batchId, batch.zoneId(),
				Map.of("status", batch.status().name()), Map.of("status", "CANCELLED"), text, null));
		idempotency.complete(actor.userId(), key, "BATCH", batchId, expectedVersion + 1);
		return Outcome.fresh(batches.findById(batchId).orElseThrow());
	}

	private DeliveryBatch load(ActorContext actor, UUID batchId, long expectedVersion) {
		DeliveryBatch batch = batches.findByIdForUpdate(batchId)
				.orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (batch.version() != expectedVersion) {
			throw BusinessException.versionConflict(batch.version());
		}
		return batch;
	}

	private static String requireReason(String reason, String verb) {
		String text = reason == null ? "" : reason.trim();
		if (text.isEmpty() || text.length() > 500) {
			throw BusinessException.validation("reason", "REQUIRED", "A reason of up to 500 characters is required to "
					+ verb + " the batch.");
		}
		return text;
	}
}
