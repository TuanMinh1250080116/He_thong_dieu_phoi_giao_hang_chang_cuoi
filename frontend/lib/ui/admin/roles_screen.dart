import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/kit.dart';
import '../profile_screen.dart';

const permissionText = <String, String>{
  'order.create': 'Tạo và gửi đơn hàng',
  'order.view.own': 'Xem đơn của chính mình',
  'order.view.scope': 'Xem đơn trong khu vực phụ trách',
  'dispatch.board.view': 'Xem bảng điều phối',
  'dispatch.batch.manage': 'Tạo / sửa / khóa / mở lại lô',
  'dispatch.recommendation.generate': 'Tạo đề xuất tài xế',
  'dispatch.assign': 'Phân công tài xế',
  'dispatch.recommendation.override': 'Ghi đè đề xuất (cấp riêng)',
  'dispatch.reassign': 'Phân công lại',
  'dispatch.cancel': 'Hủy điều phối, hủy lô',
  'delivery.monitor': 'Giám sát chuyến đang chạy',
  'delivery.execute': 'Thực hiện chuyến giao',
  'proof.submit': 'Nộp bằng chứng giao hàng',
  'proof.view': 'Xem bằng chứng giao hàng',
  'exception.report': 'Báo cáo sự cố',
  'exception.view': 'Xem sự cố',
  'exception.context.add': 'Bổ sung thông tin sự cố',
  'exception.manage': 'Điều tra / giải quyết / hủy sự cố',
  'fleet.manage': 'Quản lý tài xế và phương tiện',
  'config.zone.manage': 'Cấu hình vùng giao hàng',
  'config.rule.manage': 'Cấu hình quy tắc điều phối và POD',
  'audit.view': 'Tra cứu nhật ký kiểm toán',
  'iam.user.manage': 'Quản lý người dùng',
  'iam.role.manage': 'Quản lý vai trò và quyền',
};

class _RolesData {
  _RolesData(this.roles, this.allPermissions);

  final Map<String, List<String>> roles;
  final List<String> allPermissions;
}

class RolesScreen extends StatefulWidget {
  const RolesScreen({super.key});

  @override
  State<RolesScreen> createState() => _RolesScreenState();
}

class _RolesScreenState extends State<RolesScreen> {
  int _reloadKey = 0;
  String? _role;
  Set<String> _selected = {};
  String? _loadedFor;
  final _reason = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<_RolesData> _load() async {
    final api = context.read<AuthState>().api;
    final body = (await api.get('/admin/roles')).map;
    final roles = <String, List<String>>{
      for (final item in (body['items'] as List).cast<Map<String, dynamic>>())
        item['code'] as String: (item['permissions'] as List).cast<String>(),
    };
    roles.remove('DISPATCH_OVERRIDER');
    return _RolesData(roles, (body['allPermissions'] as List).cast<String>());
  }

  void _pick(String role, _RolesData data) {
    _role = role;
    _loadedFor = role;
    _selected = {...data.roles[role] ?? const <String>[]};
    _reason.clear();
  }

  Future<void> _save() async {
    final api = context.read<AuthState>().api;
    final role = _role!;
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => api.put('/admin/roles/$role/permissions',
          body: {'permissionCodes': _selected.toList()..sort(), 'reason': _reason.text.trim()}),
      success: 'Đã lưu quyền cho vai trò ${roleLabels[role] ?? role}.',
    );
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      if (done != null) {
        _loadedFor = null;
        _reloadKey++;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return LoadView<_RolesData>(
      key: ValueKey(_reloadKey),
      loader: _load,
      builder: (context, data, reload) {
        if (_role == null || !data.roles.containsKey(_role) || _loadedFor == null) {
          _pick(_role != null && data.roles.containsKey(_role) ? _role! : data.roles.keys.first, data);
        }
        final wide = MediaQuery.sizeOf(context).width >= 840;
        final roleList = _RoleList(
          roles: data.roles.keys.toList(),
          selected: _role!,
          horizontal: !wide,
          onSelect: (role) => setState(() => _pick(role, data)),
        );
        final original = {...data.roles[_role]!};
        final changed = !(original.length == _selected.length && original.containsAll(_selected));
        final editor = _PermissionEditor(
          role: _role!,
          all: data.allPermissions,
          selected: _selected,
          reason: _reason,
          busy: _busy,
          changed: changed,
          onToggle: (code, on) => setState(() => on ? _selected.add(code) : _selected.remove(code)),
          onSave: _save,
          onReset: () => setState(() => _pick(_role!, data)),
        );
        return Column(
          children: [
            Expanded(
              child: wide
                  ? Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(width: 240, child: ListView(padding: const EdgeInsets.all(AppTokens.s4), children: [roleList])),
                        Expanded(child: PageBody(maxWidth: 820, children: [editor])),
                      ],
                    )
                  : PageBody(children: [roleList, const SizedBox(height: AppTokens.s3), editor]),
            ),
            const StatusFooter('Thay đổi quyền được ghi nhật ký kiểm toán (trước / sau).'),
          ],
        );
      },
    );
  }
}

