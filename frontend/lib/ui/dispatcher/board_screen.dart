import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/kit.dart';
import '../kit/problem_view.dart';
import '../exceptions/exception_detail_screen.dart';
import 'batch_screen.dart';
import 'monitor_screen.dart';

const _pollInterval = Duration(seconds: 15);

class BoardHomeScreen extends StatelessWidget {
  const BoardHomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const DefaultTabController(
      length: 2,
      child: Column(
        children: [
          Material(child: TabBar(tabs: [Tab(text: 'Tổng quan'), Tab(text: 'Chuyến đang chạy')])),
          Expanded(child: TabBarView(children: [BoardScreen(), MonitorScreen()])),
        ],
      ),
    );
  }
}

const _tiles = <String, ({String title, IconData icon, bool urgent, String action})>{
  'readyOrders': (title: 'Đơn sẵn sàng', icon: Icons.inventory_2_outlined, urgent: false, action: 'Mở danh sách đơn'),
  'draftBatches': (title: 'Lô nháp', icon: Icons.edit_note, urgent: false, action: 'Tiếp tục sửa'),
  'lockedWithoutRecommendation': (title: 'Lô khóa chưa đề xuất', icon: Icons.lock_outline, urgent: true, action: 'Tạo đề xuất'),
  'expiredRecommendations': (title: 'Đề xuất hết hạn', icon: Icons.timer_off_outlined, urgent: true, action: 'Tạo lại'),
  'awaitingDriverResponse': (title: 'Chờ tài xế phản hồi', icon: Icons.hourglass_top, urgent: false, action: 'Xem'),
  'awaitingReassignment': (title: 'Chờ gán lại', icon: Icons.swap_horiz, urgent: true, action: 'Phân công lại'),
  'activeDeliveries': (title: 'Chuyến đang chạy', icon: Icons.local_shipping_outlined, urgent: false, action: 'Giám sát'),
  'openExceptions': (title: 'Sự cố đang mở', icon: Icons.report_problem_outlined, urgent: true, action: 'Xem'),
};

const _taskKinds = [
  'awaitingReassignment',
  'expiredRecommendations',
  'lockedWithoutRecommendation',
  'openExceptions',
  'awaitingDriverResponse',
  'draftBatches',
];

String agoText(String? iso) {
  if (iso == null) {
    return '';
  }
  final diff = DateTime.now().toUtc().difference(DateTime.parse(iso).toUtc());
  if (diff.inMinutes < 1) {
    return 'vừa xong';
  }
  if (diff.inMinutes < 60) {
    return '${diff.inMinutes} phút trước';
  }
  if (diff.inHours < 24) {
    return '${diff.inHours} giờ trước';
  }
  return '${diff.inDays} ngày trước';
}

class BoardScreen extends StatefulWidget {
  const BoardScreen({super.key});

  @override
  State<BoardScreen> createState() => _BoardScreenState();
}

