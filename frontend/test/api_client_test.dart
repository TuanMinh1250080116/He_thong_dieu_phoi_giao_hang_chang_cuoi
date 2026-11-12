import 'dart:io';

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:logistics_app/api/api_client.dart';
import 'package:logistics_app/api/error_text.dart';

http.Response json(int status, Object body, {Map<String, String> headers = const {}}) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json', ...headers},
    );

void main() {
  test('getIfChanged sends If-None-Match and returns null on 304', () async {
    final seen = <String?>[];
    final client = ApiClient(
      baseUrl: 'http://test/api/v1',
      tokenStore: MemoryTokenStore(),
      httpClient: MockClient((request) async {
        seen.add(request.headers['If-None-Match']);
        if (request.headers['If-None-Match'] == '"abc"') {
          return http.Response('', 304, headers: {'etag': '"abc"'});
        }
        return json(200, {'panels': {}}, headers: {'etag': '"abc"'});
      }),
    );

    final first = await client.getIfChanged('/dispatch/board');
    final second = await client.getIfChanged('/dispatch/board', etag: first!.etag);

    expect(first.etag, '"abc"');
    expect(second, isNull);
    expect(seen, [null, '"abc"']);
  });

  test('login keeps the access token and stores the refresh cookie', () async {
    final store = MemoryTokenStore();
    final client = ApiClient(
      baseUrl: 'http://test/api/v1',
      tokenStore: store,
      httpClient: MockClient((request) async {
        expect(request.url.path, '/api/v1/auth/login');
        return json(
          200,
          {
            'accessToken': 'access-1',
            'user': {'id': 'u1', 'email': 'a@b.c', 'displayName': 'A', 'roles': ['DRIVER']},
          },
          headers: {'set-cookie': 'refresh_token=refresh-1; Path=/api/v1/auth; HttpOnly; SameSite=Strict'},
        );
      }),
    );

    final user = await client.login('a@b.c', 'pw');

    expect(user['roles'], ['DRIVER']);
    expect(client.accessToken, 'access-1');
    expect(await store.readRefreshToken(), 'refresh-1');
  });

  test('a 401 triggers one refresh and the request is retried with the same Idempotency-Key', () async {
    final store = MemoryTokenStore();
    await store.writeRefreshToken('refresh-1');
    final seenKeys = <String?>[];
    var refreshCalls = 0;
    final client = ApiClient(
      baseUrl: 'http://test/api/v1',
      tokenStore: store,
      httpClient: MockClient((request) async {
        if (request.url.path.endsWith('/auth/refresh')) {
          refreshCalls++;
          expect(request.headers['X-Requested-With'], 'fetch');
          expect(request.headers['Cookie'], 'refresh_token=refresh-1');
          return json(200, {'accessToken': 'access-2'},
              headers: {'set-cookie': 'refresh_token=refresh-2; Path=/; HttpOnly'});
        }
        seenKeys.add(request.headers['Idempotency-Key']);
        if (request.headers['Authorization'] != 'Bearer access-2') {
          return json(401, {'code': 'AUTHENTICATION_REQUIRED', 'title': 'Unauthorized'});
        }
        return json(201, {'data': {'id': 'o1'}}, headers: {'etag': '"0"'});
      }),
    );
    client.accessToken = 'expired';

    final result = await client.post('/orders', body: {'a': 1}, idempotent: true);

    expect(result.etag, '"0"');
    expect(refreshCalls, 1);
    expect(seenKeys, hasLength(2));
    expect(seenKeys[0], isNotNull);
    expect(seenKeys[0], seenKeys[1]);
    expect(await store.readRefreshToken(), 'refresh-2');
  });

  test('problem+json becomes an ApiException with the stable code and version', () async {
    final client = ApiClient(
      baseUrl: 'http://test/api/v1',
      tokenStore: MemoryTokenStore(),
      httpClient: MockClient((request) async => json(409, {
            'code': 'VERSION_CONFLICT',
            'detail': 'stale',
            'currentVersion': 7,
          })),
    );

    await expectLater(
      client.get('/orders/1'),
      throwsA(isA<ApiException>()
          .having((e) => e.code, 'code', 'VERSION_CONFLICT')
          .having((e) => e.currentVersion, 'currentVersion', 7)),
    );
  });

  test('error messages are friendly and list what is missing', () {
    expect(
      describeError(ApiException(status: 422, code: 'POD_POLICY_NOT_SATISFIED', message: 'x',
          details: {'missing': ['OTP', 'PHOTO']})),
      contains('OTP, PHOTO'),
    );
    expect(describeError(ApiException(status: 409, code: 'RECOMMENDATION_EXPIRED', message: 'x')),
        contains('hết hạn'));
    expect(describeError(const SocketException('refused')), contains('Không kết nối'));
    expect(describeError(Exception('bad data')), contains('Lỗi trong ứng dụng'));
    expect(describeError(ApiException(status: 404, code: 'HTTP_404', message: 'No static resource api/v1/x.')),
        contains('bản cũ'));
  });

  test('idempotency keys are unique version 4 UUIDs', () {
    final a = newIdempotencyKey();
    final b = newIdempotencyKey();

    expect(a, isNot(b));
    expect(a, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
  });
}
