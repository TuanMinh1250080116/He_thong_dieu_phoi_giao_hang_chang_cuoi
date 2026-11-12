import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'api/api_client.dart';
import 'config.dart';
import 'state/auth_state.dart';
import 'theme/app_theme.dart';
import 'ui/home_shell.dart';
import 'ui/login_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final api = ApiClient(baseUrl: apiBaseUrl, tokenStore: SecureTokenStore());
  runApp(
    ChangeNotifierProvider(
      create: (_) => AuthState(api)..restore(),
      child: const LogisticsApp(),
    ),
  );
}

class LogisticsApp extends StatelessWidget {
  const LogisticsApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'LastMile Dispatch',
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      themeMode: ThemeMode.system,
      home: Consumer<AuthState>(
        builder: (context, auth, _) {
          if (auth.restoring) {
            return const Scaffold(body: Center(child: CircularProgressIndicator()));
          }
          return auth.isSignedIn ? const HomeShell() : const LoginScreen();
        },
      ),
    );
  }
}
