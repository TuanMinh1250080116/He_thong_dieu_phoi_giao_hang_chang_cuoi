package vn.edu.finalism.logistics.modules.dispatch.application;

import java.util.UUID;

import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment;

public record AssignmentResult(Assignment assignment, long decisionVersion, UUID deliveryId) {
}
