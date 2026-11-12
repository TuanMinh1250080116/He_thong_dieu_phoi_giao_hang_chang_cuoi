import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/error_text.dart';
import '../state/auth_state.dart';
import '../theme/tokens.dart';
import 'common.dart';
import 'kit/fields.dart';
import 'kit/kit.dart';

class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  String _show = 'UNREAD';
  DateTime? _from;
  List<Map<String, dynamic>> _items = [];
  bool _loading = true;
  String? _error;
  String? _selected;

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
      final items = ((await api.get('/notifications')).map['items'] as List).cast<Map<String, dynamic>>();
      if (mounted) {
        setState(() => _items = items);
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

  List<Map<String, dynamic>> get _visible {
    return _items.where((item) {
      if (_show == 'UNREAD' && item['status'] == 'READ') {
        return false;
      }
      if (_from != null) {
        final created = DateTime.tryParse('${item['createdAt']}')?.toLocal();
        if (created != null && created.isBefore(DateTime(_from!.year, _from!.month, _from!.day))) {
          return false;
        }
      }
      return true;
    }).toList();
  }

  Future<void> _markRead(Map<String, dynamic>? item) async {
    final api = context.read<AuthState>().api;
    await guarded(
      context,
      () => item == null ? api.post('/notifications/read-all') : api.post('/notifications/${item['id']}/read'),
    );
    if (mounted) {
      await _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final rows = _visible;
    final unreadCount = _items.where((i) => i['status'] != 'READ').length;
    final selected = rows.where((i) => i['id'] == _selected).firstOrNull;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: _load,
            maxWidth: 920,
            children: [
              FilterPanel(
                onSearch: _load,
                onClear: () => setState(() {
                  _show = 'UNREAD';
                  _from = null;
                }),
                children: [
                  ChoiceField<String>(
                    label: 'Hiển thị',
                    value: _show,
                    options: const {'UNREAD': 'Chưa đọc', 'ALL': 'Tất cả'},
                    onChanged: (v) => setState(() => _show = v),
                  ),
                  DateField(label: 'Từ ngày', value: _from, onChanged: (v) => setState(() => _from = v)),
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
                      emptyTitle: _show == 'UNREAD' ? 'Không có thông báo chưa đọc' : 'Chưa có thông báo nào',
                      emptyIcon: Icons.notifications_none,
                      rows: rows,
                      isSelected: (r) => r['id'] == _selected,
                      onTap: (r) => setState(() => _selected = r['id'] as String),
                      columns: [
                        GridColumn(
                          title: 'Tiêu đề',
                          primary: true,
                          flex: 1.6,
                          cell: (c, r) {
                            final unread = r['status'] != 'READ';
                            return Row(
                              children: [
                                if (unread)
                                  Padding(
                                    padding: const EdgeInsets.only(right: 6),
                                    child: Icon(Icons.circle, size: 10, color: Theme.of(c).colorScheme.primary, semanticLabel: 'Chưa đọc'),
                                  ),
                                Expanded(child: Text('${r['title']}', style: TextStyle(fontWeight: unread ? FontWeight.w700 : FontWeight.w400))),
                              ],
                            );
                          },
                        ),
                        GridColumn(title: 'Thời gian', cell: (c, r) => Text(formatTime(r['createdAt'] as String?))),
                        GridColumn(title: 'Nội dung', flex: 2.4, cell: (c, r) => Text('${r['body']}')),
                      ],
                    ),
                ],
              ),
            ],
          ),
        ),
        ActionBar(
          footer: unreadCount == 0 ? 'Bạn đã đọc hết thông báo.' : '$unreadCount thông báo chưa đọc',
          children: [
            FilledButton.icon(
              onPressed: selected == null || selected['status'] == 'READ' ? null : () => _markRead(selected),
              icon: const Icon(Icons.done),
              label: const Text('Đánh dấu đã đọc'),
            ),
            OutlinedButton.icon(
              onPressed: unreadCount == 0 ? null : () => _markRead(null),
              icon: const Icon(Icons.done_all),
              label: const Text('Đọc tất cả'),
            ),
          ],
        ),
      ],
    );
  }
}
