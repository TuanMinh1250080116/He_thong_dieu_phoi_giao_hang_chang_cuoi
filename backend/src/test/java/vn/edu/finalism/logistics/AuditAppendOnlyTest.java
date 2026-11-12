package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditAppendOnlyTest {

	@Autowired
	JdbcTemplate jdbc;

	private UUID insertRecord() {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO audit.audit_records
				  (id, occurred_at, actor_type, action, entity_type, entity_id, correlation_id)
				VALUES (?, ?, 'SYSTEM', 'ORDER_CREATED', 'ORDER', ?, 'test-correlation')
				""", id, OffsetDateTime.parse("2026-10-26T10:00:00Z"), UUID.randomUUID());
		return id;
	}

	@Test
	void insertIsAllowed() {
		UUID id = insertRecord();
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM audit.audit_records WHERE id = ?", Integer.class, id);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void updateIsRejected() {
		UUID id = insertRecord();
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE audit.audit_records SET action = 'TAMPERED' WHERE id = ?", id))
				.hasMessageContaining("append-only");
	}

	@Test
	void deleteIsRejected() {
		UUID id = insertRecord();
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit.audit_records WHERE id = ?", id))
				.hasMessageContaining("append-only");
	}

	@Test
	void truncateIsRejected() {
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit.audit_records"))
				.hasMessageContaining("append-only");
	}

}
