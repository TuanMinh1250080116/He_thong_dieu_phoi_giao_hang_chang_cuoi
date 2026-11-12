import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../dialogs.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import '../kit/map_view.dart';
import 'package:latlong2/latlong.dart';

const _evidenceText = <String, String>{'OTP': 'Mã OTP', 'PHOTO': 'Ảnh giao hàng', 'SIGNATURE': 'Chữ ký người nhận'};
const _recipientText = <String, String>{
  'ALWAYS': 'Luôn cần tên người nhận',
  'DIRECT_HANDOFF': 'Cần khi giao trực tiếp',
  'NEVER': 'Không cần',
};

double? _number(String text) => double.tryParse(text.trim().replaceAll(',', '.'));

Future<bool> _confirm(BuildContext context, String message) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (_) => FormDialog(
      title: 'Xác nhận',
      actions: [
        Builder(builder: (context) => TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Hủy'))),
        Builder(builder: (context) => FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Đồng ý'))),
      ],
      footer: 'Thay đổi cấu hình được ghi nhật ký kiểm toán.',
      children: [Text(message)],
    ),
  );
  return result == true;
}

class ConfigScreen extends StatefulWidget {
  const ConfigScreen({super.key});

  @override
  State<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends State<ConfigScreen> {
  late final Future<List<Map<String, dynamic>>> _zones = _loadZones();

  Future<List<Map<String, dynamic>>> _loadZones() async {
    final api = context.read<AuthState>().api;
    return ((await api.get('/admin/zone-options')).map['items'] as List).cast<Map<String, dynamic>>();
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Column(
        children: [
          const Material(child: TabBar(tabs: [Tab(text: 'Quy tắc điều phối và POD'), Tab(text: 'Vùng giao hàng')])),
          Expanded(
            child: FutureBuilder<List<Map<String, dynamic>>>(
              future: _zones,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return ProblemViewHost(error: snapshot.error!);
                }
                if (!snapshot.hasData) {
                  return const Center(child: CircularProgressIndicator());
                }
                return TabBarView(children: [
                  _RulesTab(zones: snapshot.data!),
                  const _ZonesTab(),
                ]);
              },
            ),
          ),
          const StatusFooter('Thay đổi cấu hình được ghi nhật ký kiểm toán. Phiên bản đang dùng cho chuyến đang chạy không bị sửa đè.'),
        ],
      ),
    );
  }
}

class ProblemViewHost extends StatelessWidget {
  const ProblemViewHost({super.key, required this.error});

  final Object error;

  @override
  Widget build(BuildContext context) => EmptyState(icon: Icons.cloud_off_outlined, title: 'Không tải được cấu hình', message: describeError(error));
}

String _scope(Map<String, dynamic> item, List<Map<String, dynamic>> zones) {
  final zoneId = item['zoneId'];
  if (zoneId == null) {
    return 'Mặc định toàn hệ thống';
  }
  final zone = zones.where((z) => z['id'] == zoneId).firstOrNull;
  return zone == null ? 'Vùng ${shortId('$zoneId')}' : '${zone['code']}';
}

class _RulesTab extends StatelessWidget {
  const _RulesTab({required this.zones});

  final List<Map<String, dynamic>> zones;

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= 1000;
    final rule = _VersionPanel(
      key: const ValueKey('rules'),
      title: 'Trọng số đề xuất (Recommendation)',
      path: '/admin/dispatch-rules',
      zones: zones,
      summary: (item) {
        final w = item['weights'] as Map<String, dynamic>;
        return 'D ${w['distance']} • C ${w['capacityFit']} • A ${w['availability']} • Z ${w['zoneCompat']} • ${(item['ttlSeconds'] as num) ~/ 60} phút';
      },
      createForm: (zones, onSaved) => _RuleForm(zones: zones, onSaved: onSaved),
      activateConfirm: 'Kích hoạt quy tắc này? Phiên bản đang dùng sẽ được ngưng.',
    );
    final policy = _VersionPanel(
      key: const ValueKey('policies'),
      title: 'Chính sách bằng chứng (POD policy)',
      path: '/admin/pod-policies',
      zones: zones,
      summary: (item) {
        final e = item['evidenceExpression'] as Map<String, dynamic>;
        final items = (e['items'] as List).cast<String>().map((x) => _evidenceText[x] ?? x).join(', ');
        return '${e['op'] == 'ALL_OF' ? 'Cần tất cả' : 'Cần một trong'}: $items • GPS ≤ ${item['maxGpsAccuracyM'] ?? '—'} m';
      },
      createForm: (zones, onSaved) => _PolicyForm(zones: zones, onSaved: onSaved),
      activateConfirm: 'Kích hoạt chính sách này? Chuyến bắt đầu sau đó sẽ dùng chính sách mới.',
    );
    if (wide) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: PageBody(maxWidth: 640, children: [rule])),
          Expanded(child: PageBody(maxWidth: 640, children: [policy])),
        ],
      );
    }
    return PageBody(maxWidth: 720, children: [rule, policy]);
  }
}

