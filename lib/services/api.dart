import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:supabase_flutter/supabase_flutter.dart';

import '../core/config.dart';
import 'cache.dart';

/* ------------------------------------------------------------------------------------------
 * Everything here talks straight to the services the website uses, no website API in between:
 *   - episodes and streams: the Anivexa API
 *   - intro / outro times: AniSkip
 *   - avatar decorations: the Supabase table
 * Stream links need a Referer header; the player sends it itself, so no proxy is needed.
 * ---------------------------------------------------------------------------------------- */

class ApiException implements Exception {
  ApiException(this.message);
  final String message;
  @override
  String toString() => message;
}

class Range {
  const Range(this.start, this.end);
  final double start;
  final double end;
  static Range? from(dynamic j) {
    if (j is! Map) return null;
    final s = j['start'];
    final e = j['end'];
    if (s is num && e is num && e > s) return Range(s.toDouble(), e.toDouble());
    return null;
  }
}

class WatchEpisode {
  WatchEpisode({required this.number, required this.title, this.description, this.image, this.airDate, this.duration, this.filler = false, List<String>? providers}) : providers = providers ?? [];
  final int number;
  String title;
  String? description;
  String? image;
  String? airDate;
  int? duration;
  final bool filler;

  /// Providers that have this episode, in priority order.
  final List<String> providers;

  Map<String, dynamic> toJson() => {'n': number, 't': title, 'd': description, 'i': image, 'a': airDate, 'u': duration, 'f': filler, 'p': providers};
  factory WatchEpisode.fromJson(Map j) => WatchEpisode(
        number: (j['n'] as num).toInt(),
        title: j['t'] as String,
        description: j['d'] as String?,
        image: j['i'] as String?,
        airDate: j['a'] as String?,
        duration: (j['u'] as num?)?.toInt(),
        filler: j['f'] == true,
        providers: ((j['p'] as List?) ?? const []).cast<String>().toList(),
      );
}

class EpisodesPayload {
  EpisodesPayload({this.title, required this.sub, required this.dub});
  final String? title;
  final List<WatchEpisode> sub;
  final List<WatchEpisode> dub;

  Map<String, dynamic> toJson() => {'title': title, 'sub': sub.map((e) => e.toJson()).toList(), 'dub': dub.map((e) => e.toJson()).toList()};
  factory EpisodesPayload.fromJson(Map j) => EpisodesPayload(
        title: j['title'] as String?,
        sub: ((j['sub'] as List?) ?? const []).map((e) => WatchEpisode.fromJson(e as Map)).toList(),
        dub: ((j['dub'] as List?) ?? const []).map((e) => WatchEpisode.fromJson(e as Map)).toList(),
      );
}

class StreamSubtitle {
  StreamSubtitle({required this.label, required this.lang, required this.src, required this.isDefault, this.headers = const {}});
  final String label;
  final String lang;
  final String src;
  final bool isDefault;
  final Map<String, String> headers;
}

class StreamSource {
  StreamSource({required this.id, required this.name, required this.soft, required this.hls, required this.src, required this.headers, required this.subtitles, this.intro, this.outro});
  final String id;
  final String name;

  /// true = separate subtitle tracks, false = subtitles burned into the video.
  final bool soft;
  final bool hls;
  final String src;
  final Map<String, String> headers;
  final List<StreamSubtitle> subtitles;
  final Range? intro;
  final Range? outro;
}

class WatchPayload {
  WatchPayload({required this.streams, this.intro, this.outro});
  final List<StreamSource> streams;
  final Range? intro;
  final Range? outro;
}

class SkipTimes {
  const SkipTimes({this.intro, this.outro, this.length});
  final Range? intro;
  final Range? outro;
  final double? length;
}

class AvatarDeco {
  AvatarDeco({required this.id, required this.label, required this.sheet, required this.frames, required this.cols, required this.frameSize, required this.fps, required this.scale});
  final String id;
  final String label;
  final String sheet;
  final int frames;
  final int cols;
  final int frameSize;
  final double fps;
  final double scale;

