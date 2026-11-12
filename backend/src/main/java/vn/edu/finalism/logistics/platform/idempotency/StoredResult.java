package vn.edu.finalism.logistics.platform.idempotency;

import java.util.UUID;

public record StoredResult(String resultType, UUID resultRef, Long resultVersion) {
}
