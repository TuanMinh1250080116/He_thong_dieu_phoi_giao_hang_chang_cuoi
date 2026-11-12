package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.administration.domain.ConfigValidation;
import vn.edu.finalism.logistics.shared.BusinessException;

class ConfigValidationTest {

	private static BigDecimal w(String value) {
		return new BigDecimal(value);
	}

	private void rule(String d, String c, String a, String z, int ttl) {
		ConfigValidation.validateRule("Rule", w(d), w(c), w(a), w(z), ttl, 15000, 600, 50);
	}

	@Test
	void weightsMustAddUpToExactlyOne() {
		assertThatCode(() -> rule("0.4", "0.25", "0.15", "0.2", 300)).doesNotThrowAnyException();
		assertThatCode(() -> rule("0.2500", "0.2500", "0.2500", "0.2500", 300)).doesNotThrowAnyException();
		assertThatThrownBy(() -> rule("0.4", "0.25", "0.15", "0.25", 300)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> rule("0.3333", "0.3333", "0.3333", "0.0000", 300)).isInstanceOf(BusinessException.class);
	}

	@Test
	void aWeightOutsideZeroToOneOrTooPreciseIsRejected() {
		assertThatThrownBy(() -> rule("1.2", "-0.2", "0", "0", 300)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> rule("0.12345", "0.5", "0.2", "0.17655", 300)).isInstanceOf(BusinessException.class);
	}

	@Test
	void theTimeToLiveStaysBetweenOneAndFifteenMinutes() {
		assertThatCode(() -> rule("0.4", "0.25", "0.15", "0.2", 60)).doesNotThrowAnyException();
		assertThatCode(() -> rule("0.4", "0.25", "0.15", "0.2", 900)).doesNotThrowAnyException();
		assertThatThrownBy(() -> rule("0.4", "0.25", "0.15", "0.2", 59)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> rule("0.4", "0.25", "0.15", "0.2", 901)).isInstanceOf(BusinessException.class);
	}

	@Test
	void aRuleNeedsANameAndSaneNumbers() {
		assertThatThrownBy(() -> ConfigValidation.validateRule(" ", w("1"), w("0"), w("0"), w("0"), 300, 1000, 600, 50))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validateRule("R", w("1"), w("0"), w("0"), w("0"), 300, 0, 600, 50))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validateRule("R", w("1"), w("0"), w("0"), w("0"), 300, 1000, 600, 201))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void aPodPolicyUsesKnownEvidenceKindsWithoutDuplicates() {
		assertThatCode(() -> ConfigValidation.validatePodPolicy("P", "ALL_OF", List.of("OTP", "SIGNATURE"), 50, "ALWAYS"))
				.doesNotThrowAnyException();
		assertThatCode(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of("PHOTO"), null, "NEVER"))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "SOME_OF", List.of("OTP"), 50, "ALWAYS"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of("FINGERPRINT"), 50, "ALWAYS"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of("OTP", "OTP"), 50, "ALWAYS"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of(), 50, "ALWAYS"))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void aPodPolicyLimitsTheGpsAccuracyAndNamesTheRecipientRule() {
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of("OTP"), 0, "ALWAYS"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of("OTP"), 5000, "ALWAYS"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validatePodPolicy("P", "ANY_OF", List.of("OTP"), 50, "SOMETIMES"))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void zoneCodesAreShortCapitalIdentifiers() {
		assertThatCode(() -> ConfigValidation.validateZoneCode("HCM-1")).doesNotThrowAnyException();
		assertThatThrownBy(() -> ConfigValidation.validateZoneCode("h")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validateZoneCode("has space")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ConfigValidation.validateZoneCode(null)).isInstanceOf(BusinessException.class);
	}
}