  factory AvatarDeco.fromRow(Map<String, dynamic> j) => AvatarDeco(
        id: '${j['id']}',
        label: (j['label'] as String?) ?? '${j['id']}',
        // the artwork files are static assets of the website
        sheet: AppConfig.abs(j['sheet'] as String),
        frames: (j['frames'] as num).toInt(),
        cols: (j['cols'] as num).toInt(),
        frameSize: (j['frame_size'] as num).toInt(),
        fps: double.parse('${j['fps']}'),
        scale: double.parse('${j['scale']}'),
      );
}

dynamic _decode(String s) => jsonDecode(s);

const _ua = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

/// Invented names for the servers (the same idea as the website: real provider names are never shown).
const _names = ['Nova', 'Orion', 'Lyra', 'Atlas', 'Comet', 'Zephyr', 'Pulse', 'Ember', 'Echo', 'Halo', 'Vega', 'Aero'];
int _hash(String text) {
  var h = 5381;
  for (final c in text.codeUnits) {
    h = (h * 33 + c) & 0xFFFFFFFF;
  }
  return h;
}

String _nameFor(String provider, String server, Set<String> taken) {
  var i = _hash('$provider:${server.toLowerCase()}') % _names.length;
  for (var n = 0; n < _names.length; n++, i = (i + 1) % _names.length) {
    if (!taken.contains(_names[i])) {
      taken.add(_names[i]);
      return _names[i];
    }
  }
  final fallback = 'Server ${taken.length + 1}';
  taken.add(fallback);
  return fallback;
}

String? _str(dynamic v) => v is String && v.trim().isNotEmpty ? v.trim() : null;

class SiteApi {
  SiteApi._();
  static final SiteApi instance = SiteApi._();

  Future<dynamic> _get(String url, {required Duration timeout}) async {
    final http.Response res;
    try {
      res = await http.get(Uri.parse(url), headers: {'Accept': 'application/json', 'User-Agent': _ua}).timeout(timeout);
    } catch (_) {
      throw ApiException('The streaming server did not answer. Try again in a moment.');
    }
    dynamic json;
    try {
      json = res.body.length > 20000 ? await compute(_decode, res.body) : jsonDecode(res.body);
    } catch (_) {
      json = null;
    }
    if (res.statusCode != 200) {
      final msg = json is Map ? json['error'] : null;
      throw ApiException(msg is String && msg.isNotEmpty ? msg : 'Streaming server error (HTTP ${res.statusCode}).');
    }
    return json;
  }

  String get _base => AppConfig.anivexaUrl.replaceAll(RegExp(r'/+$'), '');

  /* --------------------------------- episodes --------------------------------- */

  final Map<int, ({DateTime at, EpisodesPayload data})> _eps = {};
  final Map<int, Future<EpisodesPayload>> _epsInflight = {};
  final Map<int, List<void Function(EpisodesPayload)>> _epsListeners = {};

  /// Episodes of an anime. Every provider is asked at the same time and the first answer is returned straight away;
  /// slower providers are merged in later and delivered through [onUpdate]. A copy saved on the phone (or in Upstash)
  /// is returned instantly and refreshed quietly in the background.
  Future<EpisodesPayload> episodes(int animeId, {bool force = false, void Function(EpisodesPayload)? onUpdate}) async {
    if (onUpdate != null) (_epsListeners[animeId] ??= []).add(onUpdate);
    final hit = _eps[animeId];
    if (!force && hit != null && DateTime.now().difference(hit.at).inMinutes < 10) return hit.data;
    if (!force) {
      final cached = await AppCache.instance.read('ep:$animeId');
      if (cached != null && cached.data is Map) {
        try {
          final data = EpisodesPayload.fromJson(cached.data as Map);
          if (data.sub.isNotEmpty || data.dub.isNotEmpty) {
            _eps[animeId] = (at: cached.at, data: data);
            if (DateTime.now().difference(cached.at).inMinutes >= 10) _refresh(animeId, quiet: true).catchError((_) => data);
            return data;
          }
        } catch (_) {}
      }
    }
    return _refresh(animeId, quiet: false);
  }

