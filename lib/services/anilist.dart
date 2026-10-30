import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../core/config.dart';

/* ------------------------------------------------------------------------------------------
 * AniList GraphQL client. Same queries as the website. Questions go to the site's cached door
 * (/api/anilist, backed by Redis) first and fall back to AniList itself.
 * ---------------------------------------------------------------------------------------- */

const _endpoint = 'https://graphql.anilist.co';

class Titles {
  Titles({this.romaji, this.english, this.native});
  final String? romaji;
  final String? english;
  final String? native;
  factory Titles.from(Map<String, dynamic>? j) => Titles(
        romaji: j?['romaji'] as String?,
        english: j?['english'] as String?,
        native: j?['native'] as String?,
      );
}

class Anime {
  Anime({
    required this.id,
    required this.title,
    this.cover,
    this.coverLarge,
    this.color,
    this.banner,
    this.description,
    this.format,
    this.episodes,
    this.seasonYear,
    this.genres = const [],
    this.averageScore,
    this.status,
    this.nextEpisode,
    this.synonyms = const [],
    this.popularity,
  });

  final int id;
  final Titles title;
  final String? cover; // extraLarge
  final String? coverLarge;
  final String? color;
  final String? banner;
  final String? description;
  final String? format;
  final int? episodes;
  final int? seasonYear;
  final List<String> genres;
  final int? averageScore;
  final String? status;
  final int? nextEpisode;
  final List<String> synonyms;
  final int? popularity;

  String get name => title.english ?? title.romaji ?? 'Untitled';
  String? get poster => cover ?? coverLarge;
  String? get thumb => coverLarge ?? cover;

  /// Episodes you can watch now: aired so far for a running show, otherwise the total.
  int get aired => status == 'RELEASING' && nextEpisode != null ? (nextEpisode! - 1).clamp(0, 100000).toInt() : (episodes ?? 0);

  String get formatLabel => format == null ? 'TV' : (format == 'TV_SHORT' ? 'TV' : format!.replaceAll('_', ' '));

  factory Anime.fromJson(Map<String, dynamic> j) {
    final c = (j['coverImage'] as Map<String, dynamic>?) ?? const {};
    final next = j['nextAiringEpisode'] as Map<String, dynamic>?;
    return Anime(
      id: j['id'] as int,
      title: Titles.from(j['title'] as Map<String, dynamic>?),
      cover: c['extraLarge'] as String?,
      coverLarge: c['large'] as String?,
      color: c['color'] as String?,
      banner: j['bannerImage'] as String?,
      description: j['description'] as String?,
      format: j['format'] as String?,
      episodes: j['episodes'] as int?,
      seasonYear: j['seasonYear'] as int?,
      genres: ((j['genres'] as List?) ?? const []).whereType<String>().toList(),
      averageScore: j['averageScore'] as int?,
      status: j['status'] as String?,
      nextEpisode: next?['episode'] as int?,
      synonyms: ((j['synonyms'] as List?) ?? const []).whereType<String>().toList(),
      popularity: j['popularity'] as int?,
    );
  }
}

class LatestEpisode {
  LatestEpisode({required this.id, required this.airingAt, required this.episode, required this.media});
  final int id;
  final int airingAt;
  final int episode;
  final Anime media;
}

class FuzzyDate {
  FuzzyDate(this.year, this.month, this.day);
  final int? year;
  final int? month;
  final int? day;
  factory FuzzyDate.from(Map<String, dynamic>? j) => FuzzyDate(j?['year'] as int?, j?['month'] as int?, j?['day'] as int?);
  bool get empty => year == null;
  @override
  String toString() {
    if (year == null) return '?';
    const m = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    final mo = month != null && month! >= 1 && month! <= 12 ? '${m[month! - 1]} ' : '';
    final d = day != null ? '$day, ' : '';
    return '$mo$d$year';
  }
}

class Relation {
  Relation({required this.type, required this.media, this.mediaType, this.year});
  final String type;
  final Anime media;
  final String? mediaType;
  final int? year;
}

