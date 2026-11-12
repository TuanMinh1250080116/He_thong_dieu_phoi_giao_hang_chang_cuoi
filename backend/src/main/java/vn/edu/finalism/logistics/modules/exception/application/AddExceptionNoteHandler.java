package vn.edu.finalism.logistics.modules.exception.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class AddExceptionNoteHandler {

	private final ExceptionRepository exceptions;
	private final ExceptionAccess access;
	private final ClockPort clock;

	AddExceptionNoteHandler(ExceptionRepository exceptions, ExceptionAccess access, ClockPort clock) {
		this.exceptions = exceptions;
		this.access = access;
		this.clock = clock;
	}

	@Transactional
	public ExceptionDetail handle(ActorContext actor, UUID exceptionId, String body) {
		actor.requirePermission("exception.context.add");
		if (body == null || body.isBlank()) {
			throw BusinessException.validation("body", "REQUIRED", "The note must not be empty.");
		}
		ExceptionView view = access.requireVisible(actor, exceptionId);
		if (!view.exception().status().isOpenState()) {
			throw BusinessException.stateNotAllowed("Notes can only be added to an open exception.");
		}
		exceptions.insertNote(exceptionId, actor.userId(), "CONTEXT", body.trim(), clock.now());
		return new ExceptionDetail(view, exceptions.notes(exceptionId), exceptions.evidenceIds(exceptionId),
				access.isAssignedDriver(actor, view));
	}
}
