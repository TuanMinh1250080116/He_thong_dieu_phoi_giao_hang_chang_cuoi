package vn.edu.finalism.logistics.shared;

public interface CommandHandler<C, R> {

	R handle(C command, CommandMetadata metadata);
}
