import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../dialogs.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import 'create_order_screen.dart';
import 'order_detail_screen.dart';

const _statusOptions = <String, String>{
  'ALL': 'Tất cả',
  'DRAFT': 'Nháp',
  'READY_FOR_DISPATCH': 'Chờ điều phối',
  'BATCHED': 'Đã gom batch',
  'ASSIGNED': 'Đã phân công',
  'IN_DELIVERY': 'Đang giao',
  'DELIVERED': 'Đã giao',
  'CANCELLED': 'Đã hủy',
};

class OrdersScreen extends StatefulWidget {
  const OrdersScreen({super.key});

  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends State<OrdersScreen> {
  final _code = TextEditingController();
  String _status = 'ALL';
  DateTime? _from;
  DateTime? _to;
  final List<Map<String, dynamic>> _items = [];
  String? _cursor;
  bool _loading = false;
  bool _loaded = false;
  String? _error;
  String? _selected;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _load({bool reset = false}) async {
    if (_loading) {
      return;
    }
    final api = context.read<AuthState>().api;
    setState(() {
      _loading = true;
      _error = null;
      if (reset) {
        _items.clear();
        _cursor = null;
      }
    });
    try {
      final query = <String, String>{
        'limit': '20',
        if (_status != 'ALL') 'status': _status,
        if (_code.text.trim().isNotEmpty) 'q': _code.text.trim(),
        if (_from != null) 'windowFrom': DateTime(_from!.year, _from!.month, _from!.day).toUtc().toIso8601String(),
        if (_to != null) 'windowTo': DateTime(_to!.year, _to!.month, _to!.day).add(const Duration(days: 1)).toUtc().toIso8601String(),
        if (!reset && _cursor != null) 'cursor': _cursor!,
      };
      final page = (await api.get('/orders?${Uri(queryParameters: query).query}')).map;
      if (!mounted) {
        return;
      }
      setState(() {
        _items.addAll((page['items'] as List).cast<Map<String, dynamic>>());
        _cursor = page['nextCursor'] as String?;
        _loaded = true;
      });
    } catch (e) {
      if (mounted) {
        setState(() => _error = describeError(e));
      }
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  void _clear() {
    _code.clear();
    setState(() {
      _status = 'ALL';
      _from = null;
      _to = null;
    });
    _load(reset: true);
  }

  Future<void> _create() async {
    final created = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(builder: (_) => const CreateOrderScreen()),
    );
    if (created == true && mounted) {
      await _load(reset: true);
    }
  }

  Future<void> _open(Map<String, dynamic> order) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => OrderDetailScreen(orderId: order['id'] as String),
    ));
    if (mounted) {
      await _load(reset: true);
    }
  }

  Future<void> _cancel(Map<String, dynamic> order) async {
    final api = context.read<AuthState>().api;
    final needsReason = order['status'] != 'DRAFT';
    final reason = await askReason(
      context,
      title: 'Hủy đơn hàng',
      confirmLabel: 'Hủy đơn',
      label: needsReason ? 'Lý do hủy' : 'Lý do hủy (tùy chọn)',
      optional: !needsReason,
      summary: [('Mã đơn', '${order['code']}')],
      summaryTitle: 'Đơn hàng',
    );
    if (reason == null || !mounted) {
      return;
    }
    final done = await guarded(
      context,
      () => api.post('/orders/${order['id']}/cancel',
          body: {'reason': reason}, ifMatch: '"${order['version']}"', idempotent: true),
      success: 'Đã hủy đơn hàng.',
    );
    if (done != null && mounted) {
      await _load(reset: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final selected = _items.where((o) => o['id'] == _selected).firstOrNull;
    final canCancel = selected != null && (selected['capabilities'] as List?)?.contains('canCancelOrder') == true;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: () => _load(reset: true),
            children: [
              FilterPanel(
                onSearch: () => _load(reset: true),
                onClear: _clear,
                children: [
                  ChoiceField<String>(
                    label: 'Trạng thái',
                    value: _status,
                    options: _statusOptions,
                    onChanged: (v) => setState(() => _status = v),
                  ),
                  DateField(label: 'Từ ngày', value: _from, onChanged: (v) => setState(() => _from = v)),
                  DateField(label: 'Đến ngày', value: _to, onChanged: (v) => setState(() => _to = v)),
                  TextField(
                    controller: _code,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => _load(reset: true),
                    decoration: const InputDecoration(labelText: 'Mã đơn'),
                  ),
                ],
              ),
              FormGroup(
                title: 'Kết quả',
                children: [
                  if (_error != null) Callout(_error!, tone: Tone.danger),
                  if (!_loaded && _loading)
                    const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
                  else
                    ResultGrid<Map<String, dynamic>>(
                      emptyTitle: 'Chưa có đơn hàng nào',
                      emptyMessage: 'Bấm "Tạo đơn mới" để bắt đầu, hoặc xóa bộ lọc.',
                      rows: _items,
                      isSelected: (o) => o['id'] == _selected,
                      onTap: (o) => setState(() => _selected = o['id'] as String),
                      columns: [
                        GridColumn(title: 'Mã đơn', flex: 1.1, primary: true, cell: (c, o) => Text('${o['code']}')),
                        GridColumn(title: 'Ngày giao', cell: (c, o) => Text(formatTime((o['timeWindow'] as Map?)?['start'] as String?).split(' ').first)),
                        GridColumn(
                          title: 'Lấy → Giao',
                          flex: 2.4,
                          cell: (c, o) => Text(
                            '${(o['pickup'] as Map)['address']} → ${(o['dropoff'] as Map)['address']}',
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        GridColumn(
                          title: 'Khung giờ',
                          flex: 1.3,
                          cell: (c, o) {
                            final w = o['timeWindow'] as Map?;
                            return Text(w == null ? '—' : '${formatTime(w['start'] as String?)} – ${formatTime(w['end'] as String?).split(' ').last}');
                          },
                        ),
                        GridColumn(title: 'Trạng thái', flex: 1.3, cell: (c, o) => StatusChip(o['status'] as String)),
                        GridColumn(title: 'POD', flex: 0.6, cell: (c, o) => Text(o['status'] == 'DELIVERED' ? 'Có' : '—')),
                      ],
                    ),
                  if (_cursor != null)
                    Center(
                      child: TextButton(
                        onPressed: _loading ? null : () => _load(),
                        child: Text(_loading ? 'Đang tải...' : 'Tải thêm'),
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
        ActionBar(
          footer: '${_items.length} đơn hàng. Chỉ hiển thị đơn của tài khoản đang đăng nhập.',
          children: [
            FilledButton.icon(onPressed: _create, icon: const Icon(Icons.add), label: const Text('Tạo đơn mới')),
            OutlinedButton(onPressed: selected == null ? null : () => _open(selected), child: const Text('Xem chi tiết')),
            OutlinedButton(onPressed: canCancel ? () => _cancel(selected) : null, child: const Text('Hủy đơn')),
          ],
        ),
      ],
    );
  }
}
