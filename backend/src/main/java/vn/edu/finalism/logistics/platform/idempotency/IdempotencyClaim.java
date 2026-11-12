package vn.edu.finalism.logistics.platform.idempotency;

public sealed interface IdempotencyClaim {

	record Fresh() implements IdempotencyClaim {
	}

	record Replay(StoredResult result) implements IdempotencyClaim {
	}
}