  Future<EpisodesPayload> _refresh(int id, {required bool quiet}) {
    return _epsInflight[id] ??= _runEpisodes(id, quiet).whenComplete(() => _epsInflight.remove(id));
  }

  EpisodesPayload? _build(List<String> order, Map<String, dynamic> data) {
    final sub = _merge(order, data, 'sub');
    final dub = _merge(order, data, 'dub');
    if (sub.isEmpty && dub.isEmpty) return null;
    String? title;
    for (final p in order) {
      final m = data[p];
      final t = m is Map ? _str((m['meta'] as Map?)?['title']) : null;
      if (t != null) {
        title = t;
        break;
      }
    }
    return EpisodesPayload(title: title, sub: sub, dub: dub);
  }

  Future<EpisodesPayload> _runEpisodes(int id, bool quiet) {
    final order = AppConfig.providers;
    final found = <String, dynamic>{};
    final done = Completer<EpisodesPayload>();
    var pending = order.length;
    var failures = 0;

    void deliver(EpisodesPayload p) {
      _eps[id] = (at: DateTime.now(), data: p);
      AppCache.instance.write('ep:$id', p.toJson());
      for (final f in List.of(_epsListeners[id] ?? const [])) {
        f(p);
      }
    }

    for (final provider in order) {
      _get('$_base/episodes/$provider/$id?map=false', timeout: const Duration(seconds: 25)).then((raw) {
        if (raw is Map && raw[provider] is Map) found[provider] = raw[provider];
        final p = _build(order, found);
        if (p == null) return;
        if (!done.isCompleted) done.complete(p);
        if (!quiet && pending > 1) deliver(p);
      }).catchError((_) {
        failures++;
      }).whenComplete(() {
        pending--;
        if (pending > 0) return;
        final p = _build(order, found);
        if (p != null) {
          deliver(p);
          if (!done.isCompleted) done.complete(p);
        } else if (!done.isCompleted) {
          done.completeError(ApiException(failures >= order.length
              ? 'Could not reach the streaming server. Check your connection and try again.'
              : 'No server has this anime yet. Try again later.'));
        }
      });
    }
    return done.future;
  }

  List<WatchEpisode> _merge(List<String> order, Map<String, dynamic> data, String audio) {
    final byNumber = <int, WatchEpisode>{};
    for (final provider in order) {
      final p = data[provider];
      final list = p is Map ? ((p['episodes'] as Map?)?[audio] as List?) : null;
      for (final raw in list ?? const []) {
        if (raw is! Map) continue;
        final number = int.tryParse('${raw['number']}');
        if (number == null || number < 0) continue;
        final have = byNumber[number];
        final dur = raw['duration'] is num ? (raw['duration'] as num).toInt() : null;
        if (have == null) {
          byNumber[number] = WatchEpisode(
            number: number,
            title: _str(raw['title']) ?? 'Episode $number',
            description: _str(raw['description']),
            image: _str(raw['image']),
            airDate: _str(raw['airDate']),
            duration: dur,
            filler: raw['filler'] == true,
            providers: [provider],
          );
        } else {
          have.providers.add(provider);
          if (RegExp(r'^Episode \d+$').hasMatch(have.title) && _str(raw['title']) != null) have.title = _str(raw['title'])!;
          have.description ??= _str(raw['description']);
          have.image ??= _str(raw['image']);
          have.airDate ??= _str(raw['airDate']);
          have.duration ??= dur;
        }
      }
    }
    return byNumber.values.toList()..sort((a, b) => a.number.compareTo(b.number));
  }

  /* ----------------------------------- streams ----------------------------------- */

  final Map<String, ({DateTime at, WatchPayload data})> _watch = {};

