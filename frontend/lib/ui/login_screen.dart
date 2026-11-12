import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/auth_state.dart';
import '../theme/tokens.dart';
import 'common.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _identifier = TextEditingController();
  final _password = TextEditingController();
  final _passwordFocus = FocusNode();
  bool _busy = false;
  bool _remember = true;
  bool _showPassword = false;

  static const _devAccounts = {
    'Khách hàng': 'customer@dev.local',
    'Điều phối viên': 'dispatcher@dev.local',
    'Tài xế': 'driver@dev.local',
    'Quản lý vận hành': 'ops@dev.local',
    'Quản trị': 'admin@dev.local',
  };

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }
    final auth = context.read<AuthState>();
    setState(() => _busy = true);
    await guarded(context, () => auth.login(_identifier.text, _password.text, remember: _remember));
    if (mounted) {
      setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    _passwordFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final tokens = AppTokens.of(context);
    final text = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(AppTokens.s5),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Container(
                          width: 44,
                          height: 44,
                          decoration: BoxDecoration(
                            color: scheme.primary,
                            borderRadius: BorderRadius.circular(AppTokens.radius),
                          ),
                          child: Icon(Icons.route, color: scheme.onPrimary),
                        ),
                        const SizedBox(width: AppTokens.s3),
                        Expanded(child: Text('LastMile Dispatch', style: text.headlineSmall)),
                      ],
                    ),
                    const SizedBox(height: AppTokens.s2),
                    Text('Hệ thống điều phối giao hàng chặng cuối', style: text.bodyMedium?.copyWith(color: tokens.inkSoft)),
                    const SizedBox(height: AppTokens.s5),
                    TextFormField(
                      controller: _identifier,
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.next,
                      autofillHints: const [AutofillHints.username, AutofillHints.email],
                      autovalidateMode: AutovalidateMode.onUserInteraction,
                      onFieldSubmitted: (_) => _passwordFocus.requestFocus(),
                      validator: (value) => (value == null || value.trim().isEmpty) ? 'Nhập tên đăng nhập hoặc email.' : null,
                      decoration: const InputDecoration(labelText: 'Tên đăng nhập / Email'),
                    ),
                    const SizedBox(height: AppTokens.s3),
                    TextFormField(
                      controller: _password,
                      focusNode: _passwordFocus,
                      obscureText: !_showPassword,
                      textInputAction: TextInputAction.done,
                      autofillHints: const [AutofillHints.password],
                      autovalidateMode: AutovalidateMode.onUserInteraction,
                      onFieldSubmitted: (_) => _submit(),
                      validator: (value) => (value == null || value.isEmpty) ? 'Nhập mật khẩu.' : null,
                      decoration: InputDecoration(
                        labelText: 'Mật khẩu',
                        suffixIcon: IconButton(
                          tooltip: _showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu',
                          icon: Icon(_showPassword ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                          onPressed: () => setState(() => _showPassword = !_showPassword),
                        ),
                      ),
                    ),
                    const SizedBox(height: AppTokens.s1),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      controlAffinity: ListTileControlAffinity.leading,
                      value: _remember,
                      onChanged: _busy ? null : (value) => setState(() => _remember = value ?? true),
                      title: const Text('Ghi nhớ đăng nhập'),
                      subtitle: const Text('Bỏ chọn nếu dùng máy dùng chung: phiên sẽ kết thúc khi đóng ứng dụng.'),
                    ),
                    const SizedBox(height: AppTokens.s2),
                    FilledButton(
                      onPressed: _busy ? null : _submit,
                      child: _busy
                          ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Đăng nhập'),
                    ),
                    if (kDebugMode) ...[
                      const SizedBox(height: AppTokens.s5),
                      Text('Tài khoản thử nghiệm (mật khẩu Passw0rd!dev)', style: text.bodySmall),
                      const SizedBox(height: AppTokens.s2),
                      Wrap(
                        spacing: AppTokens.s2,
                        runSpacing: AppTokens.s2,
                        children: [
                          for (final entry in _devAccounts.entries)
                            ActionChip(
                              label: Text(entry.key),
                              onPressed: () {
                                _identifier.text = entry.value;
                                _password.text = 'Passw0rd!dev';
                              },
                            ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