class _BoardScreenState extends State<BoardScreen> with AutomaticKeepAliveClientMixin {
  final _search = TextEditingController();
  Timer? _timer;
  String? _etag;
  Map<String, dynamic>? _panelsData;
  String? _asOf;
  String? _error;
  bool _loading = false;
  String _query = '';

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(_pollInterval, (_) => _refresh(silent: true));
  }

  @override
  void dispose() {
    _timer?.cancel();
    _search.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false, bool force = false}) async {
    if (_loading) {
      return;
    }
    final api = context.read<AuthState>().api;
    if (!silent) {
      setState(() => _loading = true);
    }
    try {
      final result = await api.getIfChanged('/dispatch/board',
          query: {if (_query.isNotEmpty) 'q': _query}, etag: force ? null : _etag);
      if (!mounted) {
        return;
      }
      setState(() {
        if (result != null) {
          _etag = result.etag;
          _panelsData = result.map['panels'] as Map<String, dynamic>;
          _asOf = result.map['asOf'] as String?;
        }
        _error = null;
      });
    } catch (e) {
      if (mounted) {
        setState(() => _error = describeError(e));
      }
    } finally {
      if (mounted && !silent) {
        setState(() => _loading = false);
      }
    }
  }

  void _search_() {
    _query = _search.text.trim();
    _etag = null;
    _refresh(force: true);
  }

  Future<void> _open(Widget screen) async {
    _timer?.cancel();
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => screen));
    if (!mounted) {
      return;
    }
    _timer = Timer.periodic(_pollInterval, (_) => _refresh(silent: true));
    await _refresh(force: true);
  }

  String? _filter;

  ({String code, String detail, String? when}) _describe(String kind, Map<String, dynamic> item) {
    switch (kind) {
      case 'awaitingReassignment':
        return (
          code: '${item['code']}',
          detail: 'Tài xế ${item['driverName']} từ chối: ${item['rejectionReasonCode'] ?? '—'}',
          when: item['respondedAt'] as String?,
        );
      case 'expiredRecommendations':
        return (code: '${item['code']}', detail: 'Đề xuất đã hết hạn', when: item['expiredAt'] as String?);
      case 'lockedWithoutRecommendation':
        return (code: '${item['code']}', detail: '${item['orderCount']} đơn đã khóa, chưa có đề xuất', when: item['lockedAt'] as String?);
      case 'openExceptions':
        return (code: '${item['code']}', detail: 'Chuyến ${item['deliveryCode']} • mức ${item['severity']}', when: item['reportedAt'] as String?);
      case 'awaitingDriverResponse':
        return (code: '${item['code']}', detail: 'Đã giao cho ${item['driverName']}, chờ phản hồi', when: item['decidedAt'] as String?);
      default:
        return (code: '${item['code']}', detail: '${item['orderCount']} đơn ở trạng thái nháp', when: item['createdAt'] as String?);
    }
  }

  void _openTask(String kind, Map<String, dynamic> item) {
    if (kind == 'openExceptions') {
      _open(ExceptionDetailScreen(exceptionId: item['id'] as String));
    } else {
      _open(BatchScreen(batchId: (item['batchId'] ?? item['id']) as String));
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final data = _panelsData;
    if (data == null) {
      return _error == null
          ? const Center(child: CircularProgressIndicator())
          : ProblemView(error: _error!, onRetry: () => _refresh(force: true));
    }
    final tasks = <({String kind, Map<String, dynamic> item})>[
      for (final kind in _taskKinds)
        if (_filter == null || _filter == kind)
          for (final item in ((data[kind] as Map<String, dynamic>)['items'] as List).cast<Map<String, dynamic>>())
            (kind: kind, item: item),
    ];
    final tileWidth = MediaQuery.sizeOf(context).width < 600 ? 150.0 : 168.0;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: () => _refresh(force: true),
            children: [
              FormGroup(
                title: 'Phạm vi',
                trailing: IconButton(
                  tooltip: 'Làm mới',
                  icon: _loading
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.refresh),
                  onPressed: () => _refresh(force: true),
                ),
                children: [
                  TextField(
                    controller: _search,
                    textInputAction: TextInputAction.search,
                    decoration: InputDecoration(
                      labelText: 'Tìm mã lô / đơn / chuyến',
                      prefixIcon: const Icon(Icons.search),
                      suffixIcon: IconButton(icon: const Icon(Icons.arrow_forward), onPressed: _search_),
                    ),
                    onSubmitted: (_) => _search_(),
                  ),
                  const FieldGap(),
                  Text(
                    _asOf == null ? '' : 'Cập nhật lúc ${formatTime(_asOf)} • tự làm mới mỗi 15 giây • dữ liệu theo vùng được phân công',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (_error != null) ...[const FieldGap(), Callout(_error!, tone: Tone.danger)],
                ],
              ),
              Padding(
                padding: const EdgeInsets.only(bottom: AppTokens.s4),
                child: Wrap(
                  spacing: AppTokens.s3,
                  runSpacing: AppTokens.s3,
                  children: [
                    for (final entry in _tiles.entries)
                      _StatTile(
                        width: tileWidth,
                        title: entry.value.title,
                        icon: entry.value.icon,
                        count: (data[entry.key] as Map<String, dynamic>)['count'] as int,
                        urgent: entry.value.urgent,
                        selected: _filter == entry.key,
                        onTap: () {
                          if (entry.key == 'activeDeliveries') {
                            DefaultTabController.maybeOf(context)?.animateTo(1);
                          } else if (_taskKinds.contains(entry.key)) {
                            setState(() => _filter = _filter == entry.key ? null : entry.key);
                          }
                        },
                      ),
                  ],
                ),
              ),
              FormGroup(
                title: _filter == null ? 'Việc cần xử lý' : 'Việc cần xử lý — ${_tiles[_filter]!.title}',
                trailing: _filter == null ? null : TextButton(onPressed: () => setState(() => _filter = null), child: const Text('Bỏ lọc')),
                children: [
                  ResultGrid<({String kind, Map<String, dynamic> item})>(
                    emptyTitle: 'Không có việc nào cần xử lý',
                    emptyMessage: 'Mọi thứ đang ổn trong phạm vi của bạn.',
                    rows: tasks,
                    onTap: (row) => _openTask(row.kind, row.item),
                    columns: [
                      GridColumn(
                        title: 'Loại việc',
                        flex: 1.4,
                        cell: (c, r) => Text(_tiles[r.kind]!.title, style: const TextStyle(fontWeight: FontWeight.w600)),
                      ),
                      GridColumn(title: 'Mã', primary: true, cell: (c, r) => Text(_describe(r.kind, r.item).code)),
                      GridColumn(title: 'Chi tiết', flex: 2.4, cell: (c, r) => Text(_describe(r.kind, r.item).detail)),
                      GridColumn(title: 'Thời gian', flex: 1, cell: (c, r) => Text(agoText(_describe(r.kind, r.item).when))),
                      GridColumn(
                        title: 'Thao tác',
                        flex: 1.2,
                        cell: (c, r) => TextButton(onPressed: () => _openTask(r.kind, r.item), child: Text(_tiles[r.kind]!.action)),
                      ),
                    ],
                  ),
                ],
              ),
            ],
          ),
        ),
        const StatusFooter('Dữ liệu theo phạm vi vùng được phân công.'),
      ],
    );
  }
}

class _StatTile extends StatelessWidget {
  const _StatTile({
    required this.width,
    required this.title,
    required this.icon,
    required this.count,
    required this.urgent,
    required this.selected,
    required this.onTap,
  });

  final double width;
  final String title;
  final IconData icon;
  final int count;
  final bool urgent;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    final hot = urgent && count > 0;
    final tone = hot ? Tone.danger : Tone.neutral;
    return SizedBox(
      width: width,
      child: Material(
        color: selected ? Theme.of(context).colorScheme.primaryContainer : Theme.of(context).colorScheme.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppTokens.radius),
          side: BorderSide(color: hot ? tokens.toneFg[Tone.danger]! : tokens.line, width: hot ? 1.5 : 1),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(AppTokens.radius),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(AppTokens.s3),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(icon, size: 18, color: tokens.toneFg[tone]),
                    const Spacer(),
                    if (hot) Icon(Icons.priority_high, size: 16, color: tokens.toneFg[Tone.danger], semanticLabel: 'Cần xử lý'),
                  ],
                ),
                const SizedBox(height: AppTokens.s2),
                Text('$count', style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
                Text(title, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
