import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import 'delivery_screen.dart';

const _resultFilter = <String, String>{
  'ALL': 'Tất cả',
  'COMPLETED': 'Hoàn tất',
  'CANCELLED': 'Đã hủy',
  'IN_PROGRESS': 'Đang giao',
};

class HistoryScreen extends StatefulWidget {
  const HistoryScreen({super.key});

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  DateTime? _from;
  DateTime? _to;
  String _status = 'ALL';
  final List<Map<String, dynamic>> _items = [];
  String? _cursor;
  bool _loading = false;
  bool _loaded = false;
  String? _error;
  String? _selected;

  @override
  void initState() {
    super.initState();
    _from = DateTime.now().subtract(const Duration(days: 30));
    _load(reset: true);
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
      final query = <String, dynamic>{
        'limit': '20',
        if (_status != 'ALL') 'status': _status,
        if (_from != null) 'from': DateTime(_from!.year, _from!.month, _from!.day).toUtc().toIso8601String(),
        if (_to != null) 'to': DateTime(_to!.year, _to!.month, _to!.day).add(const Duration(days: 1)).toUtc().toIso8601String(),
        if (!reset && _cursor != null) 'cursor': _cursor,
      };
      final page = (await api.get('/me/deliveries?${Uri(queryParameters: query).query}')).map;
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
    setState(() {
      _from = null;
      _to = null;
      _status = 'ALL';
    });
    _load(reset: true);
  }

  Future<void> _open(Map<String, dynamic> row) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => DeliveryScreen(deliveryId: row['id'] as String),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final selected = _items.where((row) => row['id'] == _selected).firstOrNull;
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
                  DateField(label: 'Từ ngày', value: _from, onChanged: (v) => setState(() => _from = v)),
                  DateField(label: 'Đến ngày', value: _to, onChanged: (v) => setState(() => _to = v)),
                  ChoiceField<String>(
                    label: 'Trạng thái',
                    value: _status,
                    options: _resultFilter,
                    onChanged: (v) => setState(() => _status = v),
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
                      emptyTitle: 'Chưa có chuyến nào trong khoảng này',
                      emptyMessage: 'Thử mở rộng khoảng ngày hoặc xóa bộ lọc.',
                      rows: _items,
                      isSelected: (row) => row['id'] == _selected,
                      onTap: (row) => setState(() => _selected = row['id'] as String),
                      columns: [
                        GridColumn(title: 'Mã chuyến', flex: 1.2, primary: true, cell: (c, r) => Text('${r['code']}')),
                        GridColumn(title: 'Ngày', cell: (c, r) => Text(formatTime(r['createdAt'] as String?))),
                        GridColumn(title: 'Lô', cell: (c, r) => Text('${r['batchCode']}')),
                        GridColumn(title: 'Số điểm', flex: 0.7, cell: (c, r) => Text('${r['completedStops']}/${r['stopCount']}')),
                        GridColumn(title: 'Kết quả', flex: 1.3, cell: (c, r) => StatusChip(r['status'] as String)),
                        GridColumn(title: 'Sự cố', cell: (c, r) => Text('${r['exceptionCodes'] ?? '—'}')),
                        GridColumn(title: 'Ghi chú', flex: 1.4, cell: (c, r) => Text('${r['note'] ?? ''}')),
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
          footer: 'Chỉ hiển thị chuyến của tài xế đang đăng nhập. ${_items.length} chuyến đã tải.',
          children: [
            FilledButton.icon(
              onPressed: selected == null ? null : () => _open(selected),
              icon: const Icon(Icons.open_in_new),
              label: const Text('Xem chi tiết'),
            ),
          ],
        ),
      ],
    );
  }
}
