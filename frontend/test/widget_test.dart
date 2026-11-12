import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:logistics_app/api/api_client.dart';
import 'package:logistics_app/main.dart';
import 'package:logistics_app/state/auth_state.dart';
import 'package:provider/provider.dart';

void main() {
  testWidgets('shows the login screen when nobody is signed in', (tester) async {
    final api = ApiClient(
      baseUrl: 'http://test/api/v1',
      tokenStore: MemoryTokenStore(),
      httpClient: MockClient((request) async => http.Response('{}', 200)),
    );
    await tester.pumpWidget(
      ChangeNotifierProvider(create: (_) => AuthState(api)..restore(), child: const LogisticsApp()),
    );
    await tester.pumpAndSettle();

    expect(find.text('Đăng nhập'), findsOneWidget);
    expect(find.text('Mật khẩu'), findsOneWidget);
  });
}
