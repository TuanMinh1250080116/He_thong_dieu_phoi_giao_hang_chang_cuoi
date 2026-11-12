import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/kit.dart';

const _availabilityText = <String, String>{
  'OFFLINE': 'Ngoại tuyến',
  'AVAILABLE': 'Sẵn sàng',
  'RESERVED': 'Đã giữ chỗ',
  'BUSY': 'Đang giao',
};

class FleetScreen extends StatelessWidget {
  const FleetScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const DefaultTabController(
      length: 2,
      child: Column(
        children: [
          Material(child: TabBar(tabs: [Tab(text: 'Tài xế'), Tab(text: 'Phương tiện')])),
          Expanded(child: TabBarView(children: [_MasterDetail(kind: 'drivers'), _MasterDetail(kind: 'vehicles')])),
        ],
      ),
    );
  }
}

class _MasterDetail extends StatefulWidget {
  const _MasterDetail({required this.kind});

  final String kind;

  @override
  State<_MasterDetail> createState() => _MasterDetailState();
}

class _MasterDetailState extends State<_MasterDetail> with AutomaticKeepAliveClientMixin {
  final _search = TextEditingController();
  final List<Map<String, dynamic>> _items = [];
  String? _cursor;
  bool _loading = false;
  String? _error;
  Map<String, dynamic>? _current;
  bool _creating = false;
  int _formKey = 0;

