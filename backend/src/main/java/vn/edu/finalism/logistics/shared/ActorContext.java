package vn.edu.finalism.logistics.shared;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ActorContext(
		UUID userId,
		Set<String> roles,
		Set<String> permissions,
		Set<UUID> operationScopeIds,
		String correlationId) {

	public ActorContext {
		Objects.requireNonNull(userId, "userId");
		roles = Set.copyOf(roles);
		permissions = Set.copyOf(permissions);
		operationScopeIds = Set.copyOf(operationScopeIds);
	}

	public boolean hasPermission(String permission) {
		return permissions.contains(permission);
	}

	public boolean hasScope(UUID zoneId) {
		return operationScopeIds.contains(zoneId);
	}

	public void requirePermission(String permission) {
		if (!hasPermission(permission)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "You do not have permission for this action.");
		}
	}

	public void requireScope(UUID zoneId) {
		if (!hasScope(zoneId)) {
			throw BusinessException.notFound("Resource");
		}
	}
}
