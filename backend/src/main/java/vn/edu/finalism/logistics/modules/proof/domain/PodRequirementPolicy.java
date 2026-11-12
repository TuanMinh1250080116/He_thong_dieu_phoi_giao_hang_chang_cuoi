package vn.edu.finalism.logistics.modules.proof.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PodRequirementPolicy {

	private PodRequirementPolicy() {
	}

	public static List<String> missingEvidence(Map<String, Object> expression, Set<String> provided) {
		Object op = expression.get("op");
		List<String> items = new ArrayList<>();
		if (expression.get("items") instanceof List<?> list) {
			list.forEach(item -> items.add(String.valueOf(item)));
		}
		if ("ALL_OF".equals(op)) {
			return items.stream().filter(item -> !provided.contains(item)).toList();
		}
		boolean anyProvided = items.stream().anyMatch(provided::contains);
		return anyProvided ? List.of() : List.copyOf(items);
	}

	public static boolean recipientNameRequired(String rule, boolean directHandoff) {
		return "ALWAYS".equals(rule) || ("DIRECT_HANDOFF".equals(rule) && directHandoff);
	}
}
