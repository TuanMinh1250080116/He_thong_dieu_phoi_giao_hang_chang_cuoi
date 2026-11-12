package vn.edu.finalism.logistics.modules.proof.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.domain.EvidenceObject;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
class ExceptionEvidenceService implements ExceptionEvidencePort {

	private final ProofRepository proofs;
	private final ClockPort clock;

	ExceptionEvidenceService(ProofRepository proofs, ClockPort clock) {
		this.proofs = proofs;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public List<UUID> attachForException(List<UUID> evidenceIds, UUID ownerUserId, UUID deliveryId) {
		List<UUID> attached = new ArrayList<>();
		for (UUID evidenceId : evidenceIds) {
			EvidenceObject item = proofs.findEvidenceForUpdate(evidenceId)
					.filter(e -> e.ownerUserId().equals(ownerUserId) && deliveryId.equals(e.deliveryId())
							&& "EXCEPTION".equals(e.purpose()) && "VERIFIED".equals(e.status()))
					.orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_INVALID,
							"An evidence file is missing, not verified, or belongs to someone else."));
			proofs.markAttached(item.id(), clock.now());
			attached.add(item.id());
		}
		return attached;
	}
}