  bool get _drivers => widget.kind == 'drivers';

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _search.dispose();
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
      final query = <String, String>{
        'limit': '30',
        if (_search.text.trim().isNotEmpty) 'q': _search.text.trim(),
        if (!reset && _cursor != null) 'cursor': _cursor!,
      };
      final page = (await api.get('/admin/${widget.kind}?${Uri(queryParameters: query).query}')).map;
      if (!mounted) {
        return;
      }
      setState(() {
        _items.addAll((page['items'] as List).cast<Map<String, dynamic>>());
        _cursor = page['nextCursor'] as String?;
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

  void _select(Map<String, dynamic>? item, {bool creating = false}) {
    setState(() {
      _current = item;
      _creating = creating;
      _formKey++;
    });
    if (MediaQuery.sizeOf(context).width < 840 && (item != null || creating)) {
      Navigator.of(context)
          .push<bool>(MaterialPageRoute<bool>(
        builder: (_) => WindowFrame(
          title: creating ? (_drivers ? 'Thêm tài xế' : 'Thêm phương tiện') : (_drivers ? 'Thông tin tài xế' : 'Thông tin phương tiện'),
          body: PageBody(children: [_form(popOnDone: true)]),
        ),
      ))
          .then((_) {
        if (mounted) {
          _load(reset: true);
        }
      });
    }
  }

  Widget _form({bool popOnDone = false}) {
    void done(bool changed) {
      if (popOnDone && mounted) {
        Navigator.of(context).pop(true);
      }
      if (changed) {
        _load(reset: true);
        setState(() {
          _current = null;
          _creating = false;
          _formKey++;
        });
      }
    }

    return _drivers
        ? _DriverForm(key: ValueKey('d$_formKey'), item: _current, creating: _creating, onDone: done)
        : _VehicleForm(key: ValueKey('v$_formKey'), item: _current, creating: _creating, onDone: done);
  }

  Widget _list(bool wide) {
    return FormGroup(
      title: _drivers ? 'Danh sách tài xế' : 'Danh sách phương tiện',
      children: [
        TextField(
          controller: _search,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _load(reset: true),
          decoration: InputDecoration(
            labelText: 'Tìm kiếm...',
            prefixIcon: const Icon(Icons.search),
            suffixIcon: IconButton(icon: const Icon(Icons.arrow_forward), tooltip: 'Tìm', onPressed: () => _load(reset: true)),
          ),
        ),
        const FieldGap(),
        if (_error != null) Callout(_error!, tone: Tone.danger),
        if (_loading && _items.isEmpty)
          const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
        else
          ResultGrid<Map<String, dynamic>>(
            emptyTitle: 'Chưa có dữ liệu',
            rows: _items,
            isSelected: (r) => !_creating && _current?['id'] == r['id'],
            onTap: (r) => _select(r),
            columns: _drivers
                ? [
                    GridColumn(title: 'Mã', primary: true, flex: 0.9, cell: (c, r) => Text('${r['code']}')),
                    GridColumn(title: 'Họ tên', flex: 1.4, cell: (c, r) => Text('${r['fullName']}')),
                    GridColumn(
                      title: 'Trạng thái',
                      flex: 1.1,
                      cell: (c, r) => r['active'] == true
                          ? Text(_availabilityText[r['availability']] ?? '${r['availability']}')
                          : const StatusBadge(label: 'Ngừng', tone: Tone.neutral),
                    ),
                    GridColumn(title: 'Xe', flex: 1, cell: (c, r) => Text('${(r['vehicle'] as Map?)?['code'] ?? '—'}')),
                  ]
                : [
                    GridColumn(title: 'Biển số', primary: true, flex: 1.1, cell: (c, r) => Text('${r['code']}')),
                    GridColumn(title: 'Loại', flex: 1.2, cell: (c, r) => Text('${r['vehicleType']}')),
                    GridColumn(title: 'Tải trọng', flex: 1, cell: (c, r) => Text('${(r['capacityWeightG'] as num) / 1000} kg')),
                    GridColumn(
                      title: 'Trạng thái',
                      flex: 1,
                      cell: (c, r) => r['active'] == true
                          ? const StatusBadge(label: 'Hoạt động', tone: Tone.ok, icon: Icons.check_circle_outline)
                          : const StatusBadge(label: 'Ngừng', tone: Tone.neutral),
                    ),
                  ],
          ),
        if (_cursor != null)
          Center(
            child: TextButton(onPressed: _loading ? null : () => _load(), child: Text(_loading ? 'Đang tải...' : 'Tải thêm')),
          ),
        const FieldGap(),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton.icon(
            onPressed: () => _select(null, creating: true),
            icon: const Icon(Icons.add),
            label: const Text('Thêm mới'),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final wide = MediaQuery.sizeOf(context).width >= 840;
    return Column(
      children: [
        Expanded(
          child: wide
              ? Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(flex: 5, child: PageBody(maxWidth: 640, onRefresh: () => _load(reset: true), children: [_list(wide)])),
                    Expanded(
                      flex: 5,
                      child: PageBody(
                        maxWidth: 640,
                        children: [
                          if (_current == null && !_creating)
                            const Padding(
                              padding: EdgeInsets.all(AppTokens.s5),
                              child: EmptyState(
                                title: 'Chưa chọn mục nào',
                                message: 'Chọn một dòng trong danh sách hoặc bấm "Thêm mới".',
                                icon: Icons.touch_app_outlined,
                              ),
                            )
                          else
                            _form(),
                        ],
                      ),
                    ),
                  ],
                )
              : PageBody(onRefresh: () => _load(reset: true), children: [_list(wide)]),
        ),
        StatusFooter(_drivers ? 'Không xóa cứng tài xế đã có lịch sử giao hàng.' : 'Tải trọng dùng để kiểm tra điều kiện khi đề xuất tài xế.'),
      ],
    );
  }
}

class _VehicleForm extends StatefulWidget {
  const _VehicleForm({super.key, required this.item, required this.creating, required this.onDone});

  final Map<String, dynamic>? item;
  final bool creating;
  final void Function(bool changed) onDone;

  @override
  State<_VehicleForm> createState() => _VehicleFormState();
}

class _VehicleFormState extends State<_VehicleForm> {
  late final _code = TextEditingController(text: widget.item?['code'] as String? ?? '');
  late final _type = TextEditingController(text: widget.item?['vehicleType'] as String? ?? '');
  late final _weight = TextEditingController(text: widget.item == null ? '' : '${(widget.item!['capacityWeightG'] as num) / 1000}');
  late final _volume = TextEditingController(text: widget.item == null ? '' : '${(widget.item!['capacityVolumeCm3'] as num) / 1000000}');
  late final _note = TextEditingController(text: widget.item?['note'] as String? ?? '');
  bool _busy = false;

  bool get _editing => widget.item != null;

  @override
  void dispose() {
    for (final c in [_code, _type, _weight, _volume, _note]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final weight = double.tryParse(_weight.text.trim().replaceAll(',', '.'));
    final volume = double.tryParse(_volume.text.trim().replaceAll(',', '.'));
    if (_type.text.trim().isEmpty || weight == null || volume == null || weight <= 0 || volume <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Nhập loại xe, tải trọng (kg) và thể tích (m³) lớn hơn 0.')));
      return;
    }
    final api = context.read<AuthState>().api;
    final body = {
      if (!_editing && _code.text.trim().isNotEmpty) 'code': _code.text.trim(),
      'vehicleType': _type.text.trim(),
      'capacityWeightG': (weight * 1000).round(),
      'capacityVolumeCm3': (volume * 1000000).round(),
      'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
    };
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => _editing
          ? api.patch('/admin/vehicles/${widget.item!['id']}', body: body, ifMatch: '"${widget.item!['version']}"')
          : api.post('/admin/vehicles', body: body, idempotent: true),
      success: 'Đã lưu phương tiện.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      widget.onDone(true);
    }
  }

  Future<void> _toggle() async {
    final api = context.read<AuthState>().api;
    final active = widget.item!['active'] == true;
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => api.post('/admin/vehicles/${widget.item!['id']}/${active ? 'deactivate' : 'activate'}',
          ifMatch: '"${widget.item!['version']}"'),
      success: active ? 'Đã ngừng hoạt động phương tiện.' : 'Đã kích hoạt lại phương tiện.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      widget.onDone(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final active = widget.item?['active'] != false;
    return FormGroup(
      title: widget.creating ? 'Thêm phương tiện' : 'Thông tin phương tiện',
      trailing: _editing ? StatusBadge(label: active ? 'Hoạt động' : 'Ngừng', tone: active ? Tone.ok : Tone.neutral) : null,
      children: [
        TextField(
          controller: _code,
          enabled: !_editing,
          decoration: InputDecoration(labelText: 'Biển số', helperText: _editing ? null : 'Để trống để hệ thống tự sinh mã'),
        ),
        const FieldGap(),
        TextField(controller: _type, decoration: const InputDecoration(labelText: 'Loại xe *')),
        const FieldGap(),
        TextField(
          controller: _weight,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]'))],
          decoration: const InputDecoration(labelText: 'Tải trọng tối đa (kg) *'),
        ),
        const FieldGap(),
        TextField(
          controller: _volume,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]'))],
          decoration: const InputDecoration(labelText: 'Thể tích tối đa (m³) *'),
        ),
        const FieldGap(),
        TextField(controller: _note, maxLines: 2, decoration: const InputDecoration(labelText: 'Ghi chú vận hành')),
        const FieldGap(),
        const Callout('Ngừng hoạt động sẽ bị chặn nếu xe đang được dùng cho chuyến đang chạy.', tone: Tone.warn),
        Wrap(
          spacing: AppTokens.s2,
          runSpacing: AppTokens.s2,
          children: [
            FilledButton(onPressed: _busy ? null : _save, child: Text(_editing ? 'Lưu' : 'Thêm mới')),
            if (_editing)
              OutlinedButton(
                onPressed: _busy ? null : _toggle,
                child: Text(active ? 'Ngừng hoạt động' : 'Kích hoạt lại'),
              ),
          ],
        ),
      ],
    );
  }
}

class _DriverForm extends StatefulWidget {
  const _DriverForm({super.key, required this.item, required this.creating, required this.onDone});

