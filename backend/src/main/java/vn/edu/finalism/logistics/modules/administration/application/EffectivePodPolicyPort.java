package vn.edu.finalism.logistics.modules.administration.application;

import java.util.UUID;

public interface EffectivePodPolicyPort {

	PodPolicy effectivePolicy(UUID zoneId);
}