class _VersionPanel extends StatefulWidget {
  const _VersionPanel({
    super.key,
    required this.title,
    required this.path,
    required this.zones,
    required this.summary,
    required this.createForm,
    required this.activateConfirm,
  });

  final String title;
  final String path;
  final List<Map<String, dynamic>> zones;
  final String Function(Map<String, dynamic> item) summary;
  final Widget Function(List<Map<String, dynamic>> zones, VoidCallback onSaved) createForm;
  final String activateConfirm;

  @override
  State<_VersionPanel> createState() => _VersionPanelState();
}

class _VersionPanelState extends State<_VersionPanel> {
  int _key = 0;

  void _reload() {
    if (mounted) {
      setState(() => _key++);
    }
  }

  Future<void> _activate(Map<String, dynamic> item) async {
    final choice = await showDialog<({DateTime? at})>(
      context: context,
      builder: (_) => _ActivateDialog(message: widget.activateConfirm, name: '${item['name']} v${item['versionNo']}'),
    );
    if (choice == null || !mounted) {
      return;
    }
    final api = context.read<AuthState>().api;
    final at = choice.at;
    final done = await guarded(
      context,
      () => api.post('${widget.path}/${item['id']}/activate',
          body: at == null ? null : {'effectiveFrom': at.toUtc().toIso8601String()}, idempotent: true),
      success: at == null ? 'Đã kích hoạt phiên bản.' : 'Đã hẹn kích hoạt lúc ${formatDateTime(at)}.',
    );
    if (done != null) {
      _reload();
    }
  }

