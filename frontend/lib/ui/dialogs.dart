import 'package:flutter/material.dart';

import '../theme/tokens.dart';
import 'kit/kit.dart';

const rejectionReasonText = <String, String>{
  'VEHICLE_PROBLEM': 'Xe gặp sự cố',
  'SCHEDULE_CONFLICT': 'Trùng lịch',
  'TOO_FAR_FROM_PICKUP': 'Quá xa điểm lấy hàng',
  'CAPACITY_CONCERN': 'Không đủ tải trọng',
  'PERSONAL_EMERGENCY': 'Việc cá nhân khẩn cấp',
  'OTHER': 'Khác',
};

typedef SummaryRows = List<(String, String)>;

class FormDialog extends StatelessWidget {
  const FormDialog({
    super.key,
    required this.title,
    required this.children,
    required this.actions,
    this.footer,
  });

  final String title;
  final List<Widget> children;
  final List<Widget> actions;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Dialog(
      insetPadding: const EdgeInsets.all(AppTokens.s4),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(AppTokens.s4, AppTokens.s4, AppTokens.s4, AppTokens.s3),
              child: Text(title, style: Theme.of(context).textTheme.titleLarge),
            ),
            Divider(color: tokens.line),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(AppTokens.s4),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
              ),
            ),
            Divider(color: tokens.line),
            Padding(
              padding: const EdgeInsets.all(AppTokens.s3),
              child: Align(
                alignment: Alignment.centerRight,
                child: Wrap(spacing: AppTokens.s2, runSpacing: AppTokens.s2, children: actions),
              ),
            ),
            if (footer != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(AppTokens.s4, 0, AppTokens.s4, AppTokens.s3),
                child: Text(footer!, style: Theme.of(context).textTheme.bodySmall),
              ),
          ],
        ),
      ),
    );
  }
}

class SummaryBlock extends StatelessWidget {
  const SummaryBlock(this.title, this.rows, {super.key});

  final String title;
  final SummaryRows rows;

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: AppTokens.s3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title.toUpperCase(),
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: AppTokens.of(context).groupLabel,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.8,
                ),
          ),
          const SizedBox(height: AppTokens.s1),
          for (final row in rows) InfoRow(row.$1, row.$2),
        ],
      ),
    );
  }
}

Future<String?> askReason(
  BuildContext context, {
  required String title,
  required String confirmLabel,
  String label = 'Lý do',
  bool optional = false,
  String? summaryTitle,
  SummaryRows summary = const [],
  String? callout,
  Tone calloutTone = Tone.warn,
  String? footer,
}) {
  return showDialog<String>(
    context: context,
    builder: (_) => _ReasonDialog(
      title: title,
      confirmLabel: confirmLabel,
      label: label,
      optional: optional,
      summaryTitle: summaryTitle,
      summary: summary,
      callout: callout,
      calloutTone: calloutTone,
      footer: footer,
    ),
  );
}

class _ReasonDialog extends StatefulWidget {
  const _ReasonDialog({
    required this.title,
    required this.confirmLabel,
    required this.label,
    required this.optional,
    required this.summaryTitle,
    required this.summary,
    required this.callout,
    required this.calloutTone,
    required this.footer,
  });

  final String title;
  final String confirmLabel;
  final String label;
  final bool optional;
  final String? summaryTitle;
  final SummaryRows summary;
  final String? callout;
  final Tone calloutTone;
  final String? footer;

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final valid = widget.optional || _controller.text.trim().isNotEmpty;
    return FormDialog(
      title: widget.title,
      footer: widget.footer,
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: valid ? () => Navigator.of(context).pop(_controller.text.trim()) : null,
          child: Text(widget.confirmLabel),
        ),
      ],
      children: [
        if (widget.summary.isNotEmpty) SummaryBlock(widget.summaryTitle ?? 'Thông tin', widget.summary),
        if (widget.callout != null) Callout(widget.callout!, tone: widget.calloutTone),
        TextField(
          controller: _controller,
          autofocus: true,
          maxLines: 3,
          decoration: InputDecoration(labelText: widget.optional ? widget.label : '${widget.label} *'),
          onChanged: (_) => setState(() {}),
        ),
      ],
    );
  }
}

typedef RejectionChoice = ({String code, String? description});

Future<RejectionChoice?> askRejection(
  BuildContext context,
  List<Map<String, dynamic>> reasons, {
  SummaryRows summary = const [],
}) {
  return showDialog<RejectionChoice>(
    context: context,
    builder: (_) => _RejectionDialog(reasons: reasons, summary: summary),
  );
}

