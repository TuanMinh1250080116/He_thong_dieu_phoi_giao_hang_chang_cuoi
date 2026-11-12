import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../state/auth_state.dart';
import '../common.dart';
import '../dialogs.dart';
import '../kit/kit.dart';
import 'exception_text.dart';

class ExceptionDetailScreen extends StatefulWidget {
  const ExceptionDetailScreen({super.key, required this.exceptionId});

  final String exceptionId;

  @override
  State<ExceptionDetailScreen> createState() => _ExceptionDetailScreenState();
}

class _ExceptionDetailScreenState extends State<ExceptionDetailScreen> {
  int _reloadKey = 0;

  Future<void> _run(Future<Object?> Function() action, {String? success}) async {
    final done = await guarded(context, action, success: success);
    if (done != null && mounted) {
      setState(() => _reloadKey++);
    }
  }

  Future<void> _investigate(ApiClient api, ApiResult result) async {
    final note = await askReason(context,
        title: 'Điều tra sự cố', confirmLabel: 'Nhận điều tra', label: 'Ghi chú điều tra');
    if (note == null || !mounted) {
      return;
    }
    final me = context.read<AuthState>().user?['id'] as String;
    await _run(
      () => api.post('/exceptions/${widget.exceptionId}/investigate',
          body: {'assigneeId': me, 'note': note}, ifMatch: result.etag),
      success: 'Đã nhận xử lý sự cố.',
    );
  }

  Future<void> _resolve(ApiClient api, ApiResult result) async {
    final choice = await showDialog<({String outcome, String note})>(
      context: context,
      builder: (_) => const _ResolveDialog(),
    );
    if (choice == null) {
      return;
    }
    await _run(
      () => api.post('/exceptions/${widget.exceptionId}/resolve',
          body: {'outcome': choice.outcome, 'note': choice.note}, ifMatch: result.etag, idempotent: true),
      success: 'Đã giải quyết sự cố.',
    );
  }

  Future<void> _cancel(ApiClient api, ApiResult result) async {
    final reason = await askReason(context, title: 'Hủy báo cáo sự cố', confirmLabel: 'Hủy báo cáo');
    if (reason == null) {
      return;
    }
    await _run(
      () => api.post('/exceptions/${widget.exceptionId}/cancel',
          body: {'reason': reason}, ifMatch: result.etag, idempotent: true),
      success: 'Đã hủy báo cáo.',
    );
  }

  Future<void> _addNote(ApiClient api) async {
    final note = await askReason(context,
        title: 'Thêm ghi chú', confirmLabel: 'Gửi ghi chú', label: 'Nội dung ghi chú');
    if (note == null) {
      return;
    }
    await _run(
      () => api.post('/exceptions/${widget.exceptionId}/notes', body: {'body': note}),
      success: 'Đã thêm ghi chú.',
    );
  }