  Future<void> _unschedule(Map<String, dynamic> item) async {
    if (!await _confirm(context, 'Hủy hẹn giờ kích hoạt? Phiên bản sẽ quay về bản nháp.') || !mounted) {
      return;
    }
    final api = context.read<AuthState>().api;
    final done = await guarded(context, () => api.post('${widget.path}/${item['id']}/unschedule'), success: 'Đã hủy hẹn giờ.');
    if (done != null) {
      _reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    return Column(
      children: [
        FormGroup(
          title: widget.title,
          children: [
            LoadView<List<dynamic>>(
              key: ValueKey(_key),
              loader: () async => (await api.get(widget.path)).map['items'] as List<dynamic>,
              builder: (context, items, reload) => ResultGrid<Map<String, dynamic>>(
                emptyTitle: 'Chưa có phiên bản nào',
                rows: items.cast<Map<String, dynamic>>(),
                columns: [
                  GridColumn(title: 'Tên', primary: true, flex: 1.4, cell: (c, r) => Text('${r['name']} • v${r['versionNo']}')),
                  GridColumn(title: 'Phạm vi', flex: 1.1, cell: (c, r) => Text(_scope(r, widget.zones))),
                  GridColumn(title: 'Nội dung', flex: 2.2, cell: (c, r) => Text(widget.summary(r))),
                  GridColumn(
                    title: 'Hiệu lực từ',
                    flex: 1.1,
                    cell: (c, r) => Text(r['status'] == 'DRAFT' ? '—' : formatTime(r['effectiveFrom'] as String?)),
                  ),
                  GridColumn(
                    title: 'Trạng thái',
                    flex: 1.3,
                    cell: (c, r) => switch (r['status']) {
                      'DRAFT' => FilledButton(onPressed: () => _activate(r), child: const Text('Kích hoạt...')),
                      'SCHEDULED' => Wrap(
                          spacing: 4,
                          runSpacing: 4,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            const StatusChip('SCHEDULED'),
                            TextButton(onPressed: () => _unschedule(r), child: const Text('Hủy hẹn')),
                          ],
                        ),
                      _ => StatusChip(r['status'] as String),
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
        widget.createForm(widget.zones, _reload),
      ],
    );
  }
}

class _RuleForm extends StatefulWidget {
  const _RuleForm({required this.zones, required this.onSaved});

  final List<Map<String, dynamic>> zones;
  final VoidCallback onSaved;

  @override
  State<_RuleForm> createState() => _RuleFormState();
}

class _RuleFormState extends State<_RuleForm> {
  final _name = TextEditingController(text: 'Quy tắc mới');
  final _distance = TextEditingController(text: '0.40');
  final _capacity = TextEditingController(text: '0.25');
  final _availability = TextEditingController(text: '0.15');
  final _zoneCompat = TextEditingController(text: '0.20');
  final _ttlMinutes = TextEditingController(text: '5');
  String? _zoneId;
  DateTime? _effectiveFrom;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_name, _distance, _capacity, _availability, _zoneCompat, _ttlMinutes]) {
      c.dispose();
    }
    super.dispose();
  }

  Widget _weight(TextEditingController controller, String label) {
    return TextField(
      controller: controller,
      keyboardType: const TextInputType.numberWithOptions(decimal: true),
      decoration: InputDecoration(labelText: '$label *'),
      onChanged: (_) => setState(() {}),
    );
  }

  Future<void> _save({required bool activate}) async {
    final api = context.read<AuthState>().api;
    final weights = [_distance, _capacity, _availability, _zoneCompat].map((c) => _number(c.text)!).toList();
    final body = {
      'zoneId': _zoneId,
      'name': _name.text.trim(),
      'weights': {
        'distance': weights[0],
        'capacityFit': weights[1],
        'availability': weights[2],
        'zoneCompat': weights[3],
      },
      'ttlSeconds': (_number(_ttlMinutes.text)! * 60).round(),
      'searchRadiusM': 15000,
      'maxLocationAgeSeconds': 600,
      'persistTopN': 50,
    };
    if (activate && !await _confirm(context, _effectiveFrom == null ? 'Lưu và kích hoạt quy tắc này? Phiên bản đang dùng sẽ được ngưng.' : 'Lưu và hẹn kích hoạt lúc ${formatDateTime(_effectiveFrom!)}? Đến giờ đó phiên bản đang dùng sẽ được ngưng.')) {
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _busy = true);
    final done = await guarded(context, () async {
      final created = await api.post('/admin/dispatch-rules', body: body, idempotent: true);
      final id = created.map['id'] ?? (created.map['data'] as Map?)?['id'];
      if (activate && id != null) {
        await api.post('/admin/dispatch-rules/$id/activate',
            body: _effectiveFrom == null ? null : {'effectiveFrom': _effectiveFrom!.toUtc().toIso8601String()},
            idempotent: true);
      }
      return created;
    }, success: !activate ? 'Đã lưu bản nháp quy tắc.' : _effectiveFrom == null ? 'Đã lưu và kích hoạt quy tắc.' : 'Đã lưu và hẹn kích hoạt quy tắc.');
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      widget.onSaved();
    }
  }

  @override
  Widget build(BuildContext context) {
    final weights = [_distance, _capacity, _availability, _zoneCompat].map((c) => _number(c.text)).toList();
    final total = weights.every((w) => w != null) ? weights.fold<double>(0, (a, b) => a + b!) : null;
    final ttl = _number(_ttlMinutes.text);
    final totalOk = total != null && (total - 1).abs() < 0.00001;
    final valid = _name.text.trim().isNotEmpty && totalOk && ttl != null && ttl >= 1 && ttl <= 15;
    return FormGroup(
      title: 'Tạo phiên bản quy tắc mới',
      children: [
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Tên quy tắc *'), onChanged: (_) => setState(() {})),
        const FieldGap(),
        DropdownButtonFormField<String?>(
          initialValue: _zoneId,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Vùng áp dụng'),
          items: [
            const DropdownMenuItem<String?>(value: null, child: Text('Mặc định toàn hệ thống')),
            for (final z in widget.zones)
              DropdownMenuItem<String?>(value: z['id'] as String, child: Text('${z['code']} • ${z['name']}')),
          ],
          onChanged: (value) => setState(() => _zoneId = value),
        ),
        const FieldGap(),
        _weight(_distance, 'Distance'),
        const FieldGap(),
        _weight(_capacity, 'Capacity Fit'),
        const FieldGap(),
        _weight(_availability, 'Availability'),
        const FieldGap(),
        _weight(_zoneCompat, 'Zone Compatibility'),
        const FieldGap(),
        InfoRow('Tổng trọng số', total == null ? 'Chưa hợp lệ' : total.toStringAsFixed(4)),
        if (!totalOk) const Callout('Tổng trọng số phải đúng bằng 1.', tone: Tone.warn),
        TextField(
          controller: _ttlMinutes,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Thời hạn đề xuất (phút, 1 đến 15) *'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        const Callout('Phiên bản đã dùng cho chuyến đang chạy không bị sửa đè. Mỗi phạm vi chỉ hẹn được một phiên bản tại một thời điểm.'),
        DateField(
          label: 'Hiệu lực từ (để trống = ngay khi kích hoạt)',
          value: _effectiveFrom,
          withTime: true,
          onChanged: (v) => setState(() => _effectiveFrom = v),
        ),
        const FieldGap(),
        Wrap(
          spacing: AppTokens.s2,
          runSpacing: AppTokens.s2,
          children: [
            OutlinedButton(onPressed: valid && !_busy ? () => _save(activate: false) : null, child: const Text('Lưu nháp')),
            FilledButton(
              onPressed: valid && !_busy ? () => _save(activate: true) : null,
              child: Text(_effectiveFrom == null ? 'Lưu và kích hoạt' : 'Lưu và hẹn kích hoạt'),
            ),
          ],
        ),
      ],
    );
  }
}

class _PolicyForm extends StatefulWidget {
  const _PolicyForm({required this.zones, required this.onSaved});

  final List<Map<String, dynamic>> zones;
  final VoidCallback onSaved;

  @override
  State<_PolicyForm> createState() => _PolicyFormState();
}

class _PolicyFormState extends State<_PolicyForm> {
  final _name = TextEditingController(text: 'Chính sách mới');
  final _gps = TextEditingController(text: '100');
  final Set<String> _items = {'PHOTO'};
  String _op = 'ANY_OF';
  String _recipient = 'DIRECT_HANDOFF';
  String? _zoneId;
  DateTime? _effectiveFrom;
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _gps.dispose();
    super.dispose();
  }

  Future<void> _save({required bool activate}) async {
    final api = context.read<AuthState>().api;
    final body = {
      'zoneId': _zoneId,
      'name': _name.text.trim(),
      'evidenceExpression': {'op': _op, 'items': _evidenceText.keys.where(_items.contains).toList()},
      'maxGpsAccuracyM': int.parse(_gps.text.trim()),
      'recipientNameRule': _recipient,
    };
    if (activate && !await _confirm(context, _effectiveFrom == null ? 'Lưu và kích hoạt chính sách này? Chuyến bắt đầu sau đó sẽ dùng chính sách mới.' : 'Lưu và hẹn kích hoạt lúc ${formatDateTime(_effectiveFrom!)}?')) {
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _busy = true);
    final done = await guarded(context, () async {
      final created = await api.post('/admin/pod-policies', body: body, idempotent: true);
      final id = created.map['id'] ?? (created.map['data'] as Map?)?['id'];
      if (activate && id != null) {
        await api.post('/admin/pod-policies/$id/activate',
            body: _effectiveFrom == null ? null : {'effectiveFrom': _effectiveFrom!.toUtc().toIso8601String()},
            idempotent: true);
      }
      return created;
    }, success: !activate ? 'Đã lưu bản nháp chính sách.' : _effectiveFrom == null ? 'Đã lưu và kích hoạt chính sách.' : 'Đã lưu và hẹn kích hoạt chính sách.');
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      widget.onSaved();
    }
  }

  @override
  Widget build(BuildContext context) {
    final gps = int.tryParse(_gps.text.trim());
    final valid = _name.text.trim().isNotEmpty && _items.isNotEmpty && gps != null && gps >= 1 && gps <= 1000;
    return FormGroup(
      title: 'Tạo phiên bản chính sách mới',
      children: [
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Tên chính sách *'), onChanged: (_) => setState(() {})),
        const FieldGap(),
        DropdownButtonFormField<String?>(
          initialValue: _zoneId,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Vùng áp dụng'),
          items: [
            const DropdownMenuItem<String?>(value: null, child: Text('Mặc định toàn hệ thống')),
            for (final z in widget.zones)
              DropdownMenuItem<String?>(value: z['id'] as String, child: Text('${z['code']} • ${z['name']}')),
          ],
          onChanged: (value) => setState(() => _zoneId = value),
        ),
        const FieldGap(),
        const InfoRow('Bắt buộc', 'GPS và thời gian máy chủ ghi nhận'),
        DropdownButtonFormField<String>(
          initialValue: _op,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Cách kết hợp bằng chứng'),
          items: const [
            DropdownMenuItem(value: 'ANY_OF', child: Text('Cần ít nhất một loại đã chọn')),
            DropdownMenuItem(value: 'ALL_OF', child: Text('Cần tất cả các loại đã chọn')),
          ],
          onChanged: (value) => setState(() => _op = value!),
        ),
        for (final entry in _evidenceText.entries)
          CheckboxListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text(entry.value),
            value: _items.contains(entry.key),
            onChanged: (on) => setState(() {
              on == true ? _items.add(entry.key) : _items.remove(entry.key);
            }),
          ),
        TextField(
          controller: _gps,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(labelText: 'Sai số GPS tối đa (mét, 1 đến 1000) *'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        DropdownButtonFormField<String>(
          initialValue: _recipient,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Tên người nhận'),
          items: [for (final entry in _recipientText.entries) DropdownMenuItem(value: entry.key, child: Text(entry.value))],
          onChanged: (value) => setState(() => _recipient = value!),
        ),
        const FieldGap(),
        DateField(
          label: 'Hiệu lực từ (để trống = ngay khi kích hoạt)',
          value: _effectiveFrom,
          withTime: true,
          onChanged: (v) => setState(() => _effectiveFrom = v),
        ),
        const FieldGap(),
        Wrap(
          spacing: AppTokens.s2,
          runSpacing: AppTokens.s2,
          children: [
            OutlinedButton(onPressed: valid && !_busy ? () => _save(activate: false) : null, child: const Text('Lưu nháp')),
            FilledButton(
              onPressed: valid && !_busy ? () => _save(activate: true) : null,
              child: Text(_effectiveFrom == null ? 'Lưu và kích hoạt' : 'Lưu và hẹn kích hoạt'),
            ),
          ],
        ),
      ],
    );
  }
}

class _ZonesTab extends StatefulWidget {
  const _ZonesTab();

  @override
  State<_ZonesTab> createState() => _ZonesTabState();
}

class _ZonesTabState extends State<_ZonesTab> with AutomaticKeepAliveClientMixin {
  final _search = TextEditingController();
  final _code = TextEditingController();
  final _name = TextEditingController();
  int _listKey = 0;
  int _versionKey = 0;
  Map<String, dynamic>? _selected;
  bool _busy = false;
  bool _drawing = false;
  final List<LatLng> _corners = [];

  @override
  bool get wantKeepAlive => true;

  @override
  void dispose() {
    _search.dispose();
    _code.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _createZone() async {
    final api = context.read<AuthState>().api;
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => api.post('/admin/zones', body: {'code': _code.text.trim(), 'name': _name.text.trim()}, idempotent: true),
      success: 'Đã tạo vùng. Hãy thêm và kích hoạt một phiên bản ranh giới.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      _code.clear();
      _name.clear();
      setState(() => _listKey++);
    }
  }

  Future<void> _saveDrawn(ApiClient api, String zoneId) async {
    if (_corners.length != 2) {
      return;
    }
    final a = _corners[0];
    final b = _corners[1];
    final minLat = a.latitude < b.latitude ? a.latitude : b.latitude;
    final maxLat = a.latitude < b.latitude ? b.latitude : a.latitude;
    final minLng = a.longitude < b.longitude ? a.longitude : b.longitude;
    final maxLng = a.longitude < b.longitude ? b.longitude : a.longitude;
    final boundary = {
      'type': 'Polygon',
      'coordinates': [
        [
          [minLng, minLat],
          [maxLng, minLat],
          [maxLng, maxLat],
          [minLng, maxLat],
          [minLng, minLat],
        ],
      ],
    };
    final done = await guarded(
      context,
      () => api.post('/admin/zones/$zoneId/versions', body: {'boundary': boundary}, idempotent: true),
      success: 'Đã thêm bản nháp ranh giới từ hình vẽ.',
    );
    if (done != null && mounted) {
      setState(() {
        _drawing = false;
        _corners.clear();
        _versionKey++;
      });
    }
  }

  Future<void> _addBoundary(ApiClient api, String zoneId) async {
    final box = await showDialog<({double minLng, double minLat, double maxLng, double maxLat})>(
      context: context,
      builder: (_) => const _BoxDialog(),
    );
    if (box == null || !mounted) {
      return;
    }
    final boundary = {
      'type': 'Polygon',
      'coordinates': [
        [
          [box.minLng, box.minLat],
          [box.maxLng, box.minLat],
          [box.maxLng, box.maxLat],
          [box.minLng, box.maxLat],
          [box.minLng, box.minLat],
        ],
      ],
    };
    final done = await guarded(
      context,
      () => api.post('/admin/zones/$zoneId/versions', body: {'boundary': boundary}, idempotent: true),
      success: 'Đã thêm bản nháp ranh giới (polygon hợp lệ, 4 đỉnh).',
    );
    if (done != null && mounted) {
      setState(() => _versionKey++);
    }
  }

  Future<void> _setZoneActive(ApiClient api, Map<String, dynamic> zone) async {
    final active = zone['active'] == true;
    final reason = await askReason(
      context,
      title: active ? 'Ngừng dùng vùng' : 'Kích hoạt lại vùng',
      confirmLabel: active ? 'Ngừng dùng' : 'Kích hoạt lại',
      label: 'Lý do',
      summaryTitle: 'Vùng',
      summary: [('Mã vùng', '${zone['code']}'), ('Tên vùng', '${zone['name']}')],
      callout: active
          ? 'Vùng ngừng dùng sẽ không nhận đơn mới. Chỉ ngừng được khi không còn đơn hoặc lô đang mở.'
          : 'Vùng sẽ nhận đơn mới trở lại nếu có ranh giới đang hiệu lực.',
      footer: 'Thao tác được ghi nhật ký kiểm toán.',
    );
    if (reason == null || !mounted) {
      return;
    }
    final messenger = ScaffoldMessenger.of(context);
    final errorColor = Theme.of(context).colorScheme.error;
    try {
      await api.post('/admin/zones/${zone['id']}/${active ? 'deactivate' : 'activate'}', body: {'reason': reason});
      messenger.showSnackBar(SnackBar(content: Text(active ? 'Đã ngừng dùng vùng.' : 'Đã kích hoạt lại vùng.')));
      if (mounted) {
        setState(() {
          _listKey++;
          _selected = null;
        });
      }
    } on ApiException catch (e) {
      final orders = e.details['blockingOrders'];
      final batches = e.details['blockingBatches'];
      messenger.showSnackBar(SnackBar(
        content: Text(orders != null || batches != null
            ? 'Chưa ngừng được: còn ${orders ?? 0} đơn và ${batches ?? 0} lô đang mở trong vùng này.'
            : describeError(e)),
        backgroundColor: errorColor,
      ));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(describeError(e)), backgroundColor: errorColor));
    }
  }

  Future<void> _activate(ApiClient api, String versionId) async {
    if (!await _confirm(context, 'Kích hoạt phiên bản này? Phiên bản đang dùng sẽ được ngưng. Thay đổi vùng không ảnh hưởng chuyến đang chạy.') ||
        !mounted) {
      return;
    }
    final done = await guarded(
      context,
      () => api.post('/admin/zone-versions/$versionId/activate', idempotent: true),
      success: 'Đã kích hoạt phiên bản ranh giới.',
    );
    if (done != null && mounted) {
      setState(() {
        _versionKey++;
        _listKey++;
      });
    }
  }

  Widget _detail(ApiClient api) {
    final zone = _selected;
    if (zone == null) {
      return const Padding(
        padding: EdgeInsets.all(AppTokens.s5),
        child: EmptyState(title: 'Chưa chọn vùng nào', message: 'Chọn một vùng trong danh sách hoặc tạo vùng mới.', icon: Icons.map_outlined),
      );
    }
    final zoneId = zone['id'] as String;
    return FormGroup(
      title: 'Chi tiết vùng',
      trailing: zone['active'] != true
          ? const StatusBadge(label: 'Đã ngừng dùng', tone: Tone.neutral, icon: Icons.block)
          : StatusBadge(
              label: zone['activeVersionNo'] == null ? 'Chưa có ranh giới hiệu lực' : 'Đang hoạt động',
              tone: zone['activeVersionNo'] == null ? Tone.warn : Tone.ok,
            ),
      children: [
        InfoRow('Mã vùng', '${zone['code']}'),
        InfoRow('Tên vùng', '${zone['name']}'),
        const InfoRow('SRID', '4326 (WGS 84)'),
        _AppliedConfig(zoneId: zoneId, api: api, key: ValueKey('cfg-$zoneId-$_versionKey')),
        const FieldGap(),
        Text('Phiên bản ranh giới', style: Theme.of(context).textTheme.titleSmall),
        LoadView<List<dynamic>>(
          key: ValueKey('v-$zoneId-$_versionKey'),
          loader: () async => (await api.get('/admin/zones/$zoneId/versions')).map['items'] as List<dynamic>,
          builder: (context, items, reload) => ResultGrid<Map<String, dynamic>>(
            emptyTitle: 'Chưa có ranh giới nào',
            rows: items.cast<Map<String, dynamic>>(),
            columns: [
              GridColumn(title: 'Phiên bản', primary: true, cell: (c, v) => Text('Phiên bản ${v['versionNo']}')),
              GridColumn(title: 'Tạo lúc', flex: 1.4, cell: (c, v) => Text(formatTime(v['createdAt'] as String?))),
              GridColumn(
                title: 'Trạng thái',
                flex: 1.2,
                cell: (c, v) => v['status'] == 'DRAFT'
                    ? FilledButton(onPressed: () => _activate(api, v['id'] as String), child: const Text('Kích hoạt'))
                    : StatusChip(v['status'] as String),
              ),
            ],
          ),
        ),
        const FieldGap(),
        Text('Ranh giới vùng', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: AppTokens.s1),
        LoadView<List<dynamic>>(
          key: ValueKey('m-$zoneId-$_versionKey'),
          loader: () async => (await api.get('/admin/zones/$zoneId/versions')).map['items'] as List<dynamic>,
          builder: (context, items, reload) {
            final versions = items.cast<Map<String, dynamic>>();
            final active = versions.where((v) => v['status'] == 'ACTIVE').firstOrNull;
            final draft = versions.where((v) => v['status'] == 'DRAFT').firstOrNull;
            final shown = active ?? draft;
            final drawn = _corners.length == 2
                ? [
                    [
                      LatLng(_corners[0].latitude, _corners[0].longitude),
                      LatLng(_corners[0].latitude, _corners[1].longitude),
                      LatLng(_corners[1].latitude, _corners[1].longitude),
                      LatLng(_corners[1].latitude, _corners[0].longitude),
                    ],
                  ]
                : <List<LatLng>>[];
            return AppMap(
              height: 300,
              polygons: [...polygonsFromGeoJson(shown?['boundaryGeoJson'] as String?), ...drawn],
              pins: [for (var i = 0; i < _corners.length; i++) MapPin(_corners[i], '${i + 1}', tone: Tone.warn)],
              onTap: _drawing
                  ? (point) => setState(() {
                        if (_corners.length == 2) {
                          _corners.clear();
                        }
                        _corners.add(point);
                      })
                  : null,
              caption: _drawing
                  ? 'Chạm hai góc đối diện của hình chữ nhật. Chạm lần ba để vẽ lại.'
                  : shown == null
                      ? 'Chưa có ranh giới.'
                      : 'Đang hiển thị phiên bản ${shown['versionNo']} (${shown['status'] == 'ACTIVE' ? 'đang hiệu lực' : 'bản nháp'}).',
            );
          },
        ),
        const FieldGap(),
        Wrap(
          spacing: AppTokens.s2,
          runSpacing: AppTokens.s2,
          children: [
            if (!_drawing)
              FilledButton.icon(
                icon: const Icon(Icons.draw_outlined),
                onPressed: () => setState(() {
                  _drawing = true;
                  _corners.clear();
                }),
                label: const Text('Vẽ vùng'),
              )
            else ...[
              FilledButton.icon(
                icon: const Icon(Icons.save_outlined),
                onPressed: _corners.length == 2 ? () => _saveDrawn(api, zoneId) : null,
                label: const Text('Lưu ranh giới đã vẽ'),
              ),
              OutlinedButton(
                onPressed: () => setState(() {
                  _drawing = false;
                  _corners.clear();
                }),
                child: const Text('Hủy vẽ'),
              ),
            ],
            OutlinedButton.icon(
              icon: const Icon(Icons.add_location_alt_outlined),
              onPressed: () => _addBoundary(api, zoneId),
              label: const Text('Nhập tọa độ...'),
            ),
          ],
        ),
        const FieldGap(),
        const Callout('Geometry được kiểm tra khi lưu: polygon phải hợp lệ và không tự cắt. Bản vẽ hiện chỉ hỗ trợ hình chữ nhật.'),
        OutlinedButton.icon(
          icon: Icon(zone['active'] == true ? Icons.block : Icons.restore),
          onPressed: () => _setZoneActive(api, zone),
          label: Text(zone['active'] == true ? 'Ngừng dùng...' : 'Kích hoạt lại vùng...'),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final api = context.read<AuthState>().api;
    final wide = MediaQuery.sizeOf(context).width >= 840;
    final createValid = _code.text.trim().length >= 2 && _name.text.trim().isNotEmpty;
    final list = FormGroup(
      title: 'Danh sách vùng',
      children: [
        TextField(
          controller: _search,
          onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(labelText: 'Tìm vùng...', prefixIcon: Icon(Icons.search)),
        ),
        const FieldGap(),
        LoadView<List<dynamic>>(
          key: ValueKey('z-$_listKey'),
          loader: () async => (await api.get('/admin/zones')).map['items'] as List<dynamic>,
          builder: (context, items, reload) {
            final q = _search.text.trim().toLowerCase();
            final rows = items
                .cast<Map<String, dynamic>>()
                .where((z) => q.isEmpty || '${z['code']} ${z['name']}'.toLowerCase().contains(q))
                .toList();
            return ResultGrid<Map<String, dynamic>>(
              emptyTitle: 'Chưa có vùng nào',
              rows: rows,
              isSelected: (z) => _selected?['id'] == z['id'],
              onTap: (z) {
                setState(() => _selected = z);
                if (!wide) {
                  Navigator.of(context).push(MaterialPageRoute<void>(
                    builder: (_) => WindowFrame(title: 'Vùng ${z['code']}', body: PageBody(children: [_detail(api)])),
                  ));
                }
              },
              columns: [
                GridColumn(title: 'Mã vùng', primary: true, cell: (c, z) => Text('${z['code']}')),
                GridColumn(title: 'Tên', flex: 1.6, cell: (c, z) => Text('${z['name']}')),
                GridColumn(
                  title: 'Ranh giới',
                  flex: 1.2,
                  cell: (c, z) => z['active'] != true
                      ? const StatusBadge(label: 'Ngừng dùng', tone: Tone.neutral, icon: Icons.block)
                      : Text(z['activeVersionNo'] == null ? 'Chưa có hiệu lực' : 'Phiên bản ${z['activeVersionNo']}'),
                ),
              ],
            );
          },
        ),
        const FieldGap(),
        Text('Thêm vùng', style: Theme.of(context).textTheme.titleSmall),
        const FieldGap(),
        TextField(
          controller: _code,
          textCapitalization: TextCapitalization.characters,
          decoration: const InputDecoration(labelText: 'Mã vùng * (chữ hoa, số, - hoặc _)'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        TextField(
          controller: _name,
          decoration: const InputDecoration(labelText: 'Tên vùng *'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        FilledButton.icon(
          onPressed: createValid && !_busy ? _createZone : null,
          icon: const Icon(Icons.add),
          label: const Text('Thêm vùng'),
        ),
      ],
    );
    if (wide) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: PageBody(maxWidth: 560, children: [list])),
          Expanded(child: PageBody(maxWidth: 640, children: [_detail(api)])),
        ],
      );
    }
    return PageBody(children: [list]);
  }
}

class _BoxDialog extends StatefulWidget {
  const _BoxDialog();

  @override
  State<_BoxDialog> createState() => _BoxDialogState();
}

class _BoxDialogState extends State<_BoxDialog> {
  final _minLng = TextEditingController(text: '106.60');
  final _minLat = TextEditingController(text: '10.70');
  final _maxLng = TextEditingController(text: '106.80');
  final _maxLat = TextEditingController(text: '10.85');

  @override
  void dispose() {
    _minLng.dispose();
    _minLat.dispose();
    _maxLng.dispose();
    _maxLat.dispose();
    super.dispose();
  }

  Widget _field(TextEditingController controller, String label) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppTokens.s3),
      child: TextField(
        controller: controller,
        keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
        decoration: InputDecoration(labelText: label),
        onChanged: (_) => setState(() {}),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final values = [_number(_minLng.text), _number(_minLat.text), _number(_maxLng.text), _number(_maxLat.text)];
    final valid = values.every((v) => v != null) && values[0]! < values[2]! && values[1]! < values[3]!;
    return FormDialog(
      title: 'Ranh giới hình chữ nhật',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: valid
              ? () => Navigator.of(context).pop((minLng: values[0]!, minLat: values[1]!, maxLng: values[2]!, maxLat: values[3]!))
              : null,
          child: const Text('Thêm'),
        ),
      ],
      children: [
        _field(_minLng, 'Kinh độ nhỏ nhất'),
        _field(_minLat, 'Vĩ độ nhỏ nhất'),
        _field(_maxLng, 'Kinh độ lớn nhất'),
        _field(_maxLat, 'Vĩ độ lớn nhất'),
      ],
    );
  }
}

class _AppliedConfig extends StatelessWidget {
  const _AppliedConfig({super.key, required this.zoneId, required this.api});

