package vn.edu.finalism.logistics.modules.identityaccess.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.PageCursor;

public interface AdminUserRepository {

	List<UserView> list(String status, String role, String query, PageCursor cursor, int limit);

	Optional<UserView> findView(UUID id);

	Optional<UserView> findForUpdate(UUID id);

	boolean emailExists(String email);

	void insert(UUID id, String email, String username, String displayName, String phone, String passwordHash, Instant now);

	boolean usernameTaken(String username, UUID exceptUserId);

	boolean updateProfile(UUID id, String displayName, String username, String phone, long expectedVersion, Instant now);

	boolean setStatus(UUID id, String status, long expectedVersion, Instant now);

	boolean touchVersion(UUID id, long expectedVersion, Instant now);

	Map<String, UUID> roleIdsByCode(Collection<String> codes);

	Set<UUID> existingZoneIds(Collection<UUID> zoneIds);

	void replaceRoles(UUID userId, Collection<UUID> roleIds, UUID grantedBy, Instant now);

	void replaceScopes(UUID userId, Collection<UUID> zoneIds, UUID grantedBy, Instant now);

	int countOtherActiveAdmins(UUID excludedUserId);

	List<RoleView> roles();

	List<String> allPermissionCodes();

	boolean replaceRolePermissions(String roleCode, java.util.Collection<String> permissionCodes);

	record UserView(UUID id, String email, String username, String displayName, String phone, String status, long version, Instant createdAt,
			List<String> roles, List<UUID> scopeZoneIds) {
	}

	record RoleView(String code, List<String> permissions) {
	}
}
