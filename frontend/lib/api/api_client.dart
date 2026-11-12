import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

class ApiException implements Exception {
  ApiException({
    required this.status,
    required this.code,
    required this.message,
    this.details = const {},
    this.errors = const [],
    this.currentVersion,
  });

  final int status;
  final String code;
  final String message;
  final Map<String, dynamic> details;
  final List<Map<String, dynamic>> errors;
  final int? currentVersion;

  @override
  String toString() => '$code: $message';
}

class ApiResult {
  ApiResult(this.body, this.etag, this.replayed);

  final dynamic body;
  final String? etag;
  final bool replayed;

  Map<String, dynamic> get map => body as Map<String, dynamic>;
}

abstract class TokenStore {
  Future<String?> readRefreshToken();
  Future<void> writeRefreshToken(String? token);
  Future<bool> readRemember() async => true;
  Future<void> writeRemember(bool value) async {}
}

class SecureTokenStore implements TokenStore {
  static const _key = 'refresh_token';
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  @override
  Future<String?> readRefreshToken() => _storage.read(key: _key);

  @override
  Future<void> writeRefreshToken(String? token) async {
    if (token == null || token.isEmpty) {
      await _storage.delete(key: _key);
    } else {
      await _storage.write(key: _key, value: token);
    }
  }

  @override
  Future<bool> readRemember() async => (await _storage.read(key: 'remember')) != '0';

  @override
  Future<void> writeRemember(bool value) => _storage.write(key: 'remember', value: value ? '1' : '0');
}

class MemoryTokenStore implements TokenStore {
  String? _token;

  @override
  Future<String?> readRefreshToken() async => _token;

  @override
  Future<void> writeRefreshToken(String? token) async => _token = token;

  bool _remember = true;

  @override
  Future<bool> readRemember() async => _remember;

  @override
  Future<void> writeRemember(bool value) async => _remember = value;
}

String newIdempotencyKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}

class ApiClient {
  ApiClient({required this.baseUrl, required this.tokenStore, http.Client? httpClient})
      : _http = httpClient ?? http.Client();

  final String baseUrl;
  final TokenStore tokenStore;
  final http.Client _http;

  String? accessToken;
  void Function()? onSessionExpired;
  Future<bool>? _refreshing;

  Uri _uri(String path, Map<String, String>? query) {
    final uri = Uri.parse('$baseUrl$path');
    return query == null || query.isEmpty ? uri : uri.replace(queryParameters: query);
  }

  Map<String, String> _headers({
    required bool auth,
    String? ifMatch,
    String? idempotencyKey,
    bool json = true,
  }) {
    return {
      'Accept': 'application/json',
      if (json) 'Content-Type': 'application/json',
      if (auth && accessToken != null) 'Authorization': 'Bearer $accessToken',
      'If-Match': ?ifMatch,
      'Idempotency-Key': ?idempotencyKey,
    };
  }

  Future<ApiResult> send(
    String method,
    String path, {
    Object? body,
    String? ifMatch,
    bool idempotent = false,
    String? idempotencyKey,
    Map<String, String>? query,
    bool auth = true,
  }) async {
    final key = idempotencyKey ?? (idempotent ? newIdempotencyKey() : null);
    final response = await _withRefresh(auth, () {
      final request = http.Request(method, _uri(path, query))
        ..headers.addAll(_headers(auth: auth, ifMatch: ifMatch, idempotencyKey: key));
      if (body != null) {
        request.body = jsonEncode(body);
      } else if (method == 'POST') {
        request.body = '';
      }
      return _http.send(request).then(http.Response.fromStream);
    });
    return _toResult(response);
  }

  Future<ApiResult> get(String path, {Map<String, String>? query}) => send('GET', path, query: query);

  Future<ApiResult> post(String path,
          {Object? body, String? ifMatch, bool idempotent = false, String? idempotencyKey}) =>
      send('POST', path, body: body, ifMatch: ifMatch, idempotent: idempotent, idempotencyKey: idempotencyKey);

  Future<ApiResult?> getIfChanged(String path, {Map<String, String>? query, String? etag}) async {
    final response = await _withRefresh(true, () {
      final request = http.Request('GET', _uri(path, query))
        ..headers.addAll({..._headers(auth: true), 'If-None-Match': ?etag});
      return _http.send(request).then(http.Response.fromStream);
    });
    if (response.statusCode == 304) {
      return null;
    }
    return _toResult(response);
  }

