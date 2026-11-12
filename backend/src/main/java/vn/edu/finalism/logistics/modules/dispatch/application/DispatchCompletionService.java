package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;

@Service
public class DispatchCompletionService {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;

	DispatchCompletionService(BatchRepository batches, DispatchRepository dispatch) {
		this.batches = batches;
		this.dispatch = dispatch;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void onDeliveryCompleted(UUID batchId, UUID decisionId, Instant now) {
		batches.markCompleted(batchId, now);
		dispatch.releaseCommitments(decisionId, now);
	}
}
