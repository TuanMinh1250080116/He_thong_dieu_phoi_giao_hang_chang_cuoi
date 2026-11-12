package vn.edu.finalism.logistics.modules.identityaccess.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.identityaccess.application.AdminUserHandler;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AdminUserRepository.UserView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1/admin")
class AdminUserController {

	private final AdminUserHandler handler;

	AdminUserController(AdminUserHandler handler) {
		this.handler = handler;
	}

	record CreateUserRequest(@NotNull String email, String username, @NotNull String displayName, String phone,
			@NotNull String password) {
	}

	record UpdateUserRequest(@NotNull String displayName, String username, String phone) {
	}

	record ReasonRequest(@NotNull String reason) {
	}

	record RolesRequest(@NotNull List<String> roleCodes, List<UUID> scopeZoneIds, @NotNull String reason) {
	}

	record UserPage(List<Map<String, Object>> items, String nextCursor, Instant asOf) {
	}

	@GetMapping("/users")
	UserPage list(ActorContext actor, @RequestParam(required = false) String status,
			@RequestParam(required = false) String role, @RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		PageResult<UserView> page = handler.list(actor, status, role, q, cursor, limit);
		return new UserPage(page.items().stream().map(AdminUserController::dto).toList(), page.nextCursor(),
				page.asOf());
	}

	@PostMapping("/users")
	ResponseEntity<Map<String, Object>> create(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody CreateUserRequest request) {
		Outcome<UserView> outcome = handler.create(actor, IdempotencyKey.fromHeader(idempotencyKey),
				new AdminUserHandler.CreateUser(request.email(), request.username(), request.displayName(), request.phone(),
						request.password()));
		UserView user = outcome.value();
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(URI.create("/api/v1/admin/users/" + user.id()));
		return response.header(HttpHeaders.ETAG, IfMatch.etag(user.version())).body(dto(user));
	}

	@GetMapping("/users/{userId}")
	ResponseEntity<Map<String, Object>> get(ActorContext actor, @PathVariable UUID userId) {
		UserView user = handler.get(actor, userId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(user.version())).body(dto(user));
	}

	@PatchMapping("/users/{userId}")
	ResponseEntity<Map<String, Object>> update(ActorContext actor, @PathVariable UUID userId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody UpdateUserRequest request) {
		return ok(handler.updateProfile(actor, userId, IfMatch.parse(ifMatch), request.displayName(), request.username(), request.phone()));
	}

	@PostMapping("/users/{userId}/disable")
	ResponseEntity<Map<String, Object>> disable(ActorContext actor, @PathVariable UUID userId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody ReasonRequest request) {
		return ok(handler.disable(actor, userId, IfMatch.parse(ifMatch), request.reason()));
	}

	@PostMapping("/users/{userId}/enable")
	ResponseEntity<Map<String, Object>> enable(ActorContext actor, @PathVariable UUID userId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return ok(handler.enable(actor, userId, IfMatch.parse(ifMatch)));
	}

	@PutMapping("/users/{userId}/roles")
	ResponseEntity<Map<String, Object>> roles(ActorContext actor, @PathVariable UUID userId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody RolesRequest request) {
		return ok(handler.changeRoles(actor, userId, IfMatch.parse(ifMatch), request.roleCodes(),
				request.scopeZoneIds(), request.reason()));
	}

	record RolePermissionsRequest(@NotNull java.util.List<String> permissionCodes, @NotBlank String reason) {
	}

	@GetMapping("/roles")
	Map<String, Object> roles(ActorContext actor) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", handler.roles(actor).stream().map(AdminUserController::roleDto).toList());
		body.put("allPermissions", handler.allPermissions(actor));
		return body;
	}

	@PutMapping("/roles/{roleCode}/permissions")
	Map<String, Object> changeRolePermissions(ActorContext actor, @PathVariable String roleCode,
			@Valid @RequestBody RolePermissionsRequest request) {
		return roleDto(handler.changeRolePermissions(actor, roleCode, request.permissionCodes(), request.reason()));
	}

	private static Map<String, Object> roleDto(vn.edu.finalism.logistics.modules.identityaccess.application.port.AdminUserRepository.RoleView r) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("code", r.code());
		dto.put("permissions", r.permissions());
		return dto;
	}

	private static ResponseEntity<Map<String, Object>> ok(UserView user) {
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(user.version())).body(dto(user));
	}

	private static Map<String, Object> dto(UserView u) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", u.id());
		dto.put("email", u.email());
		dto.put("username", u.username());
		dto.put("displayName", u.displayName());
		dto.put("phone", u.phone());
		dto.put("status", u.status());
		dto.put("roles", u.roles());
		dto.put("scopeZoneIds", u.scopeZoneIds());
		dto.put("createdAt", u.createdAt());
		dto.put("version", u.version());
		return dto;
	}
}
