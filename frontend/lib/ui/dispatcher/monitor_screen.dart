import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import '../exceptions/exceptions_screen.dart';
import 'board_screen.dart';

const _filters = <String, String>{
  'ACTIVE': 'Đang chạy',
  'COMPLETED': 'Hoàn tất',
  'CANCELLED': 'Đã hủy',
};

class MonitorScreen extends StatefulWidget {
  const MonitorScreen({super.key});

  @override
  State<MonitorScreen> createState() => _MonitorScreenState();
}

class _MonitorScreenState extends State<MonitorScreen> with AutomaticKeepAliveClientMixin {
  final _search = TextEditingController();
  Timer? _timer;
  String _status = 'ACTIVE';
  final List<Map<String, dynamic>> _items = [];
  String? _cursor;
  bool _loading = false;
  bool _loaded = false;
  String? _error;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
    _timer = Timer.periodic(const Duration(seconds: 15), (_) {
      if (_cursor == null) {
        _load(reset: true, silent: true);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _search.dispose();
    super.dispose();
  }

  Future<void> _load({bool reset = false, bool silent = false}) async {
    if (_loading) {
      return;
    }
    final api = context.read<AuthState>().api;
    if (!silent) {
      setState(() {
        _loading = true;
        _error = null;
        if (reset) {
          _items.clear();
          _cursor = null;
        }
      });
    }
    try {
      final query = <String, String>{
        'limit': '20',
        'status': _status,
        if (_search.text.trim().isNotEmpty) 'q': _search.text.trim(),
        if (!reset) 'cursor': ?_cursor,
      };
      final page = (await api.get('/dispatch/deliveries?${Uri(queryParameters: query).query}')).map;
      if (!mounted) {
        return;
      }
      setState(() {
        if (reset) {
          _items.clear();
        }
        _items.addAll((page['items'] as List).cast<Map<String, dynamic>>());
        _cursor = page['nextCursor'] as String?;
        _loaded = true;
        _error = null;
      });
    } catch (e) {
      if (mounted && !silent) {
        setState(() => _error = describeError(e));
      }
    } finally {
      if (mounted && !silent) {
        setState(() => _loading = false);
      }
    }
  }

  String? _selected;
  String _impact = 'ALL';

  void _clear() {
    _search.clear();
    setState(() {
      _status = 'ACTIVE';
      _impact = 'ALL';
    });
    _load(reset: true);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final rows = _impact == 'ALL'
        ? _items
        : _items.where((i) => (i['openExceptionCount'] as int) > 0).toList();
    final selected = rows.where((i) => i['id'] == _selected).firstOrNull;
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
                    options: _filters,
                    onChanged: (v) => setState(() => _status = v),
                  ),
                  ChoiceField<String>(
                    label: 'Có sự cố',
                    value: _impact,
                    options: const {'ALL': 'Tất cả', 'YES': 'Có sự cố'},
                    onChanged: (v) => setState(() => _impact = v),
                  ),
                  TextField(
                    controller: _search,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => _load(reset: true),
                    decoration: const InputDecoration(labelText: 'Mã chuyến, lô hoặc tài xế'),
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
                      emptyTitle: 'Không có chuyến nào',
                      emptyMessage: 'Thử đổi bộ lọc trạng thái.',
                      rows: rows,
                      isSelected: (r) => r['id'] == _selected,
                      onTap: (r) => setState(() => _selected = r['id'] as String),
                      columns: [
                        GridColumn(title: 'Mã chuyến', primary: true, flex: 1.1, cell: (c, r) => Text('${r['code']}')),
                        GridColumn(title: 'Tài xế', flex: 1.5, cell: (c, r) => Text('${r['driverName']}')),
                        GridColumn(title: 'Lô / Số đơn', flex: 1.2, cell: (c, r) => Text('${r['batchCode']} / ${r['orderCount']}')),
                        GridColumn(
                          title: 'Tiến độ',
                          flex: 1,
                          cell: (c, r) {
                            final done = r['completedStops'] as int;
                            final total = r['totalStops'] as int;
                            return Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('$done / $total'),
                                const SizedBox(height: 4),
                                LinearProgressIndicator(value: total == 0 ? 0 : done / total, minHeight: 4),
                              ],
                            );
                          },
                        ),
                        GridColumn(title: 'Trạng thái', flex: 1.3, cell: (c, r) => StatusChip(r['status'] as String)),
                        GridColumn(title: 'Cập nhật cuối', flex: 1, cell: (c, r) => Text(agoText(r['lastUpdateAt'] as String?))),
                        GridColumn(
                          title: 'Sự cố',
                          flex: 0.8,
                          cell: (c, r) => (r['openExceptionCount'] as int) > 0
                              ? StatusBadge(label: '${r['openExceptionCount']}', tone: Tone.danger, icon: Icons.report_problem_outlined)
                              : const Text('—'),
                        ),
                        GridColumn(title: 'Ảnh hưởng KH', flex: 0.9, cell: (c, r) => Text(r['customerImpacting'] == true ? 'Có' : '—')),
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
          footer: '${rows.length} chuyến • Chưa có bản đồ thời gian thực, dữ liệu tự làm mới mỗi 15 giây.',
          children: [
            FilledButton(
              onPressed: selected == null || (selected['openExceptionCount'] as int) == 0
                  ? null
                  : () => Navigator.of(context).push(MaterialPageRoute<void>(
                        builder: (_) => DeliveryExceptionsPage(deliveryId: selected['id'] as String),
                      )),
              child: const Text('Xem sự cố của chuyến'),
            ),
            OutlinedButton.icon(
              onPressed: _loading ? null : () => _load(reset: true),
              icon: const Icon(Icons.refresh),
              label: const Text('Làm mới'),
            ),
          ],
        ),
      ],
    );
  }
}
