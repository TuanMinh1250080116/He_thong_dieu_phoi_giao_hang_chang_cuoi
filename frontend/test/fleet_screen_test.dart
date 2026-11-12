import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:logistics_app/api/api_client.dart';
import 'package:logistics_app/state/auth_state.dart';
import 'package:logistics_app/theme/app_theme.dart';
import 'package:logistics_app/ui/admin/fleet_screen.dart';
import 'package:provider/provider.dart';

const _driver = {
  'id': 'd1',
  'userId': 'u1',
  'code': 'DEV-DRV-01',
  'fullName': 'Dev Driver',
  'contact': null,
  'active': true,
  'availability': 'AVAILABLE',
  'vehicle': {'id': 'v1', 'code': 'DEV-VAN-01', 'vehicleType': 'VAN'},
  'zoneIds': ['z1'],
  'version': 3,
};

http.Response _json(Object body) =>
    http.Response(jsonEncode(body), 200, headers: {'content-type': 'application/json; charset=utf-8'});

void main() {
  testWidgets('opening a driver shows the form instead of spinning forever', (tester) async {
    tester.view.physicalSize = const Size(1280, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final client = MockClient((request) async {
      final path = request.url.path;
      if (path.endsWith('/admin/drivers')) {
        return _json({'items': [_driver], 'nextCursor': null, 'asOf': '2026-10-27T00:00:00Z'});
      }
      if (path.endsWith('/admin/vehicles')) {
        return _json({'items': [], 'nextCursor': null, 'asOf': '2026-10-27T00:00:00Z'});
      }
      if (path.endsWith('/admin/zone-options')) {
        return _json({
          'items': [
            {'id': 'z1', 'code': 'DEV-HCM', 'name': 'Ho Chi Minh City (dev)'},
          ],
        });
      }
      return http.Response('{}', 404);
    });
    final api = ApiClient(baseUrl: 'http://test/api/v1', tokenStore: MemoryTokenStore(), httpClient: client);

    await tester.pumpWidget(
      ChangeNotifierProvider(
        create: (_) => AuthState(api),
        child: MaterialApp(theme: buildTheme(Brightness.light), home: const Scaffold(body: FleetScreen())),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('DEV-DRV-01').first);
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('Vùng phụ trách'), findsOneWidget);
    expect(find.text('Ho Chi Minh City (dev)'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });
}
