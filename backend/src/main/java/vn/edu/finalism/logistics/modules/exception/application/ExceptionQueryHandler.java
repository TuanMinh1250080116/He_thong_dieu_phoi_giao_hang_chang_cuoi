package vn.edu.finalism.logistics.modules.exception.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionType;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionView;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.QueueFilter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class ExceptionQueryHandler {

	private final ExceptionRepository exceptions;
	private final ExceptionAccess access;
	private final ClockPort clock;

	ExceptionQueryHandler(ExceptionRepository exceptions, ExceptionAccess access, ClockPort clock) {
		this.exceptions = exceptions;
		this.access = access;
		this.clock = clock;
	}

	public record Query(String status, String severity, String type, UUID assigneeId, Boolean blocking,
			UUID deliveryId, Instant from, Instant to, String cursor, Integer limit) {
	}

	@Transactional(readOnly = true)
	public PageResult<ExceptionView> queue(ActorContext actor, Query query) {
		actor.requirePermission("exception.view");
		int limit = PageCursor.normalizeLimit(query.limit());
		QueueFilter filter = new QueueFilter(actor.operationScopeIds(), access.driverIdOf(actor), query.status(),
				query.severity(), query.type(), query.assigneeId(), query.blocking(), query.deliveryId(), query.from(),
				query.to(), query.cursor() == null || query.cursor().isBlank() ? null : PageCursor.decode(query.cursor()));
		List<ExceptionView> rows = exceptions.queue(filter, limit + 1);
		boolean more = rows.size() > limit;
		List<ExceptionView> page = more ? rows.subList(0, limit) : rows;
		String next = more ? new PageCursor(page.get(page.size() - 1).exception().reportedAt(),
				page.get(page.size() - 1).exception().id()).encode() : null;
		return new PageResult<>(List.copyOf(page), next, clock.now());
	}

	@Transactional(readOnly = true)
	public ExceptionDetail detail(ActorContext actor, UUID id) {
		actor.requirePermission("exception.view");
		ExceptionView view = access.requireVisible(actor, id);
		return new ExceptionDetail(view, exceptions.notes(id), exceptions.evidenceIds(id),
				access.isAssignedDriver(actor, view));
	}

	@Transactional(readOnly = true)
	public List<ExceptionType> types() {
		return exceptions.activeTypes();
	}
}
