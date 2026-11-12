package vn.edu.finalism.logistics.modules.proof.application.port;

import java.util.Optional;

public interface ObjectStoragePort {

	String keyFor(java.util.UUID evidenceId);

	void put(String storageKey, byte[] content);

	Optional<ObjectInfo> inspect(String storageKey);

	Optional<byte[]> read(String storageKey);

	record ObjectInfo(long sizeBytes, String detectedMime, byte[] sha256) {
	}
}