  Future<void> _resume(ApiClient api, Map<String, dynamic> data) async {
    final deliveryPath = '/deliveries/${data['deliveryId']}';
    await _run(() async {
      final delivery = await api.get(deliveryPath);
      return api.post('$deliveryPath/stops/${data['stopId']}/actions',
          body: {'action': 'RESUME_AFTER_EXCEPTION'}, ifMatch: delivery.etag);
    }, success: 'Đã tiếp tục điểm dừng.');
  }

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    return LoadView<ApiResult>(
      key: ValueKey(_reloadKey),
      loader: () => api.get('/exceptions/${widget.exceptionId}'),
      builder: (context, result, reload) {
        final data = result.map['data'] as Map<String, dynamic>;
        final capabilities = (result.map['capabilities'] as List).cast<String>();
        final type = data['type'] as Map<String, dynamic>;
        final notes = (data['notes'] as List).cast<Map<String, dynamic>>();
        final assignee = data['assignee'] as Map<String, dynamic>?;
        final manager = capabilities.contains('canInvestigateException') ||
            capabilities.contains('canResolveException') ||
            capabilities.contains('canCancelException');
        return WindowFrame(
          title: manager ? 'Xử lý sự cố ${data['code']}' : 'Sự cố ${data['code']} (chế độ xem)',
          body: PageBody(
            onRefresh: reload,
            maxWidth: 820,
            children: [
              FormGroup(
                title: 'Thông tin sự cố',
                trailing: StatusChip(data['status'] as String),
                children: [
                  InfoRow('Loại', typeLabel(type['code'] as String, type['name'] as String)),
                  InfoRow('Mức độ', severityText[data['severity']] ?? '${data['severity']}'),
                  InfoRow('Ảnh hưởng', impactText[data['impact']] ?? '${data['impact']}'),
                  InfoRow('Chuyến', '${data['deliveryCode']}'),
                  if (data['stopStatus'] != null)
                    InfoRow('Điểm giao', data['stopStatus'] == 'BLOCKED' ? 'Đang bị chặn' : '${data['stopStatus']}'),
                  InfoRow('Người báo cáo', '${(data['reporter'] as Map<String, dynamic>)['name']}'),
                  InfoRow('Thời gian', formatTime(data['reportedAt'] as String?)),
                  if (assignee != null) InfoRow('Người phụ trách', '${assignee['name']}'),
                  InfoRow('Mô tả', data['description'] as String?),
                  if (data['resolutionOutcome'] != null) ...[
                    InfoRow('Kết quả xử lý', outcomeText[data['resolutionOutcome']] ?? '${data['resolutionOutcome']}'),
                    InfoRow('Ghi chú giải quyết', '${data['resolutionNote']}'),
                  ],
                  if (data['cancellationReason'] != null) InfoRow('Lý do hủy', '${data['cancellationReason']}'),
                ],
              ),
              FormGroup(
                title: 'Lịch sử / ghi chú',
                children: [
                  if (notes.isEmpty) const Text('Chưa có ghi chú.'),
                  for (final note in notes)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(note['body'] as String),
                          Text(
                            '${(note['author'] as Map<String, dynamic>)['name']} • ${_noteType(note['type'] as String)} • ${formatTime(note['createdAt'] as String?)}',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                ],
              ),
              if (!manager && capabilities.contains('canAddContext'))
                const Callout('Điều phối viên chỉ xem và bổ sung thông tin; điều tra, giải quyết, hủy do Quản lý vận hành.'),
            ],
          ),
          actions: ActionBar(
            footer: 'OPEN → INVESTIGATING → RESOLVED • OPEN → CANCELLED',
            children: [
              if (capabilities.contains('canInvestigateException'))
                FilledButton.icon(
                  icon: const Icon(Icons.search),
                  onPressed: () => _investigate(api, result),
                  label: const Text('Điều tra'),
                ),
              if (capabilities.contains('canResolveException'))
                FilledButton.icon(
                  icon: const Icon(Icons.check_circle_outline),
                  onPressed: () => _resolve(api, result),
                  label: const Text('Giải quyết'),
                ),
              if (capabilities.contains('canCancelException'))
                OutlinedButton.icon(
                  icon: const Icon(Icons.cancel_outlined),
                  onPressed: () => _cancel(api, result),
                  label: const Text('Hủy sự cố'),
                ),
              if (capabilities.contains('canAddContext'))
                OutlinedButton.icon(
                  icon: const Icon(Icons.note_add_outlined),
                  onPressed: () => _addNote(api),
                  label: const Text('Bổ sung thông tin'),
                ),
              if (capabilities.contains('canResumeStop'))
                OutlinedButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  onPressed: () => _resume(api, data),
                  label: const Text('Tiếp tục điểm giao'),
                ),
              TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Đóng')),
            ],
          ),
        );
      },
    );
  }

  static String _noteType(String type) => switch (type) {
        'INVESTIGATION' => 'Điều tra',
        'RESOLUTION' => 'Giải quyết',
        _ => 'Ngữ cảnh',
      };
}

class _ResolveDialog extends StatefulWidget {
  const _ResolveDialog();

  @override
  State<_ResolveDialog> createState() => _ResolveDialogState();
}

class _ResolveDialogState extends State<_ResolveDialog> {
  final _note = TextEditingController();
  String? _outcome;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final valid = _outcome != null && _note.text.trim().isNotEmpty;
    return FormDialog(
      title: 'Giải quyết sự cố',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: valid ? () => Navigator.of(context).pop((outcome: _outcome!, note: _note.text.trim())) : null,
          child: const Text('Giải quyết'),
        ),
      ],
      children: [
        DropdownButtonFormField<String>(
          initialValue: _outcome,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Kết quả xử lý *'),
          items: [
            for (final entry in outcomeText.entries) DropdownMenuItem(value: entry.key, child: Text(entry.value)),
          ],
          onChanged: (value) => setState(() => _outcome = value),
        ),
        const FieldGap(),
        TextField(
          controller: _note,
          maxLines: 3,
          decoration: const InputDecoration(labelText: 'Ghi chú / lý do *'),
          onChanged: (_) => setState(() {}),
        ),
      ],
    );
  }
}
