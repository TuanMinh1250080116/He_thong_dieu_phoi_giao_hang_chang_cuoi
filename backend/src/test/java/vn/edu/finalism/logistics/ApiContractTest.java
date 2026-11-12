package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import vn.edu.finalism.logistics.shared.ErrorCode;

class ApiContractTest {

	private static Map<String, Object> spec;

	@BeforeAll
	static void loadSpec() throws IOException {
		Path file = Path.of(System.getProperty("user.dir")).resolve("../api/openapi.yaml").normalize();
		try (InputStream in = Files.newInputStream(file)) {
			spec = new Yaml().load(in);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@Test
	void errorCodesInTheSpecMatchTheJavaEnum() {
		Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
		List<?> documented = (List<?>) map(schemas.get("ErrorCode")).get("enum");

		assertThat(documented.stream().map(String::valueOf).toList())
				.containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
	}

	@Test
	void implementedEndpointsAreDocumented() {
		Map<String, Object> paths = map(spec.get("paths"));

		assertThat(map(paths.get("/auth/login"))).containsKey("post");
		assertThat(map(paths.get("/auth/refresh"))).containsKey("post");
		assertThat(map(paths.get("/auth/logout"))).containsKey("post");
		assertThat(map(paths.get("/me"))).containsKey("get");
	}

	@Test
	void everyReferenceResolves() {
		List<String> broken = new ArrayList<>();
		collectBrokenRefs(spec, broken);
		assertThat(broken).isEmpty();
	}

	@Test
	void operationIdsAreUnique() {
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();
		map(spec.get("paths")).values().forEach(pathItem -> map(pathItem).values().forEach(operation -> {
			if (operation instanceof Map<?, ?> op && op.get("operationId") != null) {
				String id = String.valueOf(op.get("operationId"));
				if (!seen.add(id)) {
					duplicates.add(id);
				}
			}
		}));
		assertThat(seen).isNotEmpty();
		assertThat(duplicates).isEmpty();
	}

	private static void collectBrokenRefs(Object node, List<String> broken) {
		if (node instanceof Map<?, ?> m) {
			m.forEach((key, value) -> {
				if ("$ref".equals(key) && value instanceof String ref && !resolves(ref)) {
					broken.add(ref);
				}
				collectBrokenRefs(value, broken);
			});
		}
		else if (node instanceof List<?> list) {
			list.forEach(item -> collectBrokenRefs(item, broken));
		}
	}

	private static boolean resolves(String ref) {
		if (!ref.startsWith("#/")) {
			return false;
		}
		Object current = spec;
		for (String part : ref.substring(2).split("/")) {
			if (!(current instanceof Map<?, ?> m) || !m.containsKey(part)) {
				return false;
			}
			current = m.get(part);
		}
		return true;
	}
}
