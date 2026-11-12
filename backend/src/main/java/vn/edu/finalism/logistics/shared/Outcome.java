package vn.edu.finalism.logistics.shared;

public record Outcome<T>(T value, boolean replayed) {

	public static <T> Outcome<T> fresh(T value) {
		return new Outcome<>(value, false);
	}

	public static <T> Outcome<T> replay(T value) {
		return new Outcome<>(value, true);
	}
}
