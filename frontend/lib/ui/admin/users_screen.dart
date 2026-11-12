import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import '../dialogs.dart';

const _statusFilters = <String?, String>{
  null: 'Tất cả',
  'ACTIVE': 'Đang hoạt động',
  'DISABLED': 'Đã vô hiệu hóa',
  'LOCKED': 'Đang khóa',
};

const _roleText = <String, String>{
  'CUSTOMER': 'Khách hàng',
  'DISPATCHER': 'Điều phối viên',
  'DRIVER': 'Tài xế',
  'OPS_MANAGER': 'Quản lý vận hành',
  'SYSTEM_ADMIN': 'Quản trị hệ thống',
  'DISPATCH_OVERRIDER': 'Được chọn tài xế ngoài gợi ý',
};

class UsersScreen extends StatefulWidget {
  const UsersScreen({super.key});

  @override
  State<UsersScreen> createState() => _UsersScreenState();
}

class _UsersScreenState extends State<UsersScreen> {
  final _search = TextEditingController();
  String? _status;
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
        'limit': '20',
        'status': ?_status,
        if (_search.text.trim().isNotEmpty) 'q': _search.text.trim(),
        if (!reset) 'cursor': ?_cursor,
      };
      final page = (await api.get('/admin/users?${Uri(queryParameters: query).query}')).map;
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

  Future<void> _create() async {
    final api = context.read<AuthState>().api;
    final data = await showDialog<({String email, String username, String name, String phone, String password})>(
      context: context,
      builder: (_) => const _CreateUserDialog(),
    );
    if (data == null || !mounted) {
      return;
    }
    final done = await guarded(
      context,
      () => api.post('/admin/users',
          body: {
            'email': data.email,
            'username': data.username.isEmpty ? null : data.username,
            'displayName': data.name,
            'phone': data.phone.isEmpty ? null : data.phone,
            'password': data.password,
          },
          idempotent: true),
      success: 'Đã tạo tài khoản. Hãy gán vai trò cho người dùng này.',
    );
    if (done != null && mounted) {
      await _load(reset: true);
    }
  }

  String? _selected;

  @override
  Widget build(BuildContext context) {
    final selected = _items.where((u) => u['id'] == _selected).firstOrNull;
    return Column(
      children: [
        Expanded(
          child: PageBody(
            onRefresh: () => _load(reset: true),
            children: [
              FilterPanel(
                onSearch: () => _load(reset: true),
                onClear: () {
                  _search.clear();
                  setState(() => _status = null);
                  _load(reset: true);
                },
                children: [
                  TextField(
                    controller: _search,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => _load(reset: true),
                    decoration: const InputDecoration(labelText: 'Tìm theo email hoặc họ tên', prefixIcon: Icon(Icons.search)),
                  ),
                  ChoiceField<String?>(
                    label: 'Trạng thái',
                    value: _status,
                    options: _statusFilters,
                    onChanged: (v) => setState(() => _status = v),
                  ),
                ],
              ),
              FormGroup(
                title: 'Danh sách người dùng',
                children: [
                  if (_error != null) Callout(_error!, tone: Tone.danger),
                  if (!_loaded && _loading)
                    const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
                  else
                    ResultGrid<Map<String, dynamic>>(
                      emptyTitle: 'Không có người dùng nào',
                      rows: _items,
                      isSelected: (u) => u['id'] == _selected,
                      onTap: (u) => setState(() => _selected = u['id'] as String),
                      columns: [
                        GridColumn(title: 'Họ tên', primary: true, flex: 1.4, cell: (c, u) => Text('${u['displayName']}')),
                        GridColumn(title: 'Tên đăng nhập', flex: 1.2, cell: (c, u) => Text('${u['username'] ?? '—'}')),
                        GridColumn(title: 'Email', flex: 1.8, cell: (c, u) => Text('${u['email']}')),
                        GridColumn(
                          title: 'Vai trò',
                          flex: 1.6,
                          cell: (c, u) {
                            final roles = (u['roles'] as List).cast<String>();
                            return Text(roles.isEmpty ? 'Chưa có vai trò' : roles.map((r) => _roleText[r] ?? r).join(', '));
                          },
                        ),
                        GridColumn(
                          title: 'Trạng thái',
                          flex: 1.1,
                          cell: (c, u) => switch (u['status'] as String) {
                            'ACTIVE' => const StatusBadge(label: 'Hoạt động', tone: Tone.ok, icon: Icons.check_circle_outline),
                            'LOCKED' => const StatusBadge(label: 'Đã khóa', tone: Tone.danger, icon: Icons.lock_outline),
                            _ => const StatusBadge(label: 'Vô hiệu hóa', tone: Tone.neutral, icon: Icons.block),
                          },
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
          footer: 'Chỉ Quản trị hệ thống được truy cập màn hình này. Khóa tài khoản không xóa lịch sử.',
          children: [
            FilledButton.icon(onPressed: _create, icon: const Icon(Icons.person_add_alt_1), label: const Text('Thêm mới')),
            OutlinedButton(
              onPressed: selected == null
                  ? null
                  : () async {
                      await Navigator.of(context).push(MaterialPageRoute<void>(
                        builder: (_) => UserDetailScreen(userId: selected['id'] as String),
                      ));
                      if (mounted) {
                        await _load(reset: true);
                      }
                    },
              child: const Text('Xem / sửa'),
            ),
          ],
        ),
      ],
    );
  }
}

class _CreateUserDialog extends StatefulWidget {
  const _CreateUserDialog();

  @override
  State<_CreateUserDialog> createState() => _CreateUserDialogState();
}

class _CreateUserDialogState extends State<_CreateUserDialog> {
  final _email = TextEditingController();
  final _username = TextEditingController();
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _password = TextEditingController();

  @override
  void dispose() {
    _email.dispose();
    _username.dispose();
    _name.dispose();
    _phone.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final valid = _email.text.contains('@') && _name.text.trim().isNotEmpty && _password.text.length >= 10;
    return FormDialog(
      title: 'Thêm người dùng',
      footer: 'Tạo xong, hãy gán vai trò cho người dùng này.',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: valid
              ? () => Navigator.of(context)
                  .pop((
                    email: _email.text.trim(),
                    username: _username.text.trim(),
                    name: _name.text.trim(),
                    phone: _phone.text.trim(),
                    password: _password.text,
                  ))
              : null,
          child: const Text('Tạo'),
        ),
      ],
      children: [
        TextField(
          controller: _email,
          keyboardType: TextInputType.emailAddress,
          decoration: const InputDecoration(labelText: 'Email *'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        TextField(
          controller: _username,
          decoration: const InputDecoration(labelText: 'Tên đăng nhập', helperText: '3 đến 32 ký tự: chữ, số, dấu chấm, gạch dưới, gạch ngang'),
        ),
        const FieldGap(),
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Họ tên *'), onChanged: (_) => setState(() {})),
        const FieldGap(),
        TextField(
          controller: _phone,
          keyboardType: TextInputType.phone,
          decoration: const InputDecoration(labelText: 'Số điện thoại', helperText: '8 đến 15 chữ số, có thể bắt đầu bằng +'),
        ),
        const FieldGap(),
        TextField(
          controller: _password,
          obscureText: true,
          decoration: const InputDecoration(labelText: 'Mật khẩu ban đầu * (từ 10 ký tự, có chữ và số)'),
          onChanged: (_) => setState(() {}),
        ),
      ],
    );
  }
}

class UserDetailScreen extends StatefulWidget {
  const UserDetailScreen({super.key, required this.userId});

  final String userId;

  @override
  State<UserDetailScreen> createState() => _UserDetailScreenState();
}

class _UserDetailScreenState extends State<UserDetailScreen> {
  int _reloadKey = 0;

  Future<void> _run(Future<Object?> Function() action, {String? success}) async {
    final done = await guarded(context, action, success: success);
    if (done != null && mounted) {
      setState(() => _reloadKey++);
    }
  }

  Future<void> _rename(ApiClient api, ApiResult result) async {
    final user = result.map;
    final data = await showDialog<({String name, String username, String phone})>(
      context: context,
      builder: (_) => _ProfileDialog(
        name: '${user['displayName']}',
        username: user['username'] as String? ?? '',
        phone: user['phone'] as String? ?? '',
      ),
    );
    if (data == null) {
      return;
    }
    await _run(
      () => api.patch('/admin/users/${widget.userId}',
          body: {
            'displayName': data.name,
            'username': data.username.isEmpty ? null : data.username,
            'phone': data.phone.isEmpty ? null : data.phone,
          },
          ifMatch: result.etag),
      success: 'Đã lưu thông tin người dùng.',
    );
  }

  Future<void> _disable(ApiClient api, ApiResult result) async {
    final reason = await askReason(context, title: 'Vô hiệu hóa tài khoản', confirmLabel: 'Vô hiệu hóa');
    if (reason == null) {
      return;
    }
    await _run(
      () => api.post('/admin/users/${widget.userId}/disable', body: {'reason': reason}, ifMatch: result.etag),
      success: 'Đã vô hiệu hóa tài khoản.',
    );
  }

  Future<void> _roles(ApiClient api, ApiResult result) async {
    final user = result.map;
    final options = await guarded(context, () async {
      final roles = (await api.get('/admin/roles')).map['items'] as List;
      final zones = (await api.get('/admin/zone-options')).map['items'] as List;
      return (roles: roles.cast<Map<String, dynamic>>(), zones: zones.cast<Map<String, dynamic>>());
    });
    if (options == null || !mounted) {
      return;
    }
    final choice = await showDialog<({List<String> roles, List<String> zones, String reason})>(
      context: context,
      builder: (_) => _RolesDialog(
        roles: options.roles,
        zones: options.zones,
        selectedRoles: (user['roles'] as List).cast<String>(),
        selectedZones: (user['scopeZoneIds'] as List).cast<String>(),
      ),
    );
    if (choice == null) {
      return;
    }
    await _run(
      () => api.put('/admin/users/${widget.userId}/roles',
          body: {'roleCodes': choice.roles, 'scopeZoneIds': choice.zones, 'reason': choice.reason},
          ifMatch: result.etag),
      success: 'Đã cập nhật vai trò và phạm vi.',
    );
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.read<AuthState>();
    final api = auth.api;
    return LoadView<ApiResult>(
      key: ValueKey(_reloadKey),
      loader: () => api.get('/admin/users/${widget.userId}'),
      builder: (context, result, reload) {
        final user = result.map;
        final roles = (user['roles'] as List).cast<String>();
        final status = user['status'] as String;
        final self = auth.user?['id'] == widget.userId;
        return WindowFrame(
          title: 'Thông tin người dùng',
          body: PageBody(
            onRefresh: reload,
            maxWidth: 720,
            children: [
              FormGroup(
                title: 'Tài khoản',
                trailing: switch (status) {
                  'ACTIVE' => const StatusBadge(label: 'Hoạt động', tone: Tone.ok, icon: Icons.check_circle_outline),
                  'LOCKED' => const StatusBadge(label: 'Đã khóa', tone: Tone.danger, icon: Icons.lock_outline),
                  _ => const StatusBadge(label: 'Vô hiệu hóa', tone: Tone.neutral, icon: Icons.block),
                },
                children: [
                  InfoRow('Tên đăng nhập', user['username'] as String?),
                  InfoRow('Họ tên', '${user['displayName']}'),
                  InfoRow('Email', '${user['email']}'),
                  InfoRow('Số điện thoại', user['phone'] as String?),
                  InfoRow('Ngày tạo', formatTime(user['createdAt'] as String?)),
                  InfoRow('Số vùng phụ trách', '${(user['scopeZoneIds'] as List).length}'),
                  InfoRow('Phiên bản', '${user['version']}'),
                ],
              ),
              FormGroup(
                title: 'Vai trò',
                children: [
                  for (final entry in _roleText.entries)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        children: [
                          Icon(
                            roles.contains(entry.key) ? Icons.check_box : Icons.check_box_outline_blank,
                            size: 20,
                            semanticLabel: roles.contains(entry.key) ? 'Có' : 'Không',
                          ),
                          const SizedBox(width: AppTokens.s2),
                          Expanded(child: Text(entry.value)),
                        ],
                      ),
                    ),
                  const FieldGap(),
                  const Callout('Khóa tài khoản không xóa lịch sử. Mọi thay đổi vai trò đều được ghi nhật ký kiểm toán.'),
                  if (self) const Callout('Đây là tài khoản của bạn: không thể tự đổi vai trò hay vô hiệu hóa.', tone: Tone.warn),
                ],
              ),
            ],
          ),
          actions: ActionBar(
            footer: 'Chỉ Quản trị hệ thống được truy cập màn hình này.',
            children: [
              if (!self)
                FilledButton.icon(
                  icon: const Icon(Icons.admin_panel_settings_outlined),
                  onPressed: () => _roles(api, result),
                  label: const Text('Sửa vai trò và phạm vi'),
                ),
              OutlinedButton.icon(
                icon: const Icon(Icons.edit_outlined),
                onPressed: () => _rename(api, result),
                label: const Text('Sửa thông tin'),
              ),
              if (!self && status == 'ACTIVE')
                OutlinedButton.icon(
                  icon: const Icon(Icons.lock_outline),
                  onPressed: () => _disable(api, result),
                  label: const Text('Khóa tài khoản'),
                ),
              if (!self && status != 'ACTIVE')
                FilledButton.icon(
                  icon: const Icon(Icons.lock_open),
                  onPressed: () => _run(
                    () => api.post('/admin/users/${widget.userId}/enable', ifMatch: result.etag),
                    success: 'Đã mở khóa tài khoản.',
                  ),
                  label: const Text('Mở khóa'),
                ),
              TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Đóng')),
            ],
          ),
        );
      },
    );
  }
}

class _RolesDialog extends StatefulWidget {
  const _RolesDialog({
    required this.roles,
    required this.zones,
    required this.selectedRoles,
    required this.selectedZones,
  });

  final List<Map<String, dynamic>> roles;
  final List<Map<String, dynamic>> zones;
  final List<String> selectedRoles;
  final List<String> selectedZones;

  @override
  State<_RolesDialog> createState() => _RolesDialogState();
}

class _RolesDialogState extends State<_RolesDialog> {
  late final Set<String> _roles = {...widget.selectedRoles};
  late final Set<String> _zones = {...widget.selectedZones};
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FormDialog(
      title: 'Vai trò và phạm vi',
      footer: 'Mọi thay đổi vai trò đều được ghi nhật ký kiểm toán.',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: _reason.text.trim().isEmpty
              ? null
              : () => Navigator.of(context).pop((roles: _roles.toList(), zones: _zones.toList(), reason: _reason.text.trim())),
          child: const Text('Lưu'),
        ),
      ],
      children: [
        Text('Vai trò', style: Theme.of(context).textTheme.titleSmall),
        for (final role in widget.roles)
          CheckboxListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text(_roleText[role['code']] ?? role['code'] as String),
            value: _roles.contains(role['code']),
            onChanged: (on) => setState(() {
              on == true ? _roles.add(role['code'] as String) : _roles.remove(role['code']);
            }),
          ),
        const FieldGap(),
        Text('Vùng phụ trách', style: Theme.of(context).textTheme.titleSmall),
        if (widget.zones.isEmpty) const Text('Chưa có vùng nào.'),
        for (final zone in widget.zones)
          CheckboxListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text('${zone['code']} • ${zone['name']}'),
            value: _zones.contains(zone['id']),
            onChanged: (on) => setState(() {
              on == true ? _zones.add(zone['id'] as String) : _zones.remove(zone['id']);
            }),
          ),
        const FieldGap(),
        TextField(
          controller: _reason,
          maxLines: 2,
          decoration: const InputDecoration(labelText: 'Lý do thay đổi *'),
          onChanged: (_) => setState(() {}),
        ),
      ],
    );
  }
}