  Future<ApiResult> put(String path, {Object? body, String? ifMatch}) =>
      send('PUT', path, body: body, ifMatch: ifMatch);

  Future<ApiResult> patch(String path, {Object? body, String? ifMatch}) =>
      send('PATCH', path, body: body, ifMatch: ifMatch);

  Future<void> putBytes(String absolutePath, Uint8List bytes, String contentType) async {
    final uri = Uri.parse(baseUrl).replace(path: absolutePath);
    final response = await _withRefresh(true, () {
      final headers = _headers(auth: true, json: false)..['Content-Type'] = contentType;
      return _http.put(uri, headers: headers, body: bytes);
    });
    _toResult(response);
  }

  Future<Map<String, dynamic>> login(String identifier, String password, {bool remember = true}) async {
    final response = await _http.post(
      _uri('/auth/login', null),
      headers: _headers(auth: false),
      body: jsonEncode({'identifier': identifier, 'password': password}),
    );
    final result = _toResult(response);
    accessToken = result.map['accessToken'] as String;
    await _captureRefreshCookie(response);
    await tokenStore.writeRemember(remember);
    sessionStartedAt = DateTime.now();
    return result.map['user'] as Map<String, dynamic>;
  }

  DateTime? sessionStartedAt;
  DateTime? lastRefreshedAt;

  Future<bool> restoreSession() async {
    if (await tokenStore.readRefreshToken() == null) {
      return false;
    }
    if (!await tokenStore.readRemember()) {
      await tokenStore.writeRefreshToken(null);
      return false;
    }
    return _refreshOnce();
  }

  Future<bool> refreshSession() => _refreshOnce();

  Future<void> logout() async {
    try {
      final refresh = await tokenStore.readRefreshToken();
      await _http.post(
        _uri('/auth/logout', null),
        headers: {
          ..._headers(auth: true),
          if (refresh != null) 'Cookie': 'refresh_token=$refresh',
        },
      );
    } catch (_) {
    }
    accessToken = null;
    await tokenStore.writeRefreshToken(null);
  }

  Future<http.Response> _withRefresh(bool auth, Future<http.Response> Function() call) async {
    var response = await call();
    if (response.statusCode == 401 && auth && await tokenStore.readRefreshToken() != null) {
      if (await _refreshOnce()) {
        response = await call();
      }
    }
    return response;
  }

  Future<bool> _refreshOnce() {
    return _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);
  }

  Future<bool> _doRefresh() async {
    final refresh = await tokenStore.readRefreshToken();
    if (refresh == null) {
      return false;
    }
    final response = await _http.post(
      _uri('/auth/refresh', null),
      headers: {
        ..._headers(auth: false),
        'X-Requested-With': 'fetch',
        'Cookie': 'refresh_token=$refresh',
      },
    );
    if (response.statusCode != 200) {
      accessToken = null;
      await tokenStore.writeRefreshToken(null);
      onSessionExpired?.call();
      return false;
    }
    accessToken = (jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>)['accessToken'] as String;
    await _captureRefreshCookie(response);
    lastRefreshedAt = DateTime.now();
    return true;
  }

  Future<void> _captureRefreshCookie(http.Response response) async {
    final header = response.headers['set-cookie'];
    if (header == null) {
      return;
    }
    final match = RegExp(r'refresh_token=([^;,\s]*)').firstMatch(header);
    if (match != null) {
      await tokenStore.writeRefreshToken(match.group(1));
    }
  }

  ApiResult _toResult(http.Response response) {
    if (response.statusCode >= 400) {
      throw _problem(response);
    }
    final text = utf8.decode(response.bodyBytes);
    final body = text.isEmpty ? null : jsonDecode(text);
    return ApiResult(body, response.headers['etag'], response.headers['idempotent-replayed'] == 'true');
  }

  ApiException _problem(http.Response response) {
    try {
      final json = jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
      return ApiException(
        status: response.statusCode,
        code: (json['code'] as String?) ?? 'HTTP_${response.statusCode}',
        message: (json['detail'] as String?) ?? (json['title'] as String?) ?? 'Lỗi không xác định',
        details: (json['details'] as Map<String, dynamic>?) ?? const {},
        errors: ((json['errors'] as List?) ?? const []).cast<Map<String, dynamic>>(),
        currentVersion: (json['currentVersion'] as num?)?.toInt(),
      );
    } catch (_) {
      return ApiException(status: response.statusCode, code: 'HTTP_${response.statusCode}', message: 'Lỗi máy chủ');
    }
  }
}
