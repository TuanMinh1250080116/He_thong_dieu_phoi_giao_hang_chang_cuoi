import 'package:flutter/material.dart';

import '../api/error_text.dart';
import '../theme/tokens.dart';
import 'kit/kit.dart';
import 'kit/problem_view.dart';

String formatTime(String? iso) {
  if (iso == null) {
    return '';
  }
  final t = DateTime.parse(iso).toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(t.day)}/${two(t.month)} ${two(t.hour)}:${two(t.minute)}';
}

String shortId(String? id) => id == null ? '' : id.substring(0, 8);

const _statusText = <String, String>{
  'DRAFT': 'Nháp',
  'READY_FOR_DISPATCH': 'Chờ điều phối',
  'BATCHED': 'Đã gom batch',
  'ASSIGNED': 'Đã phân công',
  'IN_DELIVERY': 'Đang giao',
  'DELIVERED': 'Đã giao',
  'CANCELLED': 'Đã hủy',
  'LOCKED': 'Đã khóa',
  'COMPLETED': 'Hoàn tất',
  'PENDING': 'Chờ nhận',
  'ACCEPTED': 'Đã nhận',
  'IN_PROGRESS': 'Đang thực hiện',
  'ACTIVE': 'Còn hiệu lực',
  'EXPIRED': 'Đã hết hạn',
  'CONSUMED': 'Đã dùng',
  'INVALIDATED': 'Không còn hiệu lực',
  'REJECTED': 'Đã từ chối',
  'SUPERSEDED': 'Đã thay thế',
  'PENDING_DECISION': 'Chờ quyết định',
  'AWAITING_DRIVER_RESPONSE': 'Chờ tài xế phản hồi',
  'AWAITING_REASSIGNMENT': 'Chờ gán lại',
  'CONFIRMED': 'Đã xác nhận',
  'OPEN': 'Mới báo',
  'INVESTIGATING': 'Đang xử lý',
  'RESOLVED': 'Đã giải quyết',
  'BLOCKED': 'Bị chặn',
  'SCHEDULED': 'Đã hẹn kích hoạt',
  'RETIRED': 'Đã ngưng',
};

const _statusTone = <String, Tone>{
  'DRAFT': Tone.neutral,
  'READY_FOR_DISPATCH': Tone.info,
  'BATCHED': Tone.info,
  'ASSIGNED': Tone.info,
  'IN_DELIVERY': Tone.info,
  'DELIVERED': Tone.ok,
  'CANCELLED': Tone.danger,
  'LOCKED': Tone.info,
  'COMPLETED': Tone.ok,
  'PENDING': Tone.warn,
  'ACCEPTED': Tone.ok,
  'IN_PROGRESS': Tone.info,
  'ACTIVE': Tone.ok,
  'EXPIRED': Tone.danger,
  'CONSUMED': Tone.neutral,
  'INVALIDATED': Tone.neutral,
  'REJECTED': Tone.danger,
  'SUPERSEDED': Tone.neutral,
  'PENDING_DECISION': Tone.warn,
  'AWAITING_DRIVER_RESPONSE': Tone.warn,
  'AWAITING_REASSIGNMENT': Tone.danger,
  'CONFIRMED': Tone.ok,
  'OPEN': Tone.danger,
  'INVESTIGATING': Tone.warn,
  'RESOLVED': Tone.ok,
  'BLOCKED': Tone.danger,
  'SCHEDULED': Tone.info,
  'RETIRED': Tone.neutral,
};

const _toneIcon = <Tone, IconData>{
  Tone.neutral: Icons.circle_outlined,
  Tone.info: Icons.timelapse,
  Tone.ok: Icons.check_circle_outline,
  Tone.warn: Icons.hourglass_top,
  Tone.danger: Icons.error_outline,
};

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});

  final String status;

  @override
  Widget build(BuildContext context) {
    final tone = _statusTone[status] ?? Tone.neutral;
    return StatusBadge(label: _statusText[status] ?? status, tone: tone, icon: _toneIcon[tone]);
  }
}

Future<T?> guarded<T>(BuildContext context, Future<T> Function() action, {String? success}) async {
  final messenger = ScaffoldMessenger.of(context);
  final errorColor = Theme.of(context).colorScheme.error;
  try {
    final result = await action();
    if (success != null) {
      messenger.showSnackBar(SnackBar(content: Text(success)));
    }
    return result;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(describeError(e)), backgroundColor: errorColor));
    return null;
  }
}

class LoadView<T> extends StatefulWidget {
  const LoadView({super.key, required this.loader, required this.builder});

  final Future<T> Function() loader;
  final Widget Function(BuildContext context, T data, Future<void> Function() reload) builder;

  @override
  State<LoadView<T>> createState() => _LoadViewState<T>();
}

class _LoadViewState<T> extends State<LoadView<T>> {
  late Future<T> _future = widget.loader();

  Future<void> _reload() async {
    final next = widget.loader();
    setState(() => _future = next);
    try {
      await next;
    } catch (_) {
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return ProblemView(error: snapshot.error!, onRetry: _reload);
        }
        return widget.builder(context, snapshot.data as T, _reload);
      },
    );
  }
}

class Labeled extends StatelessWidget {
  const Labeled(this.label, this.value, {super.key});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => InfoRow(label, value);
}