class Detail {
  Detail({
    required this.anime,
    this.idMal,
    this.duration,
    this.season,
    required this.start,
    required this.end,
    this.meanScore,
    this.studio,
    this.trailerId,
    this.trailerSite,
    this.relations = const [],
    this.recommendations = const [],
    this.siteUrl,
  });
  final Anime anime;
  final int? idMal;
  final int? duration;
  final String? season;
  final FuzzyDate start;
  final FuzzyDate end;
  final int? meanScore;
  final String? studio;
  final String? trailerId;
  final String? trailerSite;
  final List<Relation> relations;
  final List<Anime> recommendations;
  final String? siteUrl;
}

/// Episodes aired so far, a readable format name, and so on.
String formatName(String? f) {
  const m = {
    'TV': 'TV Series',
    'TV_SHORT': 'TV Short',
    'MOVIE': 'Movie',
    'SPECIAL': 'Special',
    'OVA': 'OVA',
    'ONA': 'ONA',
    'MUSIC': 'Music',
  };
  return m[f] ?? (f ?? 'Unknown');
}

String statusName(String? s) {
  const m = {
    'FINISHED': 'Finished',
    'RELEASING': 'Airing',
    'NOT_YET_RELEASED': 'Upcoming',
    'CANCELLED': 'Cancelled',
    'HIATUS': 'On hiatus',
  };
  return m[s] ?? (s ?? 'Unknown');
}

String timeAgo(int unix) {
  final s = (DateTime.now().millisecondsSinceEpoch / 1000 - unix).clamp(0, double.infinity).toDouble();
  if (s < 3600) return '${(s / 60).floor().clamp(1, 59)}m ago';
  if (s < 86400) return '${(s / 3600).floor()}h ago';
  return '${(s / 86400).floor()}d ago';
}

