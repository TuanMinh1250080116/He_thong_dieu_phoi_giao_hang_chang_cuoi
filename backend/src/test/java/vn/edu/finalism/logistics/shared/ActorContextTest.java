package vn.edu.finalism.logistics.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ActorContextTest {

	private final UUID zone = UUID.randomUUID();

	private ActorContext actor(Set<String> permissions) {
		return new ActorContext(UUID.randomUUID(), Set.of("DISPATCHER"), permissions, Set.of(zone), "corr-1");
	}

	@Test
	void checksPermissionAndScope() {
		ActorContext actor = actor(Set.of("dispatch.assign"));
		assertThat(actor.hasPermission("dispatch.assign")).isTrue();
		assertThat(actor.hasPermission("dispatch.recommendation.override")).isFalse();
		assertThat(actor.hasScope(zone)).isTrue();
		assertThat(actor.hasScope(UUID.randomUUID())).isFalse();
	}

	@Test
	void copiesSetsSoCallerCannotChangeThem() {
		Set<String> permissions = new HashSet<>(Set.of("dispatch.assign"));
		ActorContext actor = actor(permissions);
		permissions.add("iam.role.manage");
		assertThat(actor.hasPermission("iam.role.manage")).isFalse();
		assertThatThrownBy(() -> actor.permissions().add("x")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsMissingUserId() {
		assertThatThrownBy(() -> new ActorContext(null, Set.of(), Set.of(), Set.of(), "c"))
				.isInstanceOf(NullPointerException.class);
	}
}
