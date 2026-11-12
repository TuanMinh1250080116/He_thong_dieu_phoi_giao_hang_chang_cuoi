package vn.edu.finalism.logistics.modules.delivery.domain;

import java.util.List;
import java.util.UUID;

public record CompletionCheck(List<UUID> incompleteStopIds, List<UUID> missingPodStopIds,
		List<UUID> blockingExceptionIds) {

	public boolean allowed() {
		return incompleteStopIds.isEmpty() && missingPodStopIds.isEmpty() && blockingExceptionIds.isEmpty();
	}
}