String stripHtml(String s) => s.replaceAll(RegExp(r'<[^>]*>'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim();

/// A real answer from AniList (such as "not found"): retrying would not help.
class _Definite implements Exception {
  _Definite(this.message);
  final String message;
}

class AniList {
  AniList._();
  static final AniList instance = AniList._();

  static const _memoryTtl = Duration(minutes: 5);
  final Map<String, ({DateTime at, Map<String, dynamic> data})> _memory = {};
  final Map<String, Future<Map<String, dynamic>>> _inflight = {};
  int _active = 0;
  final List<Completer<void>> _waiting = [];

  Future<void> _acquire() async {
    if (_active >= 4) {
      final c = Completer<void>();
      _waiting.add(c);
      await c.future;
    }
    _active++;
  }

  void _release() {
    _active--;
    if (_waiting.isNotEmpty) _waiting.removeAt(0).complete();
  }

  /// Your site's cached copy of AniList (Redis). Usually the fastest door, and it works when AniList blocks this phone.
  Future<Map<String, dynamic>> _viaSite(String body) async {
    final res = await http
        .post(Uri.parse('${AppConfig.siteUrl}/api/anilist'), headers: {'Content-Type': 'application/json'}, body: body)
        .timeout(const Duration(seconds: 14));
    final json = jsonDecode(res.body);
    if (res.statusCode == 200 && json is Map && json['data'] is Map) return Map<String, dynamic>.from(json['data'] as Map);
    throw Exception('site ${res.statusCode}');
  }

  /// AniList asked directly from the phone.
  Future<Map<String, dynamic>> _direct(String body) async {
    await _acquire();
    try {
      final res = await http
          .post(Uri.parse(_endpoint), headers: {'Content-Type': 'application/json', 'Accept': 'application/json'}, body: body)
          .timeout(const Duration(seconds: 14));
      if (res.statusCode == 200) {
        final json = jsonDecode(res.body) as Map<String, dynamic>;
        final errors = json['errors'] as List?;
        if (errors != null && errors.isNotEmpty) throw _Definite((errors.first as Map)['message'] ?? 'AniList error');
        return Map<String, dynamic>.from(json['data'] as Map);
      }
      if (res.statusCode == 404) throw _Definite('Not found.');
      throw Exception(res.statusCode == 429 ? 'AniList is busy right now. Try again in a moment.' : 'AniList error ${res.statusCode}');
    } finally {
      _release();
    }
  }

  /// Both doors are opened at the same moment and the first good answer wins, so a slow or blocked one never holds the screen.
  /// A failed round is repeated once.
  Future<Map<String, dynamic>> _request(String body) async {
    Object? last;
    for (var round = 0; round < 2; round++) {
      final done = Completer<Map<String, dynamic>>();
      var left = 2;
      void fail(Object e) {
        last = e;
        if (e is _Definite && !done.isCompleted) {
          done.completeError(e);
          return;
        }
        if (--left == 0 && !done.isCompleted) done.completeError(e);
      }

      _viaSite(body).then((d) {
        if (!done.isCompleted) done.complete(d);
      }, onError: fail);
      _direct(body).then((d) {
        if (!done.isCompleted) done.complete(d);
      }, onError: fail);
      try {
        return await done.future;
      } catch (e) {
        last = e;
        if (e is _Definite) break;
        await Future<void>.delayed(const Duration(milliseconds: 600));
      }
    }
    if (last is _Definite) throw Exception((last as _Definite).message);
    throw Exception("Couldn't reach AniList. Check your connection and try again.");
  }

  /* ---- persistent cache: the last good answer is kept on the phone so the app opens instantly ---- */

  static String _hash(String s) {
    var h = 5381;
    for (final c in s.codeUnits) {
      h = ((h << 5) + h + c) & 0x7fffffff;
    }
    return '${h.toRadixString(36)}${s.length.toRadixString(36)}';
  }

  Future<({DateTime at, Map<String, dynamic> data})?> _readStore(String body) async {
    try {
      final p = await SharedPreferences.getInstance();
      final raw = p.getString('al:${_hash(body)}');
      if (raw == null) return null;
      final j = jsonDecode(raw) as Map<String, dynamic>;
      return (at: DateTime.fromMillisecondsSinceEpoch(j['t'] as int), data: Map<String, dynamic>.from(j['d'] as Map));
    } catch (_) {
      return null;
    }
  }

  Future<void> _writeStore(String body, Map<String, dynamic> data) async {
    try {
      final raw = jsonEncode({'t': DateTime.now().millisecondsSinceEpoch, 'd': data});
      if (raw.length > 300000) return;
      final p = await SharedPreferences.getInstance();
      await p.setString('al:${_hash(body)}', raw);
    } catch (_) {}
  }

  Future<Map<String, dynamic>> query(String q, Map<String, dynamic> variables) async {
    final body = jsonEncode({'query': q, 'variables': variables});
    final hit = _memory[body];
    if (hit != null && DateTime.now().difference(hit.at) < _memoryTtl) return hit.data;
    final stored = await _readStore(body);
    if (stored != null && DateTime.now().difference(stored.at) < _memoryTtl) {
      _memory[body] = stored;
      return stored.data;
    }
    final pending = _inflight[body] ??= _request(body).then((data) {
      _memory[body] = (at: DateTime.now(), data: data);
      _writeStore(body, data);
      return data;
    }).catchError((Object e) {
      // Better to show slightly old data than an error.
      final old = _memory[body] ?? stored;
      if (old != null) return old.data;
      throw e;
    }).whenComplete(() => _inflight.remove(body));
    // Older than 5 minutes but still on the phone: show it now and refresh in the background.
    if (stored != null && DateTime.now().difference(stored.at) < const Duration(days: 2)) {
      unawaited(pending.then((_) {}, onError: (Object _) {}));
      return stored.data;
    }
    return pending;
  }

  static const _media = '''
    id
    title { romaji english }
    coverImage { extraLarge large color }
    format episodes seasonYear isAdult
  ''';

  Future<({List<LatestEpisode> items, int lastPage})> latestEpisodes(int page) async {
    final data = await query('''
      query (\$page: Int) {
        Page(page: \$page, perPage: 12) {
          pageInfo { lastPage }
          airingSchedules(notYetAired: false, sort: TIME_DESC) {
            id airingAt episode
            media { $_media }
          }
        }
      }''', {'page': page});
    final pg = data['Page'] as Map<String, dynamic>;
    final seen = <int>{};
    final items = <LatestEpisode>[];
    for (final s in (pg['airingSchedules'] as List)) {
      final m = s['media'] as Map<String, dynamic>;
      if (m['isAdult'] == true || !seen.add(m['id'] as int)) continue;
      items.add(LatestEpisode(id: s['id'] as int, airingAt: s['airingAt'] as int, episode: s['episode'] as int, media: Anime.fromJson(m)));
    }
    return (items: items, lastPage: ((pg['pageInfo'] as Map)['lastPage'] as int?) ?? 1);
  }

  Future<List<Anime>> trending() async {
    final data = await query('''
      query {
        Page(page: 1, perPage: 8) {
          media(type: ANIME, sort: TRENDING_DESC, isAdult: false, status_in: [RELEASING, FINISHED]) {
            $_media
            bannerImage description(asHtml: false) genres
            status averageScore nextAiringEpisode { episode }
          }
        }
      }''', {});
    final list = ((data['Page'] as Map)['media'] as List).map((e) => Anime.fromJson(e as Map<String, dynamic>)).toList();
    return list.where((m) => m.banner != null || m.cover != null).toList();
  }

  Future<List<Anime>> _ranked(String status) async {
    final data = await query('''
      query (\$status: MediaStatus) {
        Page(page: 1, perPage: 10) {
          media(type: ANIME, status: \$status, sort: POPULARITY_DESC, isAdult: false) {
            $_media
            bannerImage averageScore nextAiringEpisode { episode } status
          }
        }
      }''', {'status': status});
    return ((data['Page'] as Map)['media'] as List).map((e) => Anime.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Anime>> topAiring() => _ranked('RELEASING');
  Future<List<Anime>> upcoming() => _ranked('NOT_YET_RELEASED');

  Future<({List<Anime> items, bool hasNext})> searchPage(String search, int page, int perPage) async {
    final data = await query('''
      query (\$search: String, \$page: Int, \$perPage: Int) {
        Page(page: \$page, perPage: \$perPage) {
          pageInfo { hasNextPage }
          media(type: ANIME, search: \$search, isAdult: false, sort: SEARCH_MATCH) {
            $_media
            status averageScore nextAiringEpisode { episode }
          }
        }
      }''', {'search': search, 'page': page, 'perPage': perPage});
    final pg = data['Page'] as Map<String, dynamic>;
    return (
      items: (pg['media'] as List).map((e) => Anime.fromJson(e as Map<String, dynamic>)).toList(),
      hasNext: (pg['pageInfo'] as Map)['hasNextPage'] == true,
    );
  }

  static String normalize(String s) => s
      .toLowerCase()
      .replaceAll(RegExp(r'[̀-ͯ]'), '')
      .replaceAll(RegExp(r'[×✕]'), 'x')
      .replaceAll(RegExp(r'[^\p{L}\p{N}]+', unicode: true), ' ')
      .trim();

  double _score(Anime hit, String term, List<String> tokens) {
    final titles = [hit.title.english, hit.title.romaji, hit.title.native, ...hit.synonyms].whereType<String>().map(normalize);
    double best = 0;
    for (final t in titles) {
      double s = 0;
      if (t == term) {
        s = 100;
      } else if (t.startsWith(term)) {
        s = 80;
      } else if (t.split(' ').any((w) => w.startsWith(tokens.isNotEmpty ? tokens.first : term)) && tokens.every(t.contains)) {
        s = 65;
      } else if (t.contains(term)) {
        s = 55;
      } else if (tokens.length > 1 && tokens.every(t.contains)) {
        s = 45;
      } else if (tokens.any(t.contains)) {
        s = 15;
      }
      if (s > best) best = s;
    }
    final pop = ((hit.popularity ?? 0) + 1).toDouble();
    final bonus = (math.log(pop) / math.ln10 * 2).clamp(0.0, 10.0).toDouble();
    return best + bonus;
  }

  /// "Type a few letters, find the show": ranks exact and prefix title matches first, popularity breaks ties.
  Future<List<Anime>> searchSmart(String raw, int limit) async {
    final term = normalize(raw);
    if (term.isEmpty) return [];
    final tokens = term.split(' ').where((e) => e.isNotEmpty).toList();
    Future<List<Anime>> run(String q) async {
      final data = await query('''
        query (\$search: String) {
          Page(page: 1, perPage: 12) {
            media(type: ANIME, search: \$search, isAdult: false, sort: SEARCH_MATCH) {
              $_media
              title { romaji english native }
              synonyms popularity status averageScore nextAiringEpisode { episode }
            }
          }
        }''', {'search': q});
      return ((data['Page'] as Map)['media'] as List).map((e) => Anime.fromJson(e as Map<String, dynamic>)).toList();
    }

    var hits = await run(raw.trim());
    if (hits.isEmpty && tokens.length > 1) hits = await run(tokens.sublist(0, tokens.length - 1).join(' '));
    final scored = <(Anime, double)>[];
    for (var i = 0; i < hits.length; i++) {
      scored.add((hits[i], _score(hits[i], term, tokens) - i * 0.01));
    }
    scored.sort((a, b) => b.$2.compareTo(a.$2));
    return scored.take(limit).map((e) => e.$1).toList();
  }

  Future<Detail?> detail(int id) async {
    const mini = 'id title { romaji english } coverImage { extraLarge large color } format episodes seasonYear';
    final data = await query('''
      query (\$id: Int) {
        Media(id: \$id, type: ANIME) {
          $mini
          idMal bannerImage description(asHtml: false) genres status duration season siteUrl
          title { romaji english native }
          startDate { year month day } endDate { year month day }
          averageScore meanScore popularity nextAiringEpisode { episode }
          studios { edges { isMain node { name } } }
          trailer { id site thumbnail }
          relations { edges { relationType(version: 2) node { $mini type startDate { year } } } }
          recommendations(sort: RATING_DESC, perPage: 12) {
            nodes { mediaRecommendation { $mini isAdult } }
          }
        }
      }''', {'id': id});
    final m = data['Media'] as Map<String, dynamic>?;
    if (m == null) return null;
    String? studio;
    final edges = ((m['studios'] as Map?)?['edges'] as List?) ?? const [];
    for (final e in edges) {
      if (e['isMain'] == true) studio = (e['node'] as Map)['name'] as String?;
    }
    studio ??= edges.isNotEmpty ? ((edges.first as Map)['node'] as Map)['name'] as String? : null;
    final trailer = m['trailer'] as Map<String, dynamic>?;
    final relations = <Relation>[];
    for (final e in (((m['relations'] as Map?)?['edges'] as List?) ?? const [])) {
      final n = e['node'] as Map<String, dynamic>;
      if (n['type'] != 'ANIME') continue;
      relations.add(Relation(type: (e['relationType'] as String? ?? '').replaceAll('_', ' '), media: Anime.fromJson(n), mediaType: n['type'] as String?, year: (n['startDate'] as Map?)?['year'] as int?));
    }
    final recs = <Anime>[];
    for (final n in (((m['recommendations'] as Map?)?['nodes'] as List?) ?? const [])) {
      final r = n['mediaRecommendation'] as Map<String, dynamic>?;
      if (r == null || r['isAdult'] == true) continue;
      recs.add(Anime.fromJson(r));
    }
    return Detail(
      anime: Anime.fromJson(m),
      idMal: m['idMal'] as int?,
      duration: m['duration'] as int?,
      season: m['season'] as String?,
      start: FuzzyDate.from(m['startDate'] as Map<String, dynamic>?),
      end: FuzzyDate.from(m['endDate'] as Map<String, dynamic>?),
      meanScore: m['meanScore'] as int?,
      studio: studio,
      trailerId: trailer?['id'] as String?,
      trailerSite: trailer?['site'] as String?,
      relations: relations,
      recommendations: recs,
      siteUrl: m['siteUrl'] as String?,
    );
  }
}
