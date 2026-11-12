package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditActorType;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditWriterTest {

	@Autowired
	AuditWriter auditWriter;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	private AuditEntry entry(String correlationId, Map<String, Object> after) {
		return new AuditEntry(AuditAction.ROLE_CHANGED, AuditActorType.USER, UUID.randomUUID(),
				"USER", UUID.randomUUID(), null, Map.of("roles", "CUSTOMER"), after,
				"promote", correlationId, UUID.randomUUID());
	}

	private int countByCorrelation(String correlationId) {
		return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE correlation_id = :c")
				.param("c", correlationId).query(Integer.class).single();
	}

	@Test
	void appendsSanitizedRecordInsideTransaction() {
		String correlationId = "audit-" + UUID.randomUUID();
		AuditEntry entry = entry(correlationId, Map.of("roles", "DISPATCHER", "password", "plain-secret"));

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> auditWriter.append(entry));

		Map<String, Object> row = jdbc.sql("""
				SELECT action, actor_type, reason, after_state::text AS after_text
				FROM audit.audit_records WHERE correlation_id = :c
				""").param("c", correlationId).query().singleRow();
		assertThat(row.get("action")).isEqualTo("ROLE_CHANGED");
		assertThat(row.get("actor_type")).isEqualTo("USER");
		assertThat(row.get("reason")).isEqualTo("promote");
		assertThat((String) row.get("after_text")).contains("DISPATCHER").contains("[REDACTED]")
				.doesNotContain("plain-secret");
	}

	@Test
	void refusesToWriteOutsideATransaction() {
		assertThatThrownBy(() -> auditWriter.append(entry("audit-" + UUID.randomUUID(), Map.of())))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void auditRollsBackTogetherWithTheBusinessTransaction() {
		String correlationId = "audit-" + UUID.randomUUID();
		AuditEntry entry = entry(correlationId, Map.of());

		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			auditWriter.append(entry);
			throw new IllegalStateException("business step failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(countByCorrelation(correlationId)).isZero();
	}

	@Test
	void writesSystemActorWithoutActorId() {
		String correlationId = "audit-" + UUID.randomUUID();
		AuditEntry entry = new AuditEntry(AuditAction.DISPATCH_RULE_CHANGED, AuditActorType.SYSTEM, null,
				"RULE", UUID.randomUUID(), null, null, null, null, correlationId, null);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> auditWriter.append(entry));

		assertThat(countByCorrelation(correlationId)).isEqualTo(1);
	}
}
