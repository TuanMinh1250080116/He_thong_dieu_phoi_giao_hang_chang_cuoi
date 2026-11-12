package vn.edu.finalism.logistics.modules.exception.application;

import java.util.List;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionView;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.NoteView;

public record ExceptionDetail(ExceptionView view, List<NoteView> notes, List<UUID> evidenceIds,
		boolean actorIsAssignedDriver) {
}
