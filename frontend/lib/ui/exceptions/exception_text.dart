const severityText = <String, String>{
  'LOW': 'Thấp',
  'MEDIUM': 'Trung bình',
  'HIGH': 'Cao',
  'CRITICAL': 'Nghiêm trọng',
};

const impactText = <String, String>{
  'NON_BLOCKING': 'Không chặn giao hàng',
  'BLOCKS_STOP': 'Chặn điểm dừng',
  'BLOCKS_DELIVERY_COMPLETION': 'Chặn hoàn tất chuyến',
  'CUSTOMER_IMPACTING': 'Ảnh hưởng khách hàng',
};

const typeText = <String, String>{
  'RECIPIENT_ABSENT': 'Người nhận vắng mặt',
  'ADDRESS_INVALID': 'Địa chỉ không hợp lệ',
  'VEHICLE_BREAKDOWN': 'Xe hỏng',
  'PACKAGE_DAMAGED': 'Hàng bị hư hỏng',
  'ACCESS_DENIED': 'Bị từ chối vào',
};

const outcomeText = <String, String>{
  'RESUME_DELIVERY': 'Tiếp tục giao hàng',
  'REATTEMPT_LATER': 'Giao lại sau',
  'RETURN_TO_SENDER': 'Trả lại người gửi',
  'NO_ACTION_NEEDED': 'Không cần xử lý thêm',
};

String typeLabel(String code, String fallback) => typeText[code] ?? fallback;
