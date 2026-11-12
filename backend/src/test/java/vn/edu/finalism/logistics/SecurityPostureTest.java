package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class SecurityPostureTest {

	private static final Set<String> PUBLIC = Set.of("POST /api/v1/auth/login", "POST /api/v1/auth/refresh",
			"GET /api/v1/evidence/view/{token}");

	@Autowired
	WebApplicationContext context;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mapping;

	@Test
	void everyApiEndpointRejectsAnonymousCallersExceptTheDocumentedPublicOnes() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		List<String> leaks = new ArrayList<>();
		int checked = 0;
		for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
			if (info.getPathPatternsCondition() == null) {
				continue;
			}
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (!pattern.startsWith("/api/")) {
					continue;
				}
				for (var method : info.getMethodsCondition().getMethods()) {
					String label = method.name() + " " + pattern;
					if (PUBLIC.contains(label)) {
						continue;
					}
					String path = pattern.replaceAll("\\{[^/}]+}", UUID.randomUUID().toString());
					int status = mvc.perform(MockMvcRequestBuilders.request(
							org.springframework.http.HttpMethod.valueOf(method.name()), path)
							.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
					checked++;
					if (status != 401) {
						leaks.add(label + " -> " + status);
					}
				}
			}
		}
		assertThat(checked).as("endpoints checked").isGreaterThan(60);
		assertThat(leaks).isEmpty();
	}

	@Test
	void errorResponsesNeverLeakStackTracesOrInternalNames() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		String body = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn().getResponse()
				.getContentAsString();

		assertThat(body).doesNotContain("Exception").doesNotContain("at vn.edu").doesNotContain("org.springframework");
	}
}
