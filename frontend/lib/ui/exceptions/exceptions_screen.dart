import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import 'exception_detail_screen.dart';
import 'exception_text.dart';

const _filters = <String?, String>{
  null: 'Tất cả',
  'OPEN': 'Mới báo',
  'INVESTIGATING': 'Đang xử lý',
  'RESOLVED': 'Đã giải quyết',
  'CANCELLED': 'Đã hủy',
};

class ExceptionsScreen extends StatefulWidget {
  const ExceptionsScreen({super.key, this.deliveryId});

  final String? deliveryId;

  @override
  State<ExceptionsScreen> createState() => _ExceptionsScreenState();
}

class _ExceptionsScreenState extends State<ExceptionsScreen> {
  String? _status = 'OPEN';
  String _severity = 'ALL';
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
        'status': ?_status,
        'deliveryId': ?widget.deliveryId,
        if (!reset) 'cursor': ?_cursor,
      };
      final path = '/exceptions?${Uri(queryParameters: query).query}';
      final page = (await api.get(path)).map;
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

  Future<void> _open(Map<String, dynamic> item) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => ExceptionDetailScreen(exceptionId: item['id'] as String),
    ));
    if (mounted) {
      await _load(reset: true);
    }
  }

  String _age(String? iso) {
    if (iso == null) {
      return '';
    }
    final diff = DateTime.now().toUtc().difference(DateTime.parse(iso).toUtc());
    if (diff.inMinutes < 60) {
      return "${diff.inMinutes}'";
    }
    if (diff.inHours < 24) {
      return "${diff.inHours}h${(diff.inMinutes % 60).toString().padLeft(2, '0')}'";
    }
    return '${diff.inDays} ngày';
  }

  @override
  Widget build(BuildContext context) {
    final rows = _severity == 'ALL' ? _items : _items.where((i) => i['severity'] == _severity).toList();
    final selected = rows.where((i) => i['id'] == _selected).firstOrNull;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: () => _load(reset: true),
            children: [
              FilterPanel(
                onSearch: () => _load(reset: true),
                onClear: () {
                  setState(() {
                    _status = 'OPEN';
                    _severity = 'ALL';
                  });
                  _load(reset: true);
                },
                children: [
                  ChoiceField<String?>(
                    label: 'Trạng thái',
                    value: _status,
                    options: _filters,
                    onChanged: (v) => setState(() => _status = v),
                  ),
                  ChoiceField<String>(
                    label: 'Mức độ',
                    value: _severity,
                    options: {'ALL': 'Tất cả', ...severityText},
                    onChanged: (v) => setState(() => _severity = v),
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
                      emptyTitle: 'Không có sự cố nào',
                      emptyMessage: 'Không có sự cố khớp bộ lọc hiện tại.',
                      rows: rows,
                      isSelected: (r) => r['id'] == _selected,
                      onTap: (r) => setState(() => _selected = r['id'] as String),
                      columns: [
                        GridColumn(title: 'Mã', primary: true, flex: 0.9, cell: (c, r) => Text('${r['code']}')),
                        GridColumn(title: 'Trạng thái', flex: 1.2, cell: (c, r) => StatusChip(r['status'] as String)),
                        GridColumn(
                          title: 'Mức độ',
                          flex: 0.9,
                          cell: (c, r) => Text(severityText[r['severity']] ?? '${r['severity']}'),
                        ),
                        GridColumn(
                          title: 'Loại',
                          flex: 1.6,
                          cell: (c, r) {
                            final type = r['type'] as Map<String, dynamic>;
                            return Text(typeLabel(type['code'] as String, type['name'] as String));
                          },
                        ),
                        GridColumn(title: 'Chuyến', flex: 1, cell: (c, r) => Text('${r['deliveryCode']}')),
                        GridColumn(title: 'Tuổi', flex: 0.6, cell: (c, r) => Text(_age(r['reportedAt'] as String?))),
                        GridColumn(
                          title: 'Chặn',
                          flex: 0.6,
                          cell: (c, r) => r['blocking'] == true
                              ? const StatusBadge(label: 'Có', tone: Tone.danger, icon: Icons.block)
                              : const Text('—'),
                        ),
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
          footer: 'Điều phối viên chỉ xem và bổ sung thông tin; Quản lý vận hành mới được xử lý.',
          children: [
            FilledButton(onPressed: selected == null ? null : () => _open(selected), child: const Text('Xem chi tiết / xử lý')),
          ],
        ),
      ],
    );
  }
}

class DeliveryExceptionsPage extends StatelessWidget {
  const DeliveryExceptionsPage({super.key, required this.deliveryId});

  final String deliveryId;

  @override
  Widget build(BuildContext context) {
    return WindowFrame(title: 'Sự cố của chuyến', body: ExceptionsScreen(deliveryId: deliveryId));
  }
}
