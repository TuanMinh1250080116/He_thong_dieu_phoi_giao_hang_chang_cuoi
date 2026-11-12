package vn.edu.finalism.logistics.modules.administration.application;

import java.util.UUID;

public interface EffectiveRulePort {

	DispatchRule effectiveRule(UUID zoneId);
}
