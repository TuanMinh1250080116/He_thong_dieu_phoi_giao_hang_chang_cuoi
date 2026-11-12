import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/auth_state.dart';
import '../theme/tokens.dart';
import 'admin/config_screen.dart';
import 'admin/fleet_screen.dart';
import 'admin/roles_screen.dart';
import 'admin/users_screen.dart';
import 'audit/audit_screen.dart';
import 'customer/orders_screen.dart';
import 'dispatcher/board_screen.dart';
import 'dispatcher/dispatch_screen.dart';
import 'driver/assignments_screen.dart';
import 'driver/history_screen.dart';
import 'exceptions/exceptions_screen.dart';
import 'notifications_screen.dart';
import 'profile_screen.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _Tab {
  const _Tab(this.title, this.icon, this.build);

  final String title;
  final IconData icon;
  final Widget Function() build;
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  List<_Tab> _tabsFor(List<String> roles) {
    return [
      if (roles.contains('CUSTOMER')) _Tab('Đơn hàng', Icons.inventory_2_outlined, () => const OrdersScreen()),
      if (roles.contains('DISPATCHER') || roles.contains('OPS_MANAGER'))
        _Tab('Bảng điều phối', Icons.space_dashboard_outlined, () => const BoardHomeScreen()),
      if (roles.contains('DISPATCHER')) _Tab('Tạo lô', Icons.hub_outlined, () => const DispatchScreen()),
      if (roles.contains('DRIVER')) _Tab('Chuyến giao', Icons.local_shipping_outlined, () => const AssignmentsScreen()),
      if (roles.contains('DRIVER')) _Tab('Lịch sử', Icons.history_toggle_off, () => const HistoryScreen()),
      if (roles.contains('OPS_MANAGER') || roles.contains('DISPATCHER'))
        _Tab('Sự cố', Icons.report_problem_outlined, () => const ExceptionsScreen()),
      if (roles.contains('SYSTEM_ADMIN')) _Tab('Người dùng', Icons.people_outline, () => const UsersScreen()),
      if (roles.contains('SYSTEM_ADMIN'))
        _Tab('Vai trò và quyền', Icons.admin_panel_settings_outlined, () => const RolesScreen()),
      if (roles.contains('OPS_MANAGER')) _Tab('Đội xe', Icons.directions_car_outlined, () => const FleetScreen()),
      if (roles.contains('OPS_MANAGER')) _Tab('Cấu hình', Icons.tune, () => const ConfigScreen()),
      if (roles.contains('OPS_MANAGER') || roles.contains('SYSTEM_ADMIN'))
        _Tab('Nhật ký', Icons.fact_check_outlined, () => const AuditScreen()),
      _Tab('Thông báo', Icons.notifications_outlined, () => const NotificationsScreen()),
    ];
  }

  void _openProfile() {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const ProfileScreen()));
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthState>();
    final tabs = _tabsFor(auth.roles);
    final index = _index.clamp(0, tabs.length - 1);
    final width = MediaQuery.sizeOf(context).width;
    final wide = width >= 840;
    final account = _AccountButton(name: auth.displayName, onPressed: _openProfile);
    final body = KeyedSubtree(key: ValueKey(tabs[index].title), child: tabs[index].build());

    if (wide) {
      final tokens = AppTokens.of(context);
      return Scaffold(
        body: Row(
          children: [
            NavigationRail(
              extended: width >= 1100,
              selectedIndex: index,
              onDestinationSelected: (value) => setState(() => _index = value),
              leading: Padding(
                padding: const EdgeInsets.symmetric(vertical: AppTokens.s4),
                child: _Brand(compact: width < 1100),
              ),
              trailing: Expanded(
                child: Align(
                  alignment: Alignment.bottomCenter,
                  child: Padding(padding: const EdgeInsets.all(AppTokens.s3), child: account),
                ),
              ),
              destinations: [
                for (final tab in tabs) NavigationRailDestination(icon: Icon(tab.icon), label: Text(tab.title)),
              ],
            ),
            VerticalDivider(width: 1, color: tokens.line),
            Expanded(
              child: Column(
                children: [
                  _PageHeader(title: tabs[index].title),
                  Expanded(child: body),
                ],
              ),
            ),
          ],
        ),
      );
    }

    final bottom = tabs.length >= 2 && tabs.length <= 5;
    return Scaffold(
      appBar: AppBar(
        title: Text(tabs[index].title),
        actions: [account, const SizedBox(width: AppTokens.s2)],
      ),
      drawer: bottom
          ? null
          : Drawer(
              child: SafeArea(
                child: ListView(
                  children: [
                    const Padding(padding: EdgeInsets.all(AppTokens.s4), child: _Brand(compact: false)),
                    for (var i = 0; i < tabs.length; i++)
                      ListTile(
                        leading: Icon(tabs[i].icon),
                        title: Text(tabs[i].title),
                        selected: i == index,
                        onTap: () {
                          setState(() => _index = i);
                          Navigator.of(context).pop();
                        },
                      ),
                  ],
                ),
              ),
            ),
      body: body,
      bottomNavigationBar: bottom
          ? NavigationBar(
              selectedIndex: index,
              onDestinationSelected: (value) => setState(() => _index = value),
              destinations: [
                for (final tab in tabs) NavigationDestination(icon: Icon(tab.icon), label: tab.title),
              ],
            )
          : null,
    );
  }
}

class _Brand extends StatelessWidget {
  const _Brand({required this.compact});

  final bool compact;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final mark = Container(
      width: 36,
      height: 36,
      decoration: BoxDecoration(color: scheme.primary, borderRadius: BorderRadius.circular(AppTokens.radius)),
      child: Icon(Icons.route, color: scheme.onPrimary, size: 22),
    );
    if (compact) {
      return mark;
    }
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        mark,
        const SizedBox(width: AppTokens.s3),
        Text('LastMile Dispatch', style: Theme.of(context).textTheme.titleMedium),
      ],
    );
  }
}

class _PageHeader extends StatelessWidget {
  const _PageHeader({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Container(
      height: 56,
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.symmetric(horizontal: AppTokens.s5),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border(bottom: BorderSide(color: tokens.line)),
      ),
      child: Text(title, style: Theme.of(context).textTheme.titleLarge),
    );
  }
}

class _AccountButton extends StatelessWidget {
  const _AccountButton({required this.name, required this.onPressed});

  final String name;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final initial = name.trim().isEmpty ? '?' : name.trim().substring(0, 1).toUpperCase();
    return Tooltip(
      message: 'Hồ sơ và phiên đăng nhập',
      child: InkWell(
        borderRadius: BorderRadius.circular(AppTokens.radius),
        onTap: onPressed,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minWidth: AppTokens.tapTarget, minHeight: AppTokens.tapTarget),
          child: Center(
            child: CircleAvatar(
              radius: 16,
              backgroundColor: Theme.of(context).colorScheme.primaryContainer,
              foregroundColor: Theme.of(context).colorScheme.onPrimaryContainer,
              child: Text(initial, semanticsLabel: 'Tài khoản $name'),
            ),
          ),
        ),
      ),
    );
  }
}