  /// Streams for one episode. Providers are asked at the same time; the first one with a playable stream is
  /// returned right away and the others are added through [onMore] (so playback starts as early as possible).
  Future<WatchPayload> watch(int animeId, {required bool dub, required int episode, List<String> providers = const [], void Function(WatchPayload)? onMore}) {
    final key = '$animeId:${dub ? 'dub' : 'sub'}:$episode:${providers.join(',')}';
    final hit = _watch[key];
    // stream links expire quickly, so they are only reused for a minute and a half
    if (hit != null && DateTime.now().difference(hit.at).inSeconds < 90) return Future.value(hit.data);
    final audio = dub ? 'dub' : 'sub';
    final order = AppConfig.providers.where((p) => providers.isEmpty || providers.contains(p)).toList();
    if (order.isEmpty) return Future.error(ApiException('No server has this episode.'));
    final taken = <String>{};
    final results = <String, ({List<StreamSource> streams, Range? intro, Range? outro})>{};
    final done = Completer<WatchPayload>();
    var pending = order.length;

    WatchPayload build() {
      final streams = <StreamSource>[];
      Range? intro;
      Range? outro;
      for (final p in order) {
        final r = results[p];
        if (r == null) continue;
        streams.addAll(r.streams);
        intro ??= r.intro;
        outro ??= r.outro;
      }
      return WatchPayload(streams: streams, intro: intro, outro: outro);
    }

    for (final provider in order) {
      _get('$_base/watch/$provider/$animeId/$audio/$provider-$episode', timeout: const Duration(seconds: 25)).then((raw) {
        final r = _parseWatch(provider, dub, raw as Map<String, dynamic>, taken);
        if (r.streams.isEmpty) return;
        results[provider] = r;
        final p = build();
        _watch[key] = (at: DateTime.now(), data: p);
        if (!done.isCompleted) {
          done.complete(p);
        } else {
          onMore?.call(p);
        }
      }).catchError((_) {}).whenComplete(() {
        pending--;
        if (pending == 0 && !done.isCompleted) {
          done.completeError(ApiException('No playable stream was found for this episode. Try another episode or try again later.'));
        }
      });
    }
    return done.future;
  }

  bool _isThumbs(Map t) => RegExp(r'thumbnail|storyboard|sprite|preview', caseSensitive: false).hasMatch('${t['url'] ?? t['src'] ?? t['file'] ?? ''} ${t['label'] ?? ''}');