  final Map<String, dynamic>? item;
  final bool creating;
  final void Function(bool changed) onDone;

  @override
  State<_DriverForm> createState() => _DriverFormState();
}

class _DriverFormState extends State<_DriverForm> {
  late final _name = TextEditingController(text: widget.item?['fullName'] as String? ?? '');
  late final _contact = TextEditingController(text: widget.item?['contact'] as String? ?? '');
  List<Map<String, dynamic>> _candidates = [];
  List<Map<String, dynamic>> _vehicles = [];
  List<Map<String, dynamic>> _zones = [];
  String? _userId;
  String? _vehicleId;
  late final Set<String> _zoneIds = {...((widget.item?['zoneIds'] as List?)?.cast<String>() ?? const <String>[])};
  bool _ready = false;
  bool _busy = false;
  bool _loadFailed = false;

  bool get _editing => widget.item != null;

  @override
  void initState() {
    super.initState();
    _vehicleId = (widget.item?['vehicle'] as Map?)?['id'] as String?;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _loadOptions();
      }
    });
  }

  @override
  void dispose() {
    _name.dispose();
    _contact.dispose();
    super.dispose();
  }

  Future<void> _loadOptions() async {
    setState(() => _loadFailed = false);
    final api = context.read<AuthState>().api;
    final loaded = await guarded(context, () async {
      final zones = ((await api.get('/admin/zone-options')).map['items'] as List).cast<Map<String, dynamic>>();
      final vehicles = ((await api.get('/admin/vehicles?limit=100&active=true')).map['items'] as List).cast<Map<String, dynamic>>();
      final candidates = _editing
          ? <Map<String, dynamic>>[]
          : ((await api.get('/admin/driver-candidates')).map['items'] as List).cast<Map<String, dynamic>>();
      return (zones: zones, vehicles: vehicles, candidates: candidates);
    });
    if (!mounted) {
      return;
    }
    if (loaded == null) {
      setState(() => _loadFailed = true);
      return;
    }
    setState(() {
      _zones = loaded.zones;
      _vehicles = loaded.vehicles;
      _candidates = loaded.candidates;
      final current = widget.item?['vehicle'] as Map?;
      if (_vehicleId != null && !_vehicles.any((v) => v['id'] == _vehicleId) && current != null) {
        _vehicles = [..._vehicles, current.cast<String, dynamic>()];
      }
      _ready = true;
    });
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty || (!_editing && _userId == null)) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_editing ? 'Nhập họ tên.' : 'Chọn tài khoản tài xế và nhập họ tên.')),
      );
      return;
    }
    final api = context.read<AuthState>().api;
    final body = {
      if (!_editing) 'userId': _userId,
      'fullName': _name.text.trim(),
      'contact': _contact.text.trim().isEmpty ? null : _contact.text.trim(),
      'vehicleId': _vehicleId,
      'zoneIds': _zoneIds.toList(),
    };
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => _editing
          ? api.patch('/admin/drivers/${widget.item!['id']}', body: body, ifMatch: '"${widget.item!['version']}"')
          : api.post('/admin/drivers', body: body, idempotent: true),
      success: 'Đã lưu hồ sơ tài xế.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      widget.onDone(true);
    }
  }

  Future<void> _toggle() async {
    final api = context.read<AuthState>().api;
    final active = widget.item!['active'] == true;
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => api.post('/admin/drivers/${widget.item!['id']}/${active ? 'deactivate' : 'activate'}',
          ifMatch: '"${widget.item!['version']}"'),
      success: active ? 'Đã ngừng hoạt động tài xế.' : 'Đã kích hoạt lại tài xế.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (done != null) {
      widget.onDone(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loadFailed) {
      return FormGroup(
        title: 'Thông tin tài xế',
        children: [
          const Callout('Không tải được danh sách vùng và phương tiện.', tone: Tone.danger),
          OutlinedButton.icon(onPressed: _loadOptions, icon: const Icon(Icons.refresh), label: const Text('Thử lại')),
        ],
      );
    }
    if (!_ready) {
      return const Padding(padding: EdgeInsets.all(AppTokens.s5), child: Center(child: CircularProgressIndicator()));
    }
    final active = widget.item?['active'] != false;
    return FormGroup(
      title: widget.creating ? 'Thêm tài xế' : 'Thông tin tài xế',
      trailing: _editing ? StatusBadge(label: active ? 'Hoạt động' : 'Ngừng', tone: active ? Tone.ok : Tone.neutral) : null,
      children: [
        if (_editing) ...[
          InfoRow('Mã tài xế', widget.item!['code'] as String?),
          InfoRow('Trạng thái làm việc', '${_availabilityText[widget.item!['availability']] ?? widget.item!['availability']} (hệ thống cập nhật)'),
          const FieldGap(),
        ] else ...[
          DropdownButtonFormField<String>(
            initialValue: _userId,
            isExpanded: true,
            decoration: InputDecoration(
              labelText: 'Tài khoản tài xế *',
              helperText: _candidates.isEmpty ? 'Chưa có tài khoản DRIVER nào chưa có hồ sơ.' : null,
            ),
            items: [
              for (final c in _candidates)
                DropdownMenuItem(value: c['userId'] as String, child: Text('${c['displayName']} (${c['email']})')),
            ],
            onChanged: (v) => setState(() => _userId = v),
          ),
          const FieldGap(),
        ],
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Họ tên *')),
        const FieldGap(),
        TextField(controller: _contact, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Số điện thoại / liên hệ')),
        const FieldGap(),
        DropdownButtonFormField<String?>(
          initialValue: _vehicleId,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Xe đang dùng'),
          items: [
            const DropdownMenuItem<String?>(value: null, child: Text('Chưa gán xe')),
            for (final v in _vehicles)
              DropdownMenuItem<String?>(value: v['id'] as String, child: Text('${v['code']} — ${v['vehicleType'] ?? v['type'] ?? ''}')),
          ],
          onChanged: (v) => setState(() => _vehicleId = v),
        ),
        const FieldGap(),
        Text('Vùng phụ trách', style: Theme.of(context).textTheme.titleSmall),
        for (final z in _zones)
          CheckboxListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text('${z['name'] ?? z['code']}'),
            value: _zoneIds.contains(z['id']),
            onChanged: (on) => setState(() {
              if (on == true) {
                _zoneIds.add(z['id'] as String);
              } else {
                _zoneIds.remove(z['id']);
              }
            }),
          ),
        const FieldGap(),
        const Callout('Ngừng hoạt động sẽ bị chặn nếu tài xế còn chuyến đang chạy.', tone: Tone.warn),
        Wrap(
          spacing: AppTokens.s2,
          runSpacing: AppTokens.s2,
          children: [
            FilledButton(onPressed: _busy ? null : _save, child: Text(_editing ? 'Lưu' : 'Thêm mới')),
            if (_editing)
              OutlinedButton(onPressed: _busy ? null : _toggle, child: Text(active ? 'Ngừng hoạt động' : 'Kích hoạt lại')),
          ],
        ),
      ],
    );
  }
}
