import 'dart:convert';

import 'package:http/http.dart' as http;

import '../core/config.dart';
import 'cache.dart';

/// Title logos and artwork from TMDB, the same source and the same picking rules as the website (lib/logos.ts).
/// Needs TMDB_KEY at build time; without it the hero simply shows the plain text title.
class Artwork {
  const Artwork(this.kind, this.url);
  final String kind; // Backgrounds | Banners | Posters | Logos
  final String url;
}

String _fnv(String s) {
  var h = 0x811c9dc5;
  for (final c in s.codeUnits) {
    h = ((h ^ c) * 0x01000193) & 0xFFFFFFFF;
  }
  return h.toRadixString(36);
}

class Tmdb {
  Tmdb._();
  static final Tmdb instance = Tmdb._();

  static bool get enabled => AppConfig.tmdbKey.isNotEmpty;

  final Map<String, String?> _logos = {};
  final Map<String, Future<String?>> _logoInflight = {};
  final Map<String, List<Artwork>> _art = {};

  Future<dynamic> _get(String path, [Map<String, String> params = const {}]) async {
    try {
      final uri = Uri.https('api.themoviedb.org', '/3/$path', {...params, 'api_key': AppConfig.tmdbKey});
      final res = await http.get(uri, headers: {'Accept': 'application/json'}).timeout(const Duration(seconds: 10));
      return res.statusCode == 200 ? jsonDecode(res.body) : null;
    } catch (_) {
      return null;
    }
  }

  Future<({String kind, int id})?> _find(String name, bool movie) async {
    for (final kind in movie ? const ['movie', 'tv'] : const ['tv', 'movie']) {
      final j = await _get('search/$kind', {'query': name});
      final r = j is Map ? j['results'] : null;
      if (r is List && r.isNotEmpty && r.first is Map && (r.first as Map)['id'] is num) return (kind: kind, id: ((r.first as Map)['id'] as num).toInt());
    }
    return null;
  }

  /// The best English logo (highest vote, then widest), or any logo when there is no English one.
  Future<String?> logo(String name, bool movie) {
    if (!enabled || name.trim().isEmpty) return Future.value(null);
    final key = '${movie ? 'm' : 't'}:$name';
    if (_logos.containsKey(key)) return Future.value(_logos[key]);
    return _logoInflight[key] ??= _loadLogo(key, name, movie).whenComplete(() => _logoInflight.remove(key));
  }

  Future<String?> _loadLogo(String key, String name, bool movie) async {
    final ck = 'logo:${_fnv(key)}';
    final hit = await AppCache.instance.read(ck);
    if (hit != null && DateTime.now().difference(hit.at).inDays < 7 && hit.data is Map) {
      final u = (hit.data as Map)['u'];
      return _logos[key] = u is String && u.isNotEmpty ? u : null;
    }
    String? url;
    var failed = false;
    for (final kind in movie ? const ['movie', 'tv'] : const ['tv', 'movie']) {
      final s = await _get('search/$kind', {'query': name});
      if (s == null) failed = true;
      final r = s is Map ? s['results'] : null;
      final id = r is List && r.isNotEmpty && r.first is Map ? (r.first as Map)['id'] : null;
      if (id is! num) continue;
      final img = await _get('$kind/${id.toInt()}/images', {'include_image_language': 'en,ja,null'});
      if (img == null) failed = true;
      final logos = img is Map ? (img['logos'] as List?)?.whereType<Map>().toList() : null;
      if (logos == null || logos.isEmpty) continue;
      num n(dynamic v) => v is num ? v : 0;
      final en = logos.where((l) => l['iso_639_1'] == 'en').toList()
        ..sort((a, b) {
          final v = n(b['vote_average']).compareTo(n(a['vote_average']));
          return v != 0 ? v : n(b['width']).compareTo(n(a['width']));
        });
      final best = en.isNotEmpty ? en.first : logos.first;
      if (best['file_path'] is String) {
        url = 'https://image.tmdb.org/t/p/w500${best['file_path']}';
        break;
      }
    }
    // don't remember a miss that was only a network hiccup
    if (url != null || !failed) {
      _logos[key] = url;
      AppCache.instance.write(ck, {'u': url ?? ''}, ttlSeconds: 7 * 86400);
    }
    return url;
  }

  /// Backgrounds, posters and logos for the info page gallery.
  Future<List<Artwork>> artwork(String name, bool movie) async {
    if (!enabled || name.trim().isEmpty) return const [];
    final key = '${movie ? 'm' : 't'}:$name';
    final have = _art[key];
    if (have != null) return have;
    final ck = 'art:${_fnv(key)}';
    final hit = await AppCache.instance.read(ck);
    if (hit != null && DateTime.now().difference(hit.at).inDays < 7 && hit.data is List) {
      return _art[key] = [for (final e in (hit.data as List).whereType<Map>()) Artwork('${e['k']}', '${e['u']}')];
    }
    final found = await _find(name, movie);
    if (found == null) return const [];
    final j = await _get('${found.kind}/${found.id}/images');
    if (j is! Map) return const [];
    List<Artwork> pick(String field, String kind, String size) => [
          for (final i in (j[field] as List?)?.whereType<Map>() ?? const <Map>[])
            if (i['file_path'] is String) Artwork(kind, 'https://image.tmdb.org/t/p/$size${i['file_path']}'),
        ];
    final out = [...pick('backdrops', 'Backgrounds', 'w780'), ...pick('posters', 'Posters', 'w500'), ...pick('logos', 'Logos', 'w500')];
    if (out.isNotEmpty) {
      _art[key] = out;
      AppCache.instance.write(ck, [for (final a in out) {'k': a.kind, 'u': a.url}], ttlSeconds: 7 * 86400);
    }
    return out;
  }
}
