package vn.edu.finalism.logistics.modules.exception.domain;

public enum ExceptionStatus {
	OPEN, INVESTIGATING, RESOLVED, CANCELLED;

	public boolean isOpenState() {
		return this == OPEN || this == INVESTIGATING;
	}
}