  ({List<StreamSource> streams, Range? intro, Range? outro}) _parseWatch(String provider, bool dub, Map<String, dynamic> data, Set<String> taken) {
    final streams = <StreamSource>[];
    final seen = <String>{};
    final all = ((data['streams'] as List?) ?? const []).whereType<Map>().toList();
    num prio(Map s) => s['priority'] is num ? s['priority'] as num : 0;
    final ordered = [...all]..sort((a, b) {
        final act = (b['isActive'] == true ? 1 : 0) - (a['isActive'] == true ? 1 : 0);
        return act != 0 ? act : prio(b).compareTo(prio(a));
      });
    final topHeaders = data['headers'] as Map?;
    for (final s in ordered) {
      final type = '${s['type'] ?? ''}'.toLowerCase();
      final format = type == 'hls' || type == 'm3u8' ? 'hls' : (type == 'mp4' ? 'file' : null);
      final url = s['url'];
      if (format == null || url is! String || !RegExp(r'^https?://', caseSensitive: false).hasMatch(url) || !seen.add(url)) continue;
      final referer = _str(s['referer']) ?? _str((s['headers'] as Map?)?['Referer']) ?? _str(topHeaders?['Referer']) ?? '';
      final ua = _str((s['headers'] as Map?)?['User-Agent']) ?? _str(topHeaders?['User-Agent']) ?? _ua;
      final headers = <String, String>{'User-Agent': ua, 'Accept': '*/*', if (referer.isNotEmpty) 'Referer': referer};
      if (referer.isNotEmpty) {
        final o = Uri.tryParse(referer);
        if (o != null && o.hasScheme && o.host.isNotEmpty) headers['Origin'] = '${o.scheme}://${o.host}${o.hasPort ? ':${o.port}' : ''}';
      }
      final rawSubs = ((s['subtitles'] is List && (s['subtitles'] as List).isNotEmpty) ? s['subtitles'] as List : ((data['subtitles'] as List?) ?? const [])).whereType<Map>().where((t) => !_isThumbs(t));
      final subtitles = <StreamSubtitle>[];
      for (final t in rawSubs) {
        final u = t['url'] ?? t['src'] ?? t['file'];
        if (u is! String || !RegExp(r'^https?://', caseSensitive: false).hasMatch(u)) continue;
        final r = _str((t['headers'] as Map?)?['Referer']) ?? referer;
        subtitles.add(StreamSubtitle(
          label: _str(t['label']) ?? _str(t['lang']) ?? _str(t['language']) ?? 'Subtitle',
          lang: _str(t['srclang']) ?? _str(t['lang']) ?? _str(t['language']) ?? 'und',
          src: u,
          isDefault: t['default'] == true,
          headers: {'User-Agent': ua, if (r.isNotEmpty) 'Referer': r},
        ));
      }
      final sub = s['subtitleType'];
      // When a provider doesn't say, a sub stream without caption tracks has its subtitles in the picture.
      final soft = sub == 'hardsub' ? false : (sub == 'softsub' || subtitles.isNotEmpty ? true : dub);
      streams.add(StreamSource(
        id: '$provider-${streams.length}',
        name: _nameFor(provider, _str(s['server']) ?? provider, taken),
        soft: soft,
        hls: format == 'hls',
        src: url,
        headers: headers,
        subtitles: subtitles,
        intro: Range.from(s['intro'] ?? data['intro']),
        outro: Range.from(s['outro'] ?? data['outro']),
      ));
    }
    return (
      streams: streams,
      intro: Range.from(data['intro'] ?? (ordered.isNotEmpty ? ordered.first['intro'] : null)),
      outro: Range.from(data['outro'] ?? (ordered.isNotEmpty ? ordered.first['outro'] : null)),
    );
  }

  /* ------------------------------ intro / outro (AniSkip) ------------------------------ */

  Future<SkipTimes> skip(int mal, int episode) async {
    try {
      final res = await http
          .get(Uri.parse('https://api.aniskip.com/v2/skip-times/$mal/$episode?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&episodeLength=0'))
          .timeout(const Duration(seconds: 8));
      if (res.statusCode != 200) return const SkipTimes();
      final json = jsonDecode(res.body) as Map<String, dynamic>;
      final results = (json['results'] as List?) ?? const [];
      Map? pick(List<String> types) {
        for (final r in results) {
          if (r is Map && types.contains(r['skipType'])) return r;
        }
        return null;
      }

      Range? range(Map? r) {
        final i = r?['interval'];
        return i is Map && i['startTime'] is num && i['endTime'] is num ? Range((i['startTime'] as num).toDouble(), (i['endTime'] as num).toDouble()) : null;
      }

      final op = pick(['op', 'mixed-op']);
      final ed = pick(['ed', 'mixed-ed']);
      final len = (op?['episodeLength'] ?? ed?['episodeLength']);
      return SkipTimes(intro: range(op), outro: range(ed), length: len is num && len > 0 ? len.toDouble() : null);
    } catch (_) {
      return const SkipTimes();
    }
  }

  /* ------------------------------- avatar decorations ------------------------------- */

  List<AvatarDeco>? _decos;
  Future<List<AvatarDeco>> decorations() async {
    if (_decos != null) return _decos!;
    try {
      final rows = await Supabase.instance.client.from('avatar_decorations').select().eq('active', true).order('sort', ascending: true);
      _decos = (rows as List).map((e) => AvatarDeco.fromRow(Map<String, dynamic>.from(e as Map))).toList();
      return _decos!;
    } catch (_) {
      return const [];
    }
  }
}
