import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../dialogs.dart';
import '../kit/kit.dart';
import '../kit/map_view.dart';
import '../pod_viewer.dart';
import 'create_order_screen.dart';

class OrderDetailScreen extends StatefulWidget {
  const OrderDetailScreen({super.key, required this.orderId});

  final String orderId;

  @override
  State<OrderDetailScreen> createState() => _OrderDetailScreenState();
}

class _OrderDetailScreenState extends State<OrderDetailScreen> {
  int _reloadKey = 0;

  Future<void> _cancel(ApiResult result) async {
    final api = context.read<AuthState>().api;
    final order = result.map;
    final needsReason = order['status'] != 'DRAFT';
    final reason = await askReason(
      context,
      title: 'Hủy đơn hàng',
      confirmLabel: 'Hủy đơn',
      label: needsReason ? 'Lý do hủy' : 'Lý do hủy (tùy chọn)',
      optional: !needsReason,
      summaryTitle: 'Đơn hàng',
      summary: [('Mã đơn', '${order['code']}')],
    );
    if (reason == null || !mounted) {
      return;
    }
    final done = await guarded(
      context,
      () => api.post('/orders/${widget.orderId}/cancel', body: {'reason': reason}, ifMatch: result.etag, idempotent: true),
      success: 'Đã hủy đơn hàng.',
    );
    if (done != null && mounted) {
      setState(() => _reloadKey++);
    }
  }

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    return LoadView<ApiResult>(
      key: ValueKey(_reloadKey),
      loader: () => api.get('/orders/${widget.orderId}'),
      builder: (context, result, reload) {
        final order = result.map;
        final pickup = order['pickup'] as Map<String, dynamic>;
        final dropoff = order['dropoff'] as Map<String, dynamic>;
        final recipient = order['recipient'] as Map<String, dynamic>;
        final pkg = order['package'] as Map<String, dynamic>;
        final window = order['timeWindow'] as Map<String, dynamic>;
        final capabilities = (order['capabilities'] as List).cast<String>();
        final status = order['status'] as String;
        final dims = [pkg['lengthCm'], pkg['widthCm'], pkg['heightCm']];
        return WindowFrame(
          title: 'Đơn hàng ${order['code']}',
          body: PageBody(
            onRefresh: reload,
            maxWidth: 920,
            children: [
              FormGroup(
                title: 'Thông tin đơn',
                trailing: StatusChip(status),
                children: [
                  InfoRow('Điểm lấy (A)', pickup['address'] as String?),
                  InfoRow('Điểm giao (B)', dropoff['address'] as String?),
                  InfoRow('Người nhận', '${recipient['name']}${recipient['contact'] == null ? '' : ' — ${recipient['contact']}'}'),
                  InfoRow(
                    'Hàng hóa',
                    '${(pkg['weightG'] as num) / 1000} kg${dims.every((d) => d != null) ? ' — ${dims.join('×')} cm' : ''}',
                  ),
                  InfoRow('Khung giờ', '${formatTime(window['start'] as String?)} – ${formatTime(window['end'] as String?)}'),
                  if (order['cancelReason'] != null) InfoRow('Lý do hủy', '${order['cancelReason']}'),
                ],
              ),
              if (latLngOf(pickup['point']) != null || latLngOf(dropoff['point']) != null)
                FormGroup(
                  title: 'Bản đồ',
                  children: [
                    AppMap(
                      height: 240,
                      pins: [
                        if (latLngOf(pickup['point']) != null) MapPin(latLngOf(pickup['point'])!, 'A', tone: Tone.info),
                        if (latLngOf(dropoff['point']) != null) MapPin(latLngOf(dropoff['point'])!, 'B', tone: Tone.ok),
                      ],
                      straightLine: [
                        if (latLngOf(pickup['point']) != null) latLngOf(pickup['point'])!,
                        if (latLngOf(dropoff['point']) != null) latLngOf(dropoff['point'])!,
                      ],
                      caption: 'A: điểm lấy, B: điểm giao. Không hiển thị vị trí trực tiếp của tài xế.',
                    ),
                  ],
                ),
              _Timeline(orderId: widget.orderId, version: order['version'] as int),
              if (status == 'DELIVERED') PodViewer(orderId: widget.orderId),
            ],
          ),
          actions: ActionBar(
            footer: 'Không hiển thị lộ trình chi tiết của tài xế. Sửa và Hủy chỉ bật khi trạng thái cho phép.',
            children: [
              if (status == 'DRAFT')
                FilledButton.icon(
                  icon: const Icon(Icons.send_outlined),
                  onPressed: () async {
                    final done = await guarded(
                      context,
                      () => api.post('/orders/${widget.orderId}/submit', ifMatch: result.etag, idempotent: true),
                      success: 'Đã gửi đơn để điều phối.',
                    );
                    if (done != null && mounted) {
                      setState(() => _reloadKey++);
                    }
                  },
                  label: const Text('Gửi điều phối'),
                ),
              OutlinedButton.icon(
                icon: const Icon(Icons.edit_outlined),
                onPressed: capabilities.contains('canEditOrder')
                    ? () async {
                        final saved = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
                          builder: (_) => CreateOrderScreen(existing: order, etag: result.etag),
                        ));
                        if (saved == true && mounted) {
                          setState(() => _reloadKey++);
                        }
                      }
                    : null,
                label: const Text('Sửa đơn'),
              ),
              OutlinedButton.icon(
                icon: const Icon(Icons.cancel_outlined),
                onPressed: capabilities.contains('canCancelOrder') ? () => _cancel(result) : null,
                label: const Text('Hủy đơn'),
              ),
              TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Đóng')),
            ],
          ),
        );
      },
    );
  }
}

