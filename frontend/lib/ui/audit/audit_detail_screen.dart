import 'dart:convert';

import 'package:flutter/material.dart';

import '../common.dart';
import '../kit/kit.dart';

class AuditDetailScreen extends StatelessWidget {
  const AuditDetailScreen({super.key, required this.record});

  final Map<String, dynamic> record;

  static String _pretty(Object? value) =>
      value == null ? 'Không có' : const JsonEncoder.withIndent('  ').convert(value);

  @override
  Widget build(BuildContext context) {
    final actor = record['actor'] as Map<String, dynamic>;
    return WindowFrame(
      title: 'Chi tiết nhật ký',
      footer: 'Chỉ đọc — không có Sửa/Xóa.',
      body: PageBody(
        maxWidth: 820,
        children: [
          FormGroup(
            title: record['action'] as String,
            children: [
              InfoRow('Thời điểm', formatTime(record['occurredAt'] as String?)),
              InfoRow('Người thực hiện', '${actor['name'] ?? (actor['type'] == 'SYSTEM' ? 'Hệ thống' : '—')}'),
              InfoRow('Đối tượng', '${record['entityType']}'),
              InfoRow('Mã đối tượng', '${record['entityId']}'),
              InfoRow('Correlation ID', '${record['correlationId']}'),
              if (record['reason'] != null) InfoRow('Lý do', '${record['reason']}'),
            ],
          ),
          FormGroup(
            title: 'Trước (before)',
            children: [SelectableText(_pretty(record['before']), style: const TextStyle(fontFamily: 'monospace'))],
          ),
          FormGroup(
            title: 'Sau (after)',
            children: [SelectableText(_pretty(record['after']), style: const TextStyle(fontFamily: 'monospace'))],
          ),
        ],
      ),
    );
  }
}