  final String zoneId;
  final ApiClient api;

  Future<(String, String)> _load() async {
    Future<String> pick(String path, String Function(Map<String, dynamic>) label) async {
      final items = ((await api.get('$path?status=ACTIVE')).map['items'] as List).cast<Map<String, dynamic>>();
      final own = items.where((i) => i['zoneId'] == zoneId).firstOrNull;
      final global = items.where((i) => i['zoneId'] == null).firstOrNull;
      if (own != null) {
        return '${label(own)} (riêng của vùng)';
      }
      return global == null ? 'Chưa có phiên bản hiệu lực' : '${label(global)} (mặc định toàn hệ thống)';
    }

    final rule = await pick('/admin/dispatch-rules', (i) => '${i['name']} v${i['versionNo']}');
    final policy = await pick('/admin/pod-policies', (i) => '${i['name']} v${i['versionNo']}');
    return (rule, policy);
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<(String, String)>(
      future: _load(),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return const InfoRow('Quy tắc / chính sách', 'Đang tải...');
        }
        return Column(
          children: [
            InfoRow('Cấu hình điều phối', snapshot.data!.$1),
            InfoRow('Chính sách POD', snapshot.data!.$2),
          ],
        );
      },
    );
  }
}

class _ActivateDialog extends StatefulWidget {
  const _ActivateDialog({required this.message, required this.name});

