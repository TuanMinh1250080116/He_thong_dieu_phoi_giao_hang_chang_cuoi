package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeedReferenceDataTest {

	@Autowired
	JdbcTemplate jdbc;

	private List<String> permissionsOf(String role) {
		return jdbc.queryForList("""
				SELECT p.code FROM iam.role_permissions rp
				JOIN iam.roles r ON r.id = rp.role_id
				JOIN iam.permissions p ON p.id = rp.permission_id
				WHERE r.code = ? ORDER BY p.code
				""", String.class, role);
	}

	@Test
	void seedsSixRolesAndTwentyFourPermissions() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM iam.roles", Integer.class)).isEqualTo(6);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM iam.permissions", Integer.class)).isEqualTo(24);
	}

	@Test
	void overridePermissionIsGrantedOnlyToTheOverriderRole() {
		java.util.List<String> roles = jdbc.queryForList("""
				SELECT r.code FROM iam.role_permissions rp
				JOIN iam.permissions p ON p.id = rp.permission_id
				JOIN iam.roles r ON r.id = rp.role_id
				WHERE p.code = 'dispatch.recommendation.override'
				""", String.class);
		assertThat(roles).containsExactly("DISPATCH_OVERRIDER");
	}

	@Test
	void onlyOperationsManagerCanManageExceptions() {
		assertThat(permissionsOf("OPS_MANAGER")).contains("exception.manage");
		assertThat(permissionsOf("DISPATCHER")).doesNotContain("exception.manage");
		assertThat(permissionsOf("DRIVER")).doesNotContain("exception.manage");
	}

	@Test
	void systemAdminHasNoDispatchOrExceptionAuthority() {
		assertThat(permissionsOf("SYSTEM_ADMIN"))
				.containsExactlyInAnyOrder("audit.view", "iam.role.manage", "iam.user.manage");
	}

	@Test
	void customerCanOnlyHandleOwnOrders() {
		assertThat(permissionsOf("CUSTOMER"))
				.containsExactlyInAnyOrder("order.create", "order.view.own", "proof.view");
	}

	@Test
	void driverCannotAssignItself() {
		assertThat(permissionsOf("DRIVER")).doesNotContain("dispatch.assign", "dispatch.reassign");
	}
}
