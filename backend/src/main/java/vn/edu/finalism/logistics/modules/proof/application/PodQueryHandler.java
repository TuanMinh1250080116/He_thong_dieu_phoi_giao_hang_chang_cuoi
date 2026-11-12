package vn.edu.finalism.logistics.modules.proof.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository.PodEvidenceRef;
import vn.edu.finalism.logistics.modules.proof.domain.ProofOfDelivery;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;

@Service
public class PodQueryHandler {

	private final ProofRepository proofs;
	private final OrderLifecycleService orders;

	PodQueryHandler(ProofRepository proofs, OrderLifecycleService orders) {
		this.proofs = proofs;
		this.orders = orders;
	}

	@Transactional(readOnly = true)
	public ProofOfDelivery get(ActorContext actor, UUID podId) {
		ProofOfDelivery pod = proofs.findPod(podId).orElseThrow(() -> BusinessException.notFound("Proof of delivery"));
		return authorize(actor, pod);
	}

	@Transactional(readOnly = true)
	public ProofOfDelivery getByOrder(ActorContext actor, UUID orderId) {
		ProofOfDelivery pod = proofs.findPodByOrder(orderId)
				.orElseThrow(() -> BusinessException.notFound("Proof of delivery"));
		return authorize(actor, pod);
	}

	@Transactional(readOnly = true)
	public List<PodEvidenceRef> evidenceOf(UUID podId) {
		return proofs.listPodEvidence(podId);
	}

	private ProofOfDelivery authorize(ActorContext actor, ProofOfDelivery pod) {
		actor.requirePermission("proof.view");
		OrderView order = orders.get(List.of(pod.orderId())).get(0);
		boolean owner = order.customerId().equals(actor.userId());
		boolean submitter = pod.submittedBy().equals(actor.userId());
		boolean inScope = order.zoneId() != null && actor.hasScope(order.zoneId());
		if (!owner && !submitter && !inScope) {
			throw BusinessException.notFound("Proof of delivery");
		}
		return pod;
	}
}