  final String message;
  final String name;

  @override
  State<_ActivateDialog> createState() => _ActivateDialogState();
}

class _ActivateDialogState extends State<_ActivateDialog> {
  bool _later = false;
  DateTime? _at;

  @override
  Widget build(BuildContext context) {
    final valid = !_later || (_at != null && _at!.isAfter(DateTime.now().add(const Duration(minutes: 1))));
    return FormDialog(
      title: 'Kích hoạt ${widget.name}',
      footer: 'Thay đổi cấu hình được ghi nhật ký kiểm toán.',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: valid ? () => Navigator.of(context).pop((at: _later ? _at : null)) : null,
          child: Text(_later ? 'Hẹn kích hoạt' : 'Kích hoạt ngay'),
        ),
      ],
      children: [
        Text(widget.message),
        const FieldGap(),
        RadioGroup<bool>(
          groupValue: _later,
          onChanged: (v) => setState(() => _later = v ?? false),
          child: const Column(
            children: [
              RadioListTile<bool>(contentPadding: EdgeInsets.zero, value: false, title: Text('Kích hoạt ngay')),
              RadioListTile<bool>(contentPadding: EdgeInsets.zero, value: true, title: Text('Hẹn giờ hiệu lực')),
            ],
          ),
        ),
        if (_later) ...[
          DateField(label: 'Hiệu lực từ *', value: _at, withTime: true, onChanged: (v) => setState(() => _at = v)),
          const FieldGap(),
          Text('Phải sau thời điểm hiện tại ít nhất 1 phút và trong vòng 1 năm.', style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    );
  }
}
