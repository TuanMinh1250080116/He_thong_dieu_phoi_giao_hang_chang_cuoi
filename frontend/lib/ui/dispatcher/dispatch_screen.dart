import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import '../kit/map_view.dart';
import 'batch_screen.dart';

final ValueNotifier<List<Map<String, String>>> recentBatches = ValueNotifier(const []);

class DispatchScreen extends StatefulWidget {
  const DispatchScreen({super.key});

  @override
  State<DispatchScreen> createState() => _DispatchScreenState();
}

class _DispatchScreenState extends State<DispatchScreen> {
  final Map<String, Map<String, dynamic>> _selected = {};
  final List<Map<String, dynamic>> _items = [];
  String? _zoneId;
  String? _cursor;
  DateTime? _from;
  DateTime? _to;
  bool _loading = false;
  bool _loaded = false;
  bool _creating = false;
  String? _error;

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
        'limit': '50',
        if (_from != null) 'windowFrom': _from!.toUtc().toIso8601String(),
        if (_to != null) 'windowTo': _to!.toUtc().toIso8601String(),
        if (!reset && _cursor != null) 'cursor': _cursor!,
      };
      final page = (await api.get('/dispatch/eligible-orders?${Uri(queryParameters: query).query}')).map;
      if (!mounted) {
        return;
      }
      setState(() {
        _items.addAll((page['items'] as List).cast<Map<String, dynamic>>());
        _cursor = page['nextCursor'] as String?;
        _loaded = true;
        _selected.removeWhere((id, _) => !_items.any((o) => o['id'] == id) && reset);
        if (_selected.isEmpty) {
          _zoneId = null;
        }
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

  void _toggle(Map<String, dynamic> order, bool on) {
    setState(() {
      final id = order['id'] as String;
      if (on) {
        _selected[id] = order;
        _zoneId = order['zoneId'] as String?;
      } else {
        _selected.remove(id);
        if (_selected.isEmpty) {
          _zoneId = null;
        }
      }
    });
  }

  Future<void> _createBatch() async {
    final api = context.read<AuthState>().api;
    setState(() => _creating = true);
    final result = await guarded(
      context,
      () => api.post('/dispatch/batches',
          body: {'zoneId': _zoneId, 'orderIds': _selected.keys.toList()}, idempotent: true),
    );
    if (!mounted) {
      return;
    }
    setState(() => _creating = false);
    if (result == null) {
      return;
    }
    final batch = result.map['data'] as Map<String, dynamic>;
    recentBatches.value = [
      {'id': batch['id'] as String, 'code': batch['code'] as String},
      ...recentBatches.value,
    ];
    setState(() => _selected.clear());
    _zoneId = null;
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => BatchScreen(batchId: batch['id'] as String),
    ));
    if (mounted) {
      await _load(reset: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final chosen = _selected.values.toList();
    final weight = chosen.fold<num>(0, (sum, o) => sum + (o['weightG'] as num));
    final volume = chosen.fold<num>(0, (sum, o) => sum + ((o['volumeCm3'] as num?) ?? 0));
    DateTime? earliest;
    DateTime? latest;
    for (final o in chosen) {
      final s = DateTime.parse(o['windowStart'] as String).toLocal();
      final e = DateTime.parse(o['windowEnd'] as String).toLocal();
      earliest = earliest == null || s.isBefore(earliest) ? s : earliest;
      latest = latest == null || e.isAfter(latest) ? e : latest;
    }
    final overlap = earliest != null && latest != null && earliest.isBefore(latest);
    final tooMany = chosen.length > 50;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: () => _load(reset: true),
            children: [
              ValueListenableBuilder<List<Map<String, String>>>(
                valueListenable: recentBatches,
                builder: (context, batches, _) {
                  if (batches.isEmpty) {
                    return const SizedBox.shrink();
                  }
                  return Padding(
                    padding: const EdgeInsets.only(bottom: AppTokens.s3),
                    child: Wrap(
                      spacing: AppTokens.s2,
                      runSpacing: AppTokens.s2,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text('Lô vừa tạo:', style: Theme.of(context).textTheme.bodySmall),
                        for (final b in batches.take(6))
                          ActionChip(
                            avatar: const Icon(Icons.inventory_2_outlined, size: 16),
                            label: Text(b['code']!),
                            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                              builder: (_) => BatchScreen(batchId: b['id']!),
                            )),
                          ),
                      ],
                    ),
                  );
                },
              ),
              FilterPanel(
                onSearch: () => _load(reset: true),
                onClear: () {
                  setState(() {
                    _from = null;
                    _to = null;
                  });
                  _load(reset: true);
                },
                children: [
                  DateField(label: 'Từ', value: _from, withTime: true, onChanged: (v) => setState(() => _from = v)),
                  DateField(label: 'Đến', value: _to, withTime: true, onChanged: (v) => setState(() => _to = v)),
                ],
              ),
              FormGroup(
                title: 'Đơn hàng đủ điều kiện (READY_FOR_DISPATCH)',
                children: [
                  if (_error != null) Callout(_error!, tone: Tone.danger),
                  if (!_loaded && _loading)
                    const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
                  else
                    ResultGrid<Map<String, dynamic>>(
                      emptyTitle: 'Không có đơn nào chờ điều phối',
                      emptyMessage: 'Đơn khách gửi sẽ xuất hiện ở đây. Thử làm mới hoặc mở rộng khung giờ.',
                      rows: _items,
                      isSelected: (o) => _selected.containsKey(o['id']),
                      onTap: (o) {
                        final blocked = _zoneId != null && o['zoneId'] != _zoneId;
                        if (blocked) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Chỉ gom các đơn cùng vùng vào một lô.')),
                          );
                          return;
                        }
                        _toggle(o, !_selected.containsKey(o['id']));
                      },
                      columns: [
                        GridColumn(
                          title: 'Chọn',
                          flex: 0.5,
                          compactHidden: true,
                          cell: (c, o) => Icon(
                            _selected.containsKey(o['id']) ? Icons.check_box : Icons.check_box_outline_blank,
                            color: _zoneId != null && o['zoneId'] != _zoneId ? Theme.of(c).disabledColor : null,
                          ),
                        ),
                        GridColumn(title: 'Mã đơn', flex: 1.1, primary: true, cell: (c, o) => Text('${o['code']}')),
                        GridColumn(title: 'Điểm giao', flex: 2, cell: (c, o) => Text('${o['dropoffAddress']}')),
                        GridColumn(
                          title: 'Khung giờ',
                          flex: 1.3,
                          cell: (c, o) => Text('${formatTime(o['windowStart'] as String?)} – ${formatTime(o['windowEnd'] as String?).split(' ').last}'),
                        ),
                        GridColumn(title: 'Khối lượng', flex: 0.8, cell: (c, o) => Text('${(o['weightG'] as num) / 1000} kg')),
                        GridColumn(
                          title: 'Thể tích',
                          flex: 0.8,
                          cell: (c, o) => Text('${(((o['volumeCm3'] as num?) ?? 0) / 1000000).toStringAsFixed(2)} m³'),
                        ),
                        GridColumn(
                          title: 'Ghi chú',
                          flex: 1,
                          cell: (c, o) => Text(_zoneId != null && o['zoneId'] != _zoneId ? 'Khác vùng' : ''),
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
              if (chosen.any((o) => latLngOf(o['dropoff']) != null))
                FormGroup(
                  title: 'Bản đồ đơn đã chọn',
                  children: [
                    AppMap(
                      height: 260,
                      pins: [
                        for (var i = 0; i < chosen.length; i++)
                          if (latLngOf(chosen[i]['dropoff']) != null)
                            MapPin(latLngOf(chosen[i]['dropoff'])!, '${i + 1}', tone: Tone.ok),
                      ],
                      caption: 'Số trên ghim là thứ tự chọn đơn, đặt tại điểm giao.',
                    ),
                  ],
                ),
              FormGroup(
                title: 'Tổng hợp lô',
                children: [
                  InfoRow('Số đơn đã chọn', '${chosen.length}'),
                  InfoRow('Tổng khối lượng', '${(weight / 1000).toStringAsFixed(1)} kg'),
                  InfoRow('Tổng thể tích', '${(volume / 1000000).toStringAsFixed(2)} m³'),
                  InfoRow('Khung giờ sớm nhất', earliest == null ? null : formatDateTime(earliest)),
                  InfoRow('Khung giờ muộn nhất', latest == null ? null : formatDateTime(latest)),
                  const FieldGap(),
                  if (chosen.isEmpty)
                    const Callout('Chọn ít nhất một đơn để tạo lô.')
                  else if (tooMany)
                    const Callout('Một lô tối đa 50 đơn.', tone: Tone.danger)
                  else if (!overlap)
                    const Callout('Các khung giờ không chồng lấn, lô có thể không khóa được.', tone: Tone.warn)
                  else
                    const Callout('Hợp lệ: cùng vùng, khung giờ chồng lấn.', tone: Tone.ok),
                ],
              ),
            ],
          ),
        ),
        ActionBar(
          footer: 'Lô mới tạo ở trạng thái DRAFT. Đơn đã thuộc lô khác không hiển thị ở đây.',
          children: [
            FilledButton.icon(
              onPressed: chosen.isEmpty || tooMany || _creating ? null : _createBatch,
              icon: const Icon(Icons.inventory_2_outlined),
              label: Text(_creating ? 'Đang tạo...' : 'Tạo lô (${chosen.length} đơn)'),
            ),
            OutlinedButton(
              onPressed: chosen.isEmpty ? null : () => setState(() {
                    _selected.clear();
                    _zoneId = null;
                  }),
              child: const Text('Bỏ chọn tất cả'),
            ),
            OutlinedButton.icon(onPressed: _loading ? null : () => _load(reset: true), icon: const Icon(Icons.refresh), label: const Text('Làm mới')),
          ],
        ),
      ],
    );
  }
}