class _RejectionDialog extends StatefulWidget {
  const _RejectionDialog({required this.reasons, required this.summary});

  final List<Map<String, dynamic>> reasons;
  final SummaryRows summary;

  @override
  State<_RejectionDialog> createState() => _RejectionDialogState();
}

class _RejectionDialogState extends State<_RejectionDialog> {
  final _description = TextEditingController();
  String? _code;

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  bool get _needsDescription {
    final chosen = widget.reasons.where((r) => r['code'] == _code);
    return chosen.isNotEmpty && chosen.first['requiresDescription'] == true;
  }

  bool get _valid => _code != null && (!_needsDescription || _description.text.trim().isNotEmpty);

  @override
  Widget build(BuildContext context) {
    return FormDialog(
      title: 'Từ chối chuyến giao',
      footer: 'Chuyến sẽ chuyển sang trạng thái chờ gán lại.',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: _valid
              ? () => Navigator.of(context).pop((
                    code: _code!,
                    description: _description.text.trim().isEmpty ? null : _description.text.trim(),
                  ))
              : null,
          child: const Text('Từ chối'),
        ),
      ],
      children: [
        SummaryBlock('Thông tin chuyến giao', widget.summary),
        Text('Lý do từ chối *', style: Theme.of(context).textTheme.titleSmall),
        RadioGroup<String>(
          groupValue: _code,
          onChanged: (value) => setState(() => _code = value),
          child: Column(
            children: [
              for (final r in widget.reasons)
                RadioListTile<String>(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  value: r['code'] as String,
                  title: Text(rejectionReasonText[r['code']] ?? r['code'] as String),
                ),
            ],
          ),
        ),
        const FieldGap(),
        TextField(
          controller: _description,
          maxLines: 3,
          decoration: InputDecoration(labelText: _needsDescription ? 'Mô tả (bắt buộc khi chọn Khác) *' : 'Mô tả (tùy chọn)'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        const Callout('Chuyến sẽ chuyển sang trạng thái chờ gán lại.'),
      ],
    );
  }
}

typedef ReassignChoice = ({String driverId, String reason});

Future<ReassignChoice?> askReassign(
  BuildContext context,
  List<Map<String, dynamic>> candidates, {
  SummaryRows summary = const [],
}) {
  return showDialog<ReassignChoice>(
    context: context,
    builder: (_) => _ReassignDialog(candidates: candidates, summary: summary),
  );
}

class _ReassignDialog extends StatefulWidget {
  const _ReassignDialog({required this.candidates, required this.summary});

  final List<Map<String, dynamic>> candidates;
  final SummaryRows summary;

  @override
  State<_ReassignDialog> createState() => _ReassignDialogState();
}

class _ReassignDialogState extends State<_ReassignDialog> {
  final _reason = TextEditingController();
  String? _driverId;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final valid = _driverId != null && _reason.text.trim().isNotEmpty;
    return FormDialog(
      title: 'Phân công lại tài xế',
      footer: 'Không phân công lại được khi chuyến đã bắt đầu giao.',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: valid ? () => Navigator.of(context).pop((driverId: _driverId!, reason: _reason.text.trim())) : null,
          child: const Text('Phân công lại'),
        ),
      ],
      children: [
        SummaryBlock('Phân công hiện tại', widget.summary),
        Text('Chọn tài xế mới *', style: Theme.of(context).textTheme.titleSmall),
        if (widget.candidates.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppTokens.s3),
            child: Text('Không còn ứng viên nào trong đề xuất hiện tại. Hãy tạo lại đề xuất.'),
          )
        else
          RadioGroup<String>(
            groupValue: _driverId,
            onChanged: (value) => setState(() => _driverId = value),
            child: Column(
              children: [
                for (final c in widget.candidates)
                  RadioListTile<String>(
                    contentPadding: EdgeInsets.zero,
                    value: c['driverId'] as String,
                    title: Text('${c['driverName']}'),
                    subtitle: Text('${(c['vehicle'] as Map?)?['type'] ?? ''} • ${c['totalScore']} điểm'),
                  ),
              ],
            ),
          ),
        const FieldGap(),
        TextField(
          controller: _reason,
          maxLines: 3,
          decoration: const InputDecoration(labelText: 'Lý do phân công lại *'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        const Callout('Tài xế cũ và tài xế mới sẽ được thông báo. Lịch sử phân công được giữ lại.'),
      ],
    );
  }
}