class _ProfileDialog extends StatefulWidget {
  const _ProfileDialog({required this.name, required this.username, required this.phone});

  final String name;
  final String username;
  final String phone;

  @override
  State<_ProfileDialog> createState() => _ProfileDialogState();
}

class _ProfileDialogState extends State<_ProfileDialog> {
  late final _name = TextEditingController(text: widget.name);
  late final _username = TextEditingController(text: widget.username);
  late final _phone = TextEditingController(text: widget.phone);

  @override
  void dispose() {
    _name.dispose();
    _username.dispose();
    _phone.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FormDialog(
      title: 'Sửa thông tin người dùng',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: _name.text.trim().isEmpty
              ? null
              : () => Navigator.of(context).pop((name: _name.text.trim(), username: _username.text.trim(), phone: _phone.text.trim())),
          child: const Text('Lưu'),
        ),
      ],
      children: [
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Họ tên *'), onChanged: (_) => setState(() {})),
        const FieldGap(),
        TextField(
          controller: _username,
          decoration: InputDecoration(
            labelText: 'Tên đăng nhập',
            helperText: widget.username.isEmpty ? 'Để trống nếu chỉ đăng nhập bằng email' : 'Không thể xóa tên đăng nhập, chỉ đổi được',
          ),
        ),
        const FieldGap(),
        TextField(controller: _phone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Số điện thoại')),
      ],
    );
  }
}
