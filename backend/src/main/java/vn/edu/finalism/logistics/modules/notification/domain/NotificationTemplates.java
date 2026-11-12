package vn.edu.finalism.logistics.modules.notification.domain;

import java.util.Map;
import java.util.Optional;

public final class NotificationTemplates {

	public record Rendered(String templateCode, String title, String body) {
	}

	private static final java.util.Set<String> EMAIL_TEMPLATES = java.util.Set.of("ORDER_CREATED", "DRIVER_ASSIGNED",
			"DELIVERY_STARTED", "DELIVERY_COMPLETED", "ORDER_AFFECTED", "DISPATCH_CANCELLED", "ORDER_CANCELLED");

	private NotificationTemplates() {
	}

	public static boolean sendsEmail(String templateCode) {
		return EMAIL_TEMPLATES.contains(templateCode);
	}

	public static Optional<Rendered> render(String eventType, Map<String, String> data) {
		return switch (eventType) {
			case "OrderCreated" -> Optional.of(new Rendered("ORDER_CREATED",
					"Đơn hàng " + data.getOrDefault("orderCode", "") + " đã được tạo",
					"Đơn hàng của bạn đã được ghi nhận trong hệ thống."));
			case "OrderCancelled" -> Optional.of(new Rendered("ORDER_CANCELLED",
					"Đơn hàng đã được hủy",
					"Đơn " + data.getOrDefault("orderCode", "") + " đã được hủy."));
			case "DriverAssigned" -> Optional.of(new Rendered("DRIVER_ASSIGNED",
					"Bạn có chuyến giao mới",
					"Chuyến " + data.getOrDefault("batchCode", "") + " đã được giao cho bạn. Hãy nhận hoặc từ chối chuyến."));
			case "DeliveryAccepted" -> Optional.of(new Rendered("DELIVERY_ACCEPTED",
					"Tài xế đã nhận chuyến",
					data.getOrDefault("driverName", "Tài xế") + " đã nhận chuyến " + data.getOrDefault("batchCode", "") + "."));
			case "DriverAssignmentRejected" -> Optional.of(new Rendered("DRIVER_ASSIGNMENT_REJECTED",
					"Tài xế đã từ chối chuyến",
					data.getOrDefault("driverName", "Tài xế") + " đã từ chối chuyến " + data.getOrDefault("batchCode", "")
							+ ". Hãy chọn tài xế khác."));
			case "DriverUnassigned" -> Optional.of(new Rendered("DRIVER_UNASSIGNED",
					"Chuyến giao đã được giao cho người khác",
					"Chuyến " + data.getOrDefault("batchCode", "") + " không còn thuộc về bạn."));
			case "DispatchCancelled" -> Optional.of(new Rendered("DISPATCH_CANCELLED",
					"Chuyến giao đã bị hủy",
					"Chuyến " + data.getOrDefault("batchCode", "") + " đã bị điều phối viên hủy."));
			case "DeliveryExceptionReported" -> Optional.of(new Rendered("EXCEPTION_REPORTED",
					"Có sự cố giao hàng mới",
					"Chuyến " + data.getOrDefault("deliveryCode", "") + " báo sự cố " + data.getOrDefault("exceptionCode", "")
							+ " (" + data.getOrDefault("typeName", "") + ")."));
			case "DeliveryExceptionInvestigating" -> Optional.of(new Rendered("EXCEPTION_INVESTIGATING",
					"Sự cố đang được xử lý",
					"Sự cố " + data.getOrDefault("exceptionCode", "") + " đã được quản lý vận hành tiếp nhận."));
			case "DeliveryExceptionResolved" -> Optional.of(new Rendered("EXCEPTION_RESOLVED",
					"Sự cố đã được giải quyết",
					"Sự cố " + data.getOrDefault("exceptionCode", "") + " đã được giải quyết."));
			case "DeliveryExceptionCancelled" -> Optional.of(new Rendered("EXCEPTION_CANCELLED",
					"Báo cáo sự cố đã bị hủy",
					"Báo cáo " + data.getOrDefault("exceptionCode", "") + " đã bị hủy vì không hợp lệ hoặc trùng lặp."));
			case "OrderAffectedByException" -> Optional.of(new Rendered("ORDER_AFFECTED",
					"Đơn hàng của bạn gặp sự cố khi giao",
					"Chuyến giao " + data.getOrDefault("deliveryCode", "") + " gặp sự cố. Chúng tôi đang xử lý."));
			case "DeliveryStarted" -> Optional.of(new Rendered("DELIVERY_STARTED",
					"Đơn hàng của bạn đang được giao",
					"Chuyến giao " + data.getOrDefault("deliveryCode", "") + " đã bắt đầu."));
			case "DeliveryCompleted" -> Optional.of(new Rendered("DELIVERY_COMPLETED",
					"Đơn hàng đã được giao",
					"Chuyến giao " + data.getOrDefault("deliveryCode", "") + " đã hoàn tất."));
			default -> Optional.empty();
		};
	}
}
