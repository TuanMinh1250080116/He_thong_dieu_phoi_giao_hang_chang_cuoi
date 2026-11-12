import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import 'audit_detail_screen.dart';

const _ranges = <String, Duration>{
  '1 giờ': Duration(hours: 1),
  '24 giờ': Duration(hours: 24),
  '7 ngày': Duration(days: 7),
  '30 ngày': Duration(days: 30),
};

const auditActions = <String>[
  'ORDER_CREATED',
  'ORDER_SUBMITTED',
  'ORDER_CANCELLED',
  'BATCH_CREATED',
  'BATCH_LOCKED',
  'BATCH_REOPENED',
  'RECOMMENDATION_GENERATED',
  'DRIVER_ASSIGNED',
  'DRIVER_ASSIGNMENT_ACCEPTED',
  'DRIVER_ASSIGNMENT_OVERRIDDEN',
  'DRIVER_REASSIGNED',
  'DRIVER_ASSIGNMENT_REJECTED',
  'DISPATCH_CANCELLED',
  'DELIVERY_STARTED',
  'POD_SUBMITTED',
  'EXCEPTION_REPORTED',
  'EXCEPTION_INVESTIGATING',
  'EXCEPTION_RESOLVED',
  'EXCEPTION_CANCELLED',
  'DELIVERY_COMPLETED',
  'ROLE_CHANGED',
  'ROLE_PERMISSIONS_CHANGED',
  'BATCH_MEMBERSHIP_CHANGED',
  'BATCH_CANCELLED',
  'DISPATCH_RULE_CHANGED',
];

class AuditScreen extends StatefulWidget {
  const AuditScreen({super.key});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  final _correlation = TextEditingController();
  final _entityId = TextEditingController();
  String _range = '24 giờ';
  String? _action;
  final List<Map<String, dynamic>> _items = [];
  String? _cursor;
  bool _loading = false;
  bool _loaded = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _correlation.dispose();
    _entityId.dispose();
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
      final now = DateTime.now().toUtc();
      final query = <String, String>{
        'from': now.subtract(_ranges[_range]!).toIso8601String(),
        'to': now.add(const Duration(minutes: 1)).toIso8601String(),
        'limit': '25',
        'action': ?_action,
        if (_correlation.text.trim().isNotEmpty) 'correlationId': _correlation.text.trim(),
        if (_entityId.text.trim().isNotEmpty) 'entityId': _entityId.text.trim(),
        if (!reset) 'cursor': ?_cursor,
      };
      final page = (await api.get('/audit-records?${Uri(queryParameters: query).query}')).map;
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

  String? _selected;

  static String _pretty(Object? value) =>
      value == null ? 'Không có' : const JsonEncoder.withIndent('  ').convert(value);

  @override
  Widget build(BuildContext context) {
    final selected = _items.where((i) => i['id'] == _selected).firstOrNull;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: () => _load(reset: true),
            children: [
              FilterPanel(
                onSearch: () => _load(reset: true),
                onClear: () {
                  _correlation.clear();
                  _entityId.clear();
                  setState(() {
                    _action = null;
                    _range = '24 giờ';
                  });
                  _load(reset: true);
                },
                children: [
                  ChoiceField<String>(
                    label: 'Khoảng thời gian',
                    value: _range,
                    options: {for (final label in _ranges.keys) label: label},
                    onChanged: (v) => setState(() => _range = v),
                  ),
                  ChoiceField<String?>(
                    label: 'Hành động',
                    value: _action,
                    options: {null: 'Tất cả', for (final action in auditActions) action: action},
                    onChanged: (v) => setState(() => _action = v),
                  ),
                  TextField(controller: _entityId, decoration: const InputDecoration(labelText: 'Mã đối tượng (UUID)')),
                  TextField(controller: _correlation, decoration: const InputDecoration(labelText: 'Correlation ID')),
                ],
              ),
              FormGroup(
                title: 'Kết quả (chỉ đọc)',
                children: [
                  if (_error != null) Callout(_error!, tone: Tone.danger),
                  if (!_loaded && _loading)
                    const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
                  else
                    ResultGrid<Map<String, dynamic>>(
                      emptyTitle: 'Không có bản ghi nào trong khoảng này',
                      rows: _items,
                      isSelected: (r) => r['id'] == _selected,
                      onTap: (r) => setState(() => _selected = r['id'] as String),
                      columns: [
                        GridColumn(title: 'Thời gian', flex: 1.2, cell: (c, r) => Text(formatTime(r['occurredAt'] as String?))),
                        GridColumn(
                          title: 'Người thực hiện',
                          flex: 1.3,
                          cell: (c, r) {
                            final actor = r['actor'] as Map<String, dynamic>;
                            return Text('${actor['name'] ?? (actor['type'] == 'SYSTEM' ? 'Hệ thống' : '—')}');
                          },
                        ),
                        GridColumn(title: 'Hành động', primary: true, flex: 2, cell: (c, r) => Text('${r['action']}')),
                        GridColumn(
                          title: 'Đối tượng',
                          flex: 1.3,
                          cell: (c, r) => Text('${r['entityType']} #${shortId(r['entityId'] as String?)}'),
                        ),
                        GridColumn(title: 'Lý do', flex: 1.6, cell: (c, r) => Text('${r['reason'] ?? ''}')),
                        GridColumn(
                          title: 'Correlation ID',
                          flex: 1.2,
                          cell: (c, r) => Text('${r['correlationId'] ?? ''}'.split('-').first),
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
              if (selected != null)
                FormGroup(
                  title: 'Chi tiết bản ghi',
                  trailing: TextButton(
                    onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                      builder: (_) => AuditDetailScreen(record: selected),
                    )),
                    child: const Text('Mở riêng'),
                  ),
                  children: [
                    Text('Trước (before)', style: Theme.of(context).textTheme.titleSmall),
                    SelectableText(_pretty(selected['before']), style: const TextStyle(fontFamily: 'monospace')),
                    const FieldGap(),
                    Text('Sau (after)', style: Theme.of(context).textTheme.titleSmall),
                    SelectableText(_pretty(selected['after']), style: const TextStyle(fontFamily: 'monospace')),
                  ],
                ),
            ],
          ),
        ),
        const StatusFooter('Chỉ đọc — không có Sửa/Xóa. Dữ liệu nhạy cảm đã được che.'),
      ],
    );
  }
}