const _milestoneIcons = <String, IconData>{
  'ORDER_RECEIVED': Icons.receipt_long_outlined,
  'BATCHED': Icons.inventory_2_outlined,
  'DRIVER_ASSIGNED': Icons.person_outline,
  'REASSIGNING': Icons.swap_horiz,
  'OUT_FOR_DELIVERY': Icons.local_shipping_outlined,
  'DELIVERY_ISSUE': Icons.report_problem_outlined,
  'DELIVERED': Icons.check_circle_outline,
  'ORDER_CANCELLED': Icons.cancel_outlined,
};

class _Timeline extends StatelessWidget {
  const _Timeline({required this.orderId, required this.version});

  final String orderId;
  final int version;

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    final tokens = AppTokens.of(context);
    return FormGroup(
      title: 'Hành trình đơn hàng',
      children: [
        LoadViewInline(
          key: ValueKey('timeline-$version'),
          loader: () async => (await api.get('/orders/$orderId/tracking')).map['items'] as List<dynamic>,
          builder: (context, items) {
            if (items.isEmpty) {
              return const Text('Chưa có mốc nào.');
            }
            return Column(
              children: [
                for (var i = 0; i < items.length; i++)
                  Builder(builder: (context) {
                    final event = items[i] as Map<String, dynamic>;
                    final last = i == items.length - 1;
                    return IntrinsicHeight(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          SizedBox(
                            width: 32,
                            child: Column(
                              children: [
                                Icon(_milestoneIcons[event['milestone']] ?? Icons.circle_outlined, size: 20, color: tokens.groupLabel),
                                if (!last) Expanded(child: Container(width: 2, color: tokens.line)),
                              ],
                            ),
                          ),
                          Expanded(
                            child: Padding(
                              padding: const EdgeInsets.only(bottom: AppTokens.s3),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text('${event['note'] ?? event['milestone']}'),
                                  Text(formatTime(event['occurredAt'] as String?), style: Theme.of(context).textTheme.bodySmall),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    );
                  }),
              ],
            );
          },
        ),
      ],
    );
  }
}

class LoadViewInline extends StatefulWidget {
  const LoadViewInline({super.key, required this.loader, required this.builder});

  final Future<List<dynamic>> Function() loader;
  final Widget Function(BuildContext context, List<dynamic> items) builder;

  @override
  State<LoadViewInline> createState() => _LoadViewInlineState();
}

class _LoadViewInlineState extends State<LoadViewInline> {
  late final Future<List<dynamic>> _future = widget.loader();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<dynamic>>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Padding(padding: EdgeInsets.all(AppTokens.s3), child: Center(child: CircularProgressIndicator()));
        }
        if (snapshot.hasError) {
          return Text(describeError(snapshot.error!), style: Theme.of(context).textTheme.bodySmall);
        }
        return widget.builder(context, snapshot.data!);
      },
    );
  }
}
