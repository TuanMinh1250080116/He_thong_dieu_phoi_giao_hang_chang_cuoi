package vn.edu.finalism.logistics.bootstrap;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;

class ApiExceptionHandlerTest {

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new FakeController())
				.setControllerAdvice(new ApiExceptionHandler())
				.addFilters(new CorrelationIdFilter())
				.build();
	}

	@Test
	void businessExceptionBecomesProblemJsonWithStableCode() throws Exception {
		mvc.perform(get("/conflict").header("X-Correlation-Id", "abc-123"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.retryable").value(false))
				.andExpect(jsonPath("$.correlationId").value("abc-123"))
				.andExpect(jsonPath("$.details.currentVersion").value(8));
	}

	@Test
	void validationErrorListsFields() throws Exception {
		mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("reason"));
	}

	@Test
	void fieldValidationFromBusinessCodeAlsoListsErrors() throws Exception {
		mvc.perform(get("/bad-header"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
				.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
	}

	@Test
	void unexpectedErrorDoesNotLeakInternals() throws Exception {
		mvc.perform(get("/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("UNEXPECTED_FAILURE"))
				.andExpect(content().string(not(containsString("secret internals"))));
	}

	@Test
	void filterGeneratesCorrelationIdWhenHeaderIsInvalid() throws Exception {
		mvc.perform(get("/conflict").header("X-Correlation-Id", "bad id with spaces!"))
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(header().string("X-Correlation-Id", not(containsString(" "))));
	}

	@Test
	void everyErrorCodeHasAnHttpStatus() {
		for (ErrorCode code : ErrorCode.values()) {
			org.assertj.core.api.Assertions.assertThat(ApiExceptionHandler.statusOf(code)).isNotNull();
		}
	}

	@RestController
	static class FakeController {

		record Body(@NotBlank String reason) {
		}

		@GetMapping("/conflict")
		void conflict() {
			throw new BusinessException(ErrorCode.VERSION_CONFLICT, "stale", Map.of("currentVersion", 8));
		}

		@GetMapping("/bad-header")
		void badHeader() {
			throw BusinessException.validation("Idempotency-Key", "REQUIRED", "Idempotency-Key header is required.");
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("secret internals");
		}

		@PostMapping("/validate")
		void validate(@Valid @RequestBody Body body) {
		}
	}
}
