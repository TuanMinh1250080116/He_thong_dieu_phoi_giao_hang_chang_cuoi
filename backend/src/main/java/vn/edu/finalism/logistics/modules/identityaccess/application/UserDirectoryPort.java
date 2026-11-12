package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDirectoryPort {

	List<UUID> userIdsWithPermissionInZone(String permission, UUID zoneId);

	boolean userHasPermission(UUID userId, String permission);

	Optional<String> displayName(UUID userId);

	void grantScope(UUID userId, UUID zoneId, UUID grantedBy);
}
