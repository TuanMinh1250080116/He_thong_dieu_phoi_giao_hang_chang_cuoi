package vn.edu.finalism.logistics.modules.proof.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.proof.application.port.ObjectStoragePort;

@Component
class LocalObjectStorage implements ObjectStoragePort {

	private final Path root;

	LocalObjectStorage(@Value("${app.storage.local-dir:}") String configuredDir) {
		try {
			Path dir = configuredDir == null || configuredDir.isBlank()
					? Path.of(System.getProperty("java.io.tmpdir"), "logistics-evidence")
					: Path.of(configuredDir);
			this.root = Files.createDirectories(dir).toAbsolutePath().normalize();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public String keyFor(UUID evidenceId) {
		return "evidence/" + evidenceId + ".bin";
	}

	@Override
	public void put(String storageKey, byte[] content) {
		try {
			Path target = resolve(storageKey);
			Files.createDirectories(target.getParent());
			Files.write(target, content);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public Optional<ObjectInfo> inspect(String storageKey) {
		Path target = resolve(storageKey);
		if (!Files.isRegularFile(target)) {
			return Optional.empty();
		}
		try {
			byte[] content = Files.readAllBytes(target);
			return Optional.of(new ObjectInfo(content.length, detectMime(content),
					MessageDigest.getInstance("SHA-256").digest(content)));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	@Override
	public Optional<byte[]> read(String storageKey) {
		Path target = resolve(storageKey);
		if (!Files.isRegularFile(target)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(target));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Path resolve(String storageKey) {
		Path target = root.resolve(storageKey).normalize();
		if (!target.startsWith(root)) {
			throw new IllegalArgumentException("Storage key escapes the storage root.");
		}
		return target;
	}

	private static String detectMime(byte[] content) {
		if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
				&& (content[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 0x50 && content[2] == 0x4E
				&& content[3] == 0x47) {
			return "image/png";
		}
		return "application/octet-stream";
	}
}
