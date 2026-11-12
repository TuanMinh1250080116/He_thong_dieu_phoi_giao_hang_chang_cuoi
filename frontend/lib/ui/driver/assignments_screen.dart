import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import 'delivery_screen.dart';
import 'driver_status_card.dart';

const _statusChoices = <String, String>{
  'ACTIVE': 'Cần phản hồi và đang giao',
  'ASSIGNED': 'Cần phản hồi',
  'ACCEPTED': 'Đã nhận',
  'IN_PROGRESS': 'Đang giao',
  'AWAITING_REASSIGNMENT': 'Chờ gán lại',
};

const _activeStatuses = ['ASSIGNED', 'ACCEPTED', 'IN_PROGRESS', 'AWAITING_REASSIGNMENT'];

class AssignmentsScreen extends StatefulWidget {
  const AssignmentsScreen({super.key});

  @override
  State<AssignmentsScreen> createState() => _AssignmentsScreenState();
}

class _AssignmentsScreenState extends State<AssignmentsScreen> {
  String _status = 'ACTIVE';
  DateTime? _day;
  List<Map<String, dynamic>> _items = [];
  bool _loading = true;
  String? _error;
  String? _selected;
  int _statusKey = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = context.read<AuthState>().api;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final query = <String, dynamic>{
        'limit': '50',
        'status': _status == 'ACTIVE' ? _activeStatuses : [_status],
        if (_day != null) 'from': DateTime(_day!.year, _day!.month, _day!.day).toUtc().toIso8601String(),
        if (_day != null) 'to': DateTime(_day!.year, _day!.month, _day!.day).add(const Duration(days: 1)).toUtc().toIso8601String(),
      };
      final page = (await api.get('/me/deliveries?${Uri(queryParameters: query).query}')).map;
      if (mounted) {
        setState(() => _items = (page['items'] as List).cast<Map<String, dynamic>>());
      }
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
      _status = 'ACTIVE';
      _day = null;
    });
    _load();
  }

  Future<void> _open(Map<String, dynamic> row) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => DeliveryScreen(deliveryId: row['id'] as String),
    ));
    if (mounted) {
      setState(() => _statusKey++);
      await _load();
    }
  }

  String _window(Map<String, dynamic> row) {
    final start = row['windowStart'] as String?;
    final end = row['windowEnd'] as String?;
    if (start == null || end == null) {
      return '—';
    }
    return '${formatTime(start)} – ${formatTime(end).split(' ').last}';
  }

  @override
  Widget build(BuildContext context) {
    final waiting = _items.where((r) => r['assignmentStatus'] == 'PENDING').length;
    final running = _items.where((r) => r['status'] == 'IN_PROGRESS').length;
    final selected = _items.where((r) => r['id'] == _selected).firstOrNull;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: _load,
            children: [
              DriverStatusCard(
                key: ValueKey(_statusKey),
                waitingCount: _loading ? null : waiting,
                runningCount: _loading ? null : running,
              ),
              FilterPanel(
                onSearch: _load,
                onClear: _clear,
                children: [
                  ChoiceField<String>(
                    label: 'Trạng thái',
                    value: _status,
                    options: _statusChoices,
                    onChanged: (v) => setState(() => _status = v),
                  ),
                  DateField(label: 'Ngày', value: _day, onChanged: (v) => setState(() => _day = v)),
                ],
              ),
              FormGroup(
                title: 'Kết quả',
                children: [
                  if (_error != null) Callout(_error!, tone: Tone.danger),
                  if (_loading)
                    const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
                  else
                    ResultGrid<Map<String, dynamic>>(
                      emptyTitle: 'Bạn chưa được giao chuyến nào',
                      emptyMessage: 'Khi điều phối viên giao chuyến, chuyến sẽ xuất hiện ở đây. Hãy bật trạng thái trực tuyến để được gợi ý.',
                      rows: _items,
                      isSelected: (row) => row['id'] == _selected,
                      onTap: (row) => setState(() => _selected = row['id'] as String),
                      columns: [
                        GridColumn(title: 'Mã chuyến', flex: 1.2, primary: true, cell: (c, r) => Text('${r['code']}')),
                        GridColumn(title: 'Trạng thái', flex: 1.3, cell: (c, r) => StatusChip(r['status'] as String)),
                        GridColumn(title: 'Lô', cell: (c, r) => Text('${r['batchCode']}')),
                        GridColumn(title: 'Số điểm', flex: 0.7, cell: (c, r) => Text('${r['stopCount']}')),
                        GridColumn(title: 'Khung giờ', flex: 1.4, cell: (c, r) => Text(_window(r))),
                        GridColumn(title: 'Khu vực', cell: (c, r) => Text('${r['zoneCode']}')),
                        GridColumn(
                          title: 'Cần phản hồi',
                          flex: 1.1,
                          cell: (c, r) => r['assignmentStatus'] == 'PENDING'
                              ? const StatusBadge(label: 'Có', tone: Tone.warn, icon: Icons.notifications_active_outlined)
                              : const Text('—'),
                        ),
                      ],
                    ),
                ],
              ),
            ],
          ),
        ),
        ActionBar(
          footer: 'Chỉ hiển thị chuyến được giao cho tài xế đang đăng nhập.',
          children: [
            FilledButton.icon(
              onPressed: selected == null ? null : () => _open(selected),
              icon: const Icon(Icons.open_in_new),
              label: const Text('Xem chi tiết'),
            ),
            OutlinedButton.icon(onPressed: _loading ? null : _load, icon: const Icon(Icons.refresh), label: const Text('Làm mới')),
          ],
        ),
      ],
    );
  }
}
