import 'package:flutter/foundation.dart';

import '../api/api_client.dart';

class AuthState extends ChangeNotifier {
  AuthState(this.api) {
    api.onSessionExpired = _expired;
  }

  final ApiClient api;
  Map<String, dynamic>? user;
  bool restoring = true;

  bool get isSignedIn => user != null;

  List<String> get roles => ((user?['roles'] as List?) ?? const []).cast<String>();

  String get displayName => (user?['displayName'] as String?) ?? '';

  Future<void> restore() async {
    try {
      if (await api.restoreSession()) {
        user = (await api.get('/me')).map;
      }
    } catch (_) {
      user = null;
    }
    restoring = false;
    notifyListeners();
  }

  Future<void> login(String identifier, String password, {bool remember = true}) async {
    user = await api.login(identifier.trim(), password, remember: remember);
    notifyListeners();
  }

  Future<void> logout() async {
    await api.logout();
    user = null;
    notifyListeners();
  }

  void _expired() {
    user = null;
    notifyListeners();
  }
}