class _RoleList extends StatelessWidget {
  const _RoleList({required this.roles, required this.selected, required this.horizontal, required this.onSelect});

  final List<String> roles;
  final String selected;
  final bool horizontal;
  final ValueChanged<String> onSelect;

  @override
  Widget build(BuildContext context) {
    if (horizontal) {
      return Wrap(
        spacing: AppTokens.s2,
        runSpacing: AppTokens.s2,
        children: [
          for (final role in roles)
            ChoiceChip(label: Text(roleLabels[role] ?? role), selected: role == selected, onSelected: (_) => onSelect(role)),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionTitle('Vai trò'),
        for (final role in roles)
          ListTile(
            dense: true,
            selected: role == selected,
            selectedTileColor: Theme.of(context).colorScheme.primaryContainer,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppTokens.radius)),
            title: Text(roleLabels[role] ?? role),
            subtitle: Text(role),
            onTap: () => onSelect(role),
          ),
      ],
    );
  }
}

class _PermissionEditor extends StatelessWidget {
  const _PermissionEditor({
    required this.role,
    required this.all,
    required this.selected,
    required this.reason,
    required this.busy,
    required this.changed,
    required this.onToggle,
    required this.onSave,
    required this.onReset,
  });

  final String role;
  final List<String> all;
  final Set<String> selected;
  final TextEditingController reason;
  final bool busy;
  final bool changed;
  final void Function(String code, bool on) onToggle;
  final VoidCallback onSave;
  final VoidCallback onReset;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    final canSave = changed && reason.text.trim().isNotEmpty && !busy;
    return FormGroup(
      title: 'Quyền của vai trò: ${roleLabels[role] ?? role}',
      children: [
        for (final code in all)
          CheckboxListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            value: selected.contains(code),
            onChanged: busy ? null : (on) => onToggle(code, on ?? false),
            title: Text(permissionText[code] ?? code),
            subtitle: Text(code, style: Theme.of(context).textTheme.bodySmall?.copyWith(fontFamily: 'monospace', color: tokens.inkSoft)),
          ),
        const FieldGap(),
        const Callout('Quản trị hệ thống không tự động có quyền nghiệp vụ của điều phối viên hay quản lý vận hành.', tone: Tone.warn),
        StatefulBuilder(
          builder: (context, setLocal) => TextField(
            controller: reason,
            maxLines: 2,
            onChanged: (_) => setLocal(() {}),
            decoration: const InputDecoration(labelText: 'Lý do thay đổi *'),
          ),
        ),
        const FieldGap(),
        ListenableBuilder(
          listenable: reason,
          builder: (context, _) => Wrap(
            spacing: AppTokens.s2,
            runSpacing: AppTokens.s2,
            children: [
              FilledButton.icon(
                onPressed: changed && reason.text.trim().isNotEmpty && !busy ? onSave : null,
                icon: const Icon(Icons.save_outlined),
                label: Text(canSave || !changed ? 'Lưu thay đổi' : 'Lưu thay đổi (cần lý do)'),
              ),
              OutlinedButton(onPressed: changed && !busy ? onReset : null, child: const Text('Hoàn tác')),
            ],
          ),
        ),
      ],
    );
  }
}
