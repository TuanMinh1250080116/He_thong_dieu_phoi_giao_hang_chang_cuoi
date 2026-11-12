import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../state/auth_state.dart';
import '../common.dart';

class _MembersData {
  _MembersData(this.members, this.eligible);

  final List<Map<String, dynamic>> members;
  final List<Map<String, dynamic>> eligible;
}

class BatchMembersScreen extends StatefulWidget {
  const BatchMembersScreen({
    super.key,
    required this.batch,
    required this.etag,
  });

  final Map<String, dynamic> batch;
  final String? etag;

  @override
  State<BatchMembersScreen> createState() => _BatchMembersScreenState();
}

class _BatchMembersScreenState extends State<BatchMembersScreen> {
  final Set<String> _remove = {};
  final Set<String> _add = {};
  bool _busy = false;

  Future<_MembersData> _load(ApiClient api) async {
    final ids = (widget.batch['orderIds'] as List).cast<String>();
    final members = await Future.wait(
      ids.map((id) async => (await api.get('/orders/$id')).map),
    );
    final page = (await api.get(
      '/dispatch/eligible-orders',
      query: {'zoneId': widget.batch['zoneId'] as String, 'limit': '100'},
    )).map;
    return _MembersData(
      members,
      (page['items'] as List).cast<Map<String, dynamic>>(),
    );
  }

  Future<void> _save() async {
    final api = context.read<AuthState>().api;
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => api.post(
        '/dispatch/batches/${widget.batch['id']}/membership',
        body: {
          'addOrderIds': _add.toList(),
          'removeOrderIds': _remove.toList(),
        },
        ifMatch: widget.etag,
      ),
      success: 'Đã cập nhật danh sách đơn trong batch.',
    );
    if (!mounted) {
      return;
    }
    if (done != null) {
      Navigator.pop(context, true);
    } else {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    final changed = _add.isNotEmpty || _remove.isNotEmpty;
    return Scaffold(
      appBar: AppBar(title: Text('Đơn trong ${widget.batch['code']}')),
      body: LoadView<_MembersData>(
        loader: () => _load(api),
        builder: (context, data, reload) {
          return ListView(
            padding: const EdgeInsets.only(bottom: 96),
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 12, 16, 4),
                child: Text('Đơn hiện có (chọn để gỡ khỏi batch)'),
              ),
              for (final order in data.members)
                CheckboxListTile(
                  value: _remove.contains(order['id']),
                  onChanged: (on) => setState(() {
                    if (on == true) {
                      _remove.add(order['id'] as String);
                    } else {
                      _remove.remove(order['id']);
                    }
                  }),
                  title: Text('${order['code']}'),
                  subtitle: Text(
                    '${((order['package'] as Map<String, dynamic>)['weightG'] as num) / 1000} kg',
                  ),
                ),
              const Divider(),
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 8, 16, 4),
                child: Text(
                  'Đơn đang chờ điều phối trong khu vực (chọn để thêm)',
                ),
              ),
              if (data.eligible.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('Không còn đơn nào để thêm.'),
                ),
              for (final order in data.eligible)
                CheckboxListTile(
                  value: _add.contains(order['id']),
                  onChanged: (on) => setState(() {
                    if (on == true) {
                      _add.add(order['id'] as String);
                    } else {
                      _add.remove(order['id']);
                    }
                  }),
                  title: Text('${order['code']}'),
                  subtitle: Text(
                    '${order['pickupAddress']} → ${order['dropoffAddress']}\n'
                    '${(order['weightG'] as num) / 1000} kg',
                  ),
                  isThreeLine: true,
                ),
            ],
          );
        },
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: FilledButton(
            onPressed: changed && !_busy ? _save : null,
            child: Text('Lưu (+${_add.length} / −${_remove.length})'),
          ),
        ),
      ),
    );
  }
}
