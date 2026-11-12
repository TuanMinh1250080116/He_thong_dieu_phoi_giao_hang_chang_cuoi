package vn.edu.finalism.logistics.modules.proof.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface PodQueryPort {

	Set<UUID> stopIdsWithPod(Collection<UUID> stopIds);
}
