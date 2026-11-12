import 'dart:async';
import 'dart:io';

import 'package:http/http.dart' as http;

import 'api_client.dart';

String describeError(Object error) {
  if (error is ApiException) {
    if (error.code == 'COMPLETION_PREREQUISITES_MISSING' &&
        ((error.details['blockingExceptionIds'] as List?)?.isNotEmpty ?? false)) {
      return 'Chưa thể hoàn tất: còn sự cố chưa được xử lý.';
    }
    final friendly = _byCode[error.code];
    if (friendly != null) {
      return friendly;
    }
    if (error.code == 'VALIDATION_ERROR' && error.errors.isNotEmpty) {
      return error.errors.map((e) => '${e['field']}: ${e['message']}').join('\n');
    }
    if (error.code == 'POD_POLICY_NOT_SATISFIED') {
      final missing = (error.details['missing'] as List?)?.join(', ') ?? '';
      return 'Bằng chứng giao hàng chưa đủ theo chính sách. Còn thiếu: $missing';
    }
    if (error.code == 'DRIVER_INELIGIBLE') {
      final reasons = (error.details['reasons'] as List?)?.join(', ') ?? '';
      return 'Tài xế không còn đủ điều kiện ($reasons). Hãy tạo gợi ý mới.';
    }
    if (error.status == 404 && error.message.startsWith('No static resource')) {
      return 'Máy chủ chưa có chức năng này. Có thể backend đang chạy bản cũ: hãy khởi động lại backend.';
    }
    return error.message;
  }
  if (error is SocketException || error is http.ClientException || error is TimeoutException || error is HandshakeException) {
    return 'Không kết nối được máy chủ. Kiểm tra backend đã chạy chưa.';
  }
  return 'Lỗi trong ứng dụng khi xử lý dữ liệu (${error.runtimeType}). Hãy chụp màn hình và báo lại.';
}

const _byCode = <String, String>{
  'AUTHENTICATION_FAILED': 'Sai email hoặc mật khẩu.',
  'ACCOUNT_LOCKED': 'Tài khoản tạm khóa do nhập sai nhiều lần. Thử lại sau ít phút.',
  'ACCOUNT_DISABLED': 'Tài khoản đã bị vô hiệu hóa.',
  'AUTHENTICATION_REQUIRED': 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.',
  'FORBIDDEN': 'Bạn không có quyền thực hiện thao tác này.',
  'RESOURCE_NOT_FOUND': 'Không tìm thấy dữ liệu.',
  'VERSION_CONFLICT': 'Dữ liệu vừa được người khác thay đổi. Hãy tải lại rồi thử lại.',
  'STATE_TRANSITION_NOT_ALLOWED': 'Thao tác này không hợp lệ ở trạng thái hiện tại.',
  'RECOMMENDATION_EXPIRED': 'Gợi ý đã hết hạn. Hãy tạo gợi ý mới.',
  'RECOMMENDATION_INVALIDATED': 'Gợi ý không còn hiệu lực. Hãy tạo gợi ý mới.',
  'ASSIGNMENT_CONFLICT': 'Tài xế vừa nhận chuyến khác. Hãy tạo gợi ý mới.',
  'OTP_INVALID_OR_EXPIRED': 'Mã xác nhận không đúng hoặc đã hết hạn.',
  'OTP_LOCKED': 'Nhập sai quá nhiều lần. Hãy gửi mã mới cho người nhận.',
  'ZONE_UNRESOLVED': 'Điểm lấy hàng nằm ngoài vùng phục vụ.',
  'COMPLETION_PREREQUISITES_MISSING': 'Chưa thể hoàn tất: còn điểm dừng chưa giao xong.',
  'EVIDENCE_INVALID': 'Ảnh không hợp lệ hoặc chưa được xác minh.',
  'IDEMPOTENCY_IN_PROGRESS': 'Yêu cầu đang được xử lý, vui lòng đợi.',
  'RATE_LIMITED': 'Thao tác quá nhanh, hãy thử lại sau.',
};
