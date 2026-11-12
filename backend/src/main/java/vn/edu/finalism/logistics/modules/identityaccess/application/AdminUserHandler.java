package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AdminUserRepository;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AdminUserRepository.RoleView;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AdminUserRepository.UserView;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshSessionRepository;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class AdminUserHandler {

	private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED", "LOCKED");
	private static final String ADMIN_ROLE = "SYSTEM_ADMIN";

	private final AdminUserRepository users;
	private final RefreshSessionRepository sessions;
	private final PasswordEncoder encoder;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ClockPort clock;

	AdminUserHandler(AdminUserRepository users, RefreshSessionRepository sessions, PasswordEncoder encoder,
			IdempotencyService idempotency, RequestFingerprinter fingerprinter, AuditWriter audit, ClockPort clock) {
		this.users = users;
		this.sessions = sessions;
		this.encoder = encoder;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.clock = clock;
	}

	public record CreateUser(String email, String username, String displayName, String phone, String password) {
	}

	@Transactional(readOnly = true)
	public PageResult<UserView> list(ActorContext actor, String status, String role, String query, String cursor,
			Integer limit) {
		actor.requirePermission("iam.user.manage");
		if (status != null && !STATUSES.contains(status)) {
			throw BusinessException.validation("status", "INVALID", "status must be ACTIVE, DISABLED or LOCKED.");
		}
		int size = PageCursor.normalizeLimit(limit);
		List<UserView> rows = users.list(status, blankToNull(role), blankToNull(query),
				cursor == null || cursor.isBlank() ? null : PageCursor.decode(cursor), size + 1);
		boolean more = rows.size() > size;
		List<UserView> page = more ? rows.subList(0, size) : rows;
		String next = more ? new PageCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id())
				.encode() : null;
		return new PageResult<>(List.copyOf(page), next, clock.now());
	}

	@Transactional(readOnly = true)
	public UserView get(ActorContext actor, UUID id) {
		actor.requirePermission("iam.user.manage");
		return users.findView(id).orElseThrow(() -> BusinessException.notFound("User"));
	}

	@Transactional(readOnly = true)
	public List<RoleView> roles(ActorContext actor) {
		actor.requirePermission("iam.role.manage");
		return users.roles();
	}

	@Transactional(readOnly = true)
	public List<String> allPermissions(ActorContext actor) {
		actor.requirePermission("iam.role.manage");
		return users.allPermissionCodes();
	}

	@Transactional
	public RoleView changeRolePermissions(ActorContext actor, String roleCode, List<String> permissionCodes,
			String reason) {
		actor.requirePermission("iam.role.manage");
		String text = requireReason(reason);
		RoleView current = users.roles().stream().filter(r -> r.code().equals(roleCode)).findFirst()
				.orElseThrow(() -> BusinessException.notFound("Role"));
		Set<String> wanted = new java.util.TreeSet<>(permissionCodes == null ? List.of() : permissionCodes);
		if (!users.allPermissionCodes().containsAll(wanted)) {
			throw BusinessException.validation("permissionCodes", "UNKNOWN", "A permission does not exist.");
		}
		if (ADMIN_ROLE.equals(roleCode) && !wanted.containsAll(Set.of("iam.user.manage", "iam.role.manage"))) {
			throw BusinessException.stateNotAllowed(
					"The system admin role must keep the permissions to manage users and roles.");
		}
		users.replaceRolePermissions(roleCode, wanted);
		audit.append(AuditEntry.forUser(actor, AuditAction.ROLE_PERMISSIONS_CHANGED, "ROLE",
				UUID.nameUUIDFromBytes(("role:" + roleCode).getBytes(java.nio.charset.StandardCharsets.UTF_8)), null,
				Map.of("role", roleCode, "permissions", current.permissions()),
				Map.of("role", roleCode, "permissions", List.copyOf(wanted)), text, null));
		return users.roles().stream().filter(r -> r.code().equals(roleCode)).findFirst().orElseThrow();
	}

	@Transactional
	public Outcome<UserView> create(ActorContext actor, IdempotencyKey key, CreateUser command) {
		actor.requirePermission("iam.user.manage");
		String email = command.email() == null ? "" : command.email().trim().toLowerCase();
		String name = command.displayName() == null ? "" : command.displayName().trim();
		if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
			throw BusinessException.validation("email", "INVALID", "A valid email address is required.");
		}
		if (name.isEmpty()) {
			throw BusinessException.validation("displayName", "REQUIRED", "A display name is required.");
		}
		String phone = normalizePhone(command.phone());
		String username = normalizeUsername(command.username());
		String password = command.password() == null ? "" : command.password();
		if (password.length() < 10 || password.chars().noneMatch(Character::isLetter)
				|| password.chars().noneMatch(Character::isDigit)) {
			throw BusinessException.validation("password", "WEAK",
					"The password needs at least 10 characters with letters and digits.");
		}

		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/users", null,
				Map.of("email", email, "username", username == null ? "" : username, "displayName", name,
						"phone", phone == null ? "" : phone));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_USER", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(users.findView(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("User")));
		}
		if (users.emailExists(email)) {
			throw BusinessException.validation("email", "DUPLICATE", "This email address is already in use.");
		}

		if (username != null && users.usernameTaken(username, null)) {
			throw BusinessException.validation("username", "DUPLICATE", "This username is already in use.");
		}

		Instant now = clock.now().truncatedTo(ChronoUnit.MILLIS);
		UUID id = UUID.randomUUID();
		users.insert(id, email, username, name, phone, encoder.encode(password), now);
		audit.append(AuditEntry.forUser(actor, AuditAction.USER_CREATED, "USER", id, null, null,
				Map.of("email", email, "username", username == null ? "" : username, "status", "ACTIVE"), null, null));
		idempotency.complete(actor.userId(), key, "USER", id, 0L);
		return Outcome.fresh(users.findView(id).orElseThrow());
	}

	@Transactional
	public UserView updateProfile(ActorContext actor, UUID id, long expectedVersion, String displayName, String usernameInput,
			String phoneInput) {
		actor.requirePermission("iam.user.manage");
		String name = displayName == null ? "" : displayName.trim();
		if (name.isEmpty()) {
			throw BusinessException.validation("displayName", "REQUIRED", "A display name is required.");
		}
		String phone = normalizePhone(phoneInput);
		String username = normalizeUsername(usernameInput);
		UserView current = lock(id, expectedVersion);
		if (username != null && users.usernameTaken(username, id)) {
			throw BusinessException.validation("username", "DUPLICATE", "This username is already in use.");
		}
		Instant now = clock.now();
		if (!users.updateProfile(id, name, username, phone, expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.USER_UPDATED, "USER", id, null,
				Map.of("displayName", current.displayName(), "username", current.username() == null ? "" : current.username(),
						"phone", current.phone() == null ? "" : current.phone()),
				Map.of("displayName", name, "username", username == null ? (current.username() == null ? "" : current.username()) : username,
						"phone", phone == null ? "" : phone), null, null));
		return users.findView(id).orElseThrow();
	}

	@Transactional
	public UserView disable(ActorContext actor, UUID id, long expectedVersion, String reason) {
		actor.requirePermission("iam.user.manage");
		String text = requireReason(reason);
		UserView current = lock(id, expectedVersion);
		if (id.equals(actor.userId())) {
			throw BusinessException.stateNotAllowed("You cannot disable your own account.");
		}
		if (!"ACTIVE".equals(current.status()) && !"LOCKED".equals(current.status())) {
			throw BusinessException.stateNotAllowed("The account is already disabled.");
		}
		if (current.roles().contains(ADMIN_ROLE) && users.countOtherActiveAdmins(id) == 0) {
			throw BusinessException.stateNotAllowed("The last active system admin cannot be disabled.");
		}
		Instant now = clock.now();
		if (!users.setStatus(id, "DISABLED", expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		sessions.revokeAllForUser(id, now);
		audit.append(AuditEntry.forUser(actor, AuditAction.USER_DISABLED, "USER", id, null,
				Map.of("status", current.status()), Map.of("status", "DISABLED"), text, null));
		return users.findView(id).orElseThrow();
	}

	@Transactional
	public UserView enable(ActorContext actor, UUID id, long expectedVersion) {
		actor.requirePermission("iam.user.manage");
		UserView current = lock(id, expectedVersion);
		if ("ACTIVE".equals(current.status())) {
			throw BusinessException.stateNotAllowed("The account is already active.");
		}
		if (!users.setStatus(id, "ACTIVE", expectedVersion, clock.now())) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.USER_ENABLED, "USER", id, null,
				Map.of("status", current.status()), Map.of("status", "ACTIVE"), null, null));
		return users.findView(id).orElseThrow();
	}

	@Transactional
	public UserView changeRoles(ActorContext actor, UUID id, long expectedVersion, List<String> roleCodes,
			List<UUID> scopeZoneIds, String reason) {
		actor.requirePermission("iam.role.manage");
		String text = requireReason(reason);
		if (roleCodes == null) {
			throw BusinessException.validation("roleCodes", "REQUIRED", "roleCodes is required.");
		}
		Set<String> codes = new LinkedHashSet<>(roleCodes);
		Set<UUID> zones = new LinkedHashSet<>(scopeZoneIds == null ? List.of() : scopeZoneIds);
		UserView current = lock(id, expectedVersion);
		if (id.equals(actor.userId())) {
			throw BusinessException.stateNotAllowed("You cannot change your own roles.");
		}
		Map<String, UUID> roleIds = users.roleIdsByCode(codes);
		for (String code : codes) {
			if (!roleIds.containsKey(code)) {
				throw BusinessException.validation("roleCodes", "UNKNOWN", "Unknown role: " + code + ".");
			}
		}
		if (!users.existingZoneIds(zones).containsAll(zones)) {
			throw BusinessException.validation("scopeZoneIds", "UNKNOWN", "A scope zone does not exist.");
		}
		if (current.roles().contains(ADMIN_ROLE) && !codes.contains(ADMIN_ROLE)
				&& "ACTIVE".equals(current.status()) && users.countOtherActiveAdmins(id) == 0) {
			throw BusinessException.stateNotAllowed("The last active system admin cannot lose the admin role.");
		}

		Instant now = clock.now();
		users.replaceRoles(id, roleIds.values(), actor.userId(), now);
		users.replaceScopes(id, zones, actor.userId(), now);
		if (!users.touchVersion(id, expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		Map<String, Object> before = new LinkedHashMap<>();
		before.put("roles", current.roles());
		before.put("scopeZoneIds", current.scopeZoneIds().stream().map(UUID::toString).toList());
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("roles", codes.stream().sorted().toList());
		after.put("scopeZoneIds", zones.stream().map(UUID::toString).sorted().toList());
		audit.append(AuditEntry.forUser(actor, AuditAction.ROLE_CHANGED, "USER", id, null, before, after, text, null));
		return users.findView(id).orElseThrow();
	}

	private UserView lock(UUID id, long expectedVersion) {
		UserView current = users.findForUpdate(id).orElseThrow(() -> BusinessException.notFound("User"));
		if (current.version() != expectedVersion) {
			throw BusinessException.versionConflict(current.version());
		}
		return current;
	}

	private static String requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw BusinessException.validation("reason", "REQUIRED", "A reason is required.");
		}
		return reason.trim();
	}

	private static String normalizeUsername(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String username = value.trim().toLowerCase();
		if (!username.matches("^[a-z0-9._-]{3,32}$")) {
			throw BusinessException.validation("username", "INVALID",
					"A username has 3 to 32 characters: letters, digits, dot, underscore or hyphen.");
		}
		return username;
	}

	private static String normalizePhone(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String phone = value.trim();
		if (!phone.matches("^\\+?[0-9 ]{8,15}$")) {
			throw BusinessException.validation("phone", "INVALID",
					"A phone number has 8 to 15 digits and may start with +.");
		}
		return phone;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
