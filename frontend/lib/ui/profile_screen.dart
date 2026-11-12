import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/auth_state.dart';
import 'common.dart';
import 'kit/kit.dart';

const roleLabels = <String, String>{
  'CUSTOMER': 'Khách hàng',
  'DISPATCHER': 'Điều phối viên',
  'DRIVER': 'Tài xế',
  'OPS_MANAGER': 'Quản lý vận hành',
  'SYSTEM_ADMIN': 'Quản trị hệ thống',
  'DISPATCH_OVERRIDER': 'Được chọn tài xế ngoài gợi ý',
};

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  bool _busy = false;

  String _device() {
    final platform = switch (defaultTargetPlatform) {
      TargetPlatform.android => 'Android',
      TargetPlatform.iOS => 'iOS',
      TargetPlatform.windows => 'Windows',
      TargetPlatform.macOS => 'macOS',
      TargetPlatform.linux => 'Linux',
      TargetPlatform.fuchsia => 'Fuchsia',
    };
    return kIsWeb ? 'Trình duyệt web' : platform;
  }

  Future<void> _refresh() async {
    final auth = context.read<AuthState>();
    setState(() => _busy = true);
    final ok = await guarded(context, () => auth.api.refreshSession(), success: 'Đã làm mới phiên đăng nhập.');
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (ok == false) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Không làm mới được phiên. Hãy đăng nhập lại.')));
    }
  }

  Future<void> _logout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Đăng xuất'),
        content: const Text('Bạn muốn kết thúc phiên đăng nhập trên thiết bị này?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Hủy')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Đăng xuất')),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    final navigator = Navigator.of(context);
    await context.read<AuthState>().logout();
    navigator.popUntil((route) => route.isFirst);
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthState>();
    final user = auth.user ?? const <String, dynamic>{};
    final roles = auth.roles;
    final started = auth.api.sessionStartedAt;
    final refreshed = auth.api.lastRefreshedAt;
    return WindowFrame(
      title: 'Hồ sơ và phiên đăng nhập',
      body: PageBody(
        maxWidth: 720,
        children: [
          FormGroup(
            title: 'Tài khoản',
            children: [
              InfoRow('Họ tên', user['displayName'] as String?),
              InfoRow('Email', user['email'] as String?),
              InfoRow(
                'Vai trò',
                null,
                valueWidget: Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [for (final role in roles) StatusBadge(label: roleLabels[role] ?? role)],
                ),
              ),
              InfoRow('Số khu vực phụ trách', '${(user['scopeZoneIds'] as List?)?.length ?? 0}'),
            ],
          ),
          FormGroup(
            title: 'Phiên đăng nhập',
            children: [
              InfoRow('Đăng nhập lúc', started == null ? 'Khôi phục từ lần trước' : formatTime(started.toIso8601String())),
              InfoRow('Làm mới lần cuối', refreshed == null ? '—' : formatTime(refreshed.toIso8601String())),
              InfoRow('Thiết bị', _device()),
              const SizedBox(height: 8),
              const Callout(
                'Quyền và menu được tải lại mỗi lần bạn đăng nhập. Nếu quản trị viên đổi vai trò của bạn, hãy làm mới phiên hoặc đăng nhập lại.',
              ),
            ],
          ),
        ],
      ),
      actions: ActionBar(
        footer: 'Đăng xuất sẽ xóa phiên trên thiết bị này.',
        children: [
          OutlinedButton.icon(
            onPressed: _busy ? null : _refresh,
            icon: const Icon(Icons.refresh),
            label: const Text('Làm mới phiên'),
          ),
          FilledButton.icon(onPressed: _busy ? null : _logout, icon: const Icon(Icons.logout), label: const Text('Đăng xuất')),
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Đóng')),
        ],
      ),
    );
  }
}
