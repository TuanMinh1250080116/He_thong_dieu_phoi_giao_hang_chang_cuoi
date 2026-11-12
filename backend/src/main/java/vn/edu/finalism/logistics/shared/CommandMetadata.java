package vn.edu.finalism.logistics.shared;

import java.time.Instant;
import java.util.UUID;

public record CommandMetadata(
		UUID commandId,
		String idempotencyKey,
		Long expectedVersion,
		Instant clientObservedAt,
		ActorContext actor) {
}
