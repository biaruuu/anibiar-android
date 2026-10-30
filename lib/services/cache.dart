import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../core/config.dart';

dynamic _decode(String s) => jsonDecode(s);

class CacheHit {
  CacheHit(this.at, this.data);
  final DateTime at;
  final dynamic data;
}

/// Two layers, both optional and both fail-safe (a broken cache never breaks the app):
///   1. a file on the phone (instant, works offline)
///   2. Upstash Redis over its REST API, only when UPSTASH_REDIS_REST_URL and _TOKEN were given at build time
class AppCache {
  AppCache._();
  static final AppCache instance = AppCache._();

  static const _prefix = 'app:v1:';
  Directory? _dir;

  bool get upstashOn => AppConfig.upstashUrl.isNotEmpty && AppConfig.upstashToken.isNotEmpty;

  Future<Directory?> _folder() async {
    if (_dir != null) return _dir;
    try {
      final base = await getApplicationSupportDirectory();
      final d = Directory('${base.path}/cache');
      if (!await d.exists()) await d.create(recursive: true);
      return _dir = d;
    } catch (_) {
      return null;
    }
  }

  String _file(String key) => key.replaceAll(RegExp(r'[^A-Za-z0-9_.-]'), '_');

  Map<String, String> get _auth => {'Authorization': 'Bearer ${AppConfig.upstashToken}'};
  String get _base => AppConfig.upstashUrl.replaceAll(RegExp(r'/+$'), '');

  Future<CacheHit?> _parse(String raw) async {
    try {
      final j = (raw.length > 20000 ? await compute(_decode, raw) : jsonDecode(raw)) as Map;
      return CacheHit(DateTime.fromMillisecondsSinceEpoch((j['at'] as num).toInt()), j['d']);
    } catch (_) {
      return null;
    }
  }

  Future<CacheHit?> read(String key) async {
    try {
      final d = await _folder();
      if (d != null) {
        final f = File('${d.path}/${_file(key)}.json');
        if (await f.exists()) {
          final hit = await _parse(await f.readAsString());
          if (hit != null) return hit;
        }
      }
    } catch (_) {}
    if (!upstashOn) return null;
    try {
      final res = await http.get(Uri.parse('$_base/get/${Uri.encodeComponent('$_prefix$key')}'), headers: _auth).timeout(const Duration(seconds: 4));
      if (res.statusCode != 200) return null;
      final v = (jsonDecode(res.body) as Map)['result'];
      if (v is! String) return null;
      final hit = await _parse(v);
      if (hit != null) _toFile(key, v);
      return hit;
    } catch (_) {
      return null;
    }
  }

  Future<void> _toFile(String key, String raw) async {
    try {
      final d = await _folder();
      if (d != null) await File('${d.path}/${_file(key)}.json').writeAsString(raw, flush: true);
    } catch (_) {}
  }

  Future<void> write(String key, dynamic data, {int ttlSeconds = 86400}) async {
    final raw = jsonEncode({'at': DateTime.now().millisecondsSinceEpoch, 'd': data});
    await _toFile(key, raw);
    // Upstash caps a request body at about 1 MB
    if (!upstashOn || raw.length > 900000) return;
    try {
      await http
          .post(Uri.parse(_base), headers: {..._auth, 'Content-Type': 'application/json'}, body: jsonEncode(['SET', '$_prefix$key', raw, 'EX', ttlSeconds]))
          .timeout(const Duration(seconds: 6));
    } catch (_) {}
  }
}
