package vn.edu.finalism.logistics.modules.audit.application;

public interface AuditWriter {

	void append(AuditEntry entry);
}
