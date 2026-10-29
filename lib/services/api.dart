import 'dart:convert';

import 'package:http/http.dart' as http;

import '../core/config.dart';

/// Talks to the AniBiar TV website's own API (episodes, streams, skip times, avatar decorations),
/// so the app always shows exactly what the website shows.

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
    if (s is num && e is num) return Range(s.toDouble(), e.toDouble());
    return null;
  }
}

class WatchEpisode {
  WatchEpisode({required this.number, required this.title, this.description, this.image, this.airDate, this.duration, this.filler = false, this.providers = const []});
  final int number;
  final String title;
  final String? description;
  final String? image;
  final String? airDate;
  final int? duration;
  final bool filler;
  final List<String> providers;

  factory WatchEpisode.fromJson(Map<String, dynamic> j) => WatchEpisode(
        number: (j['number'] as num).toInt(),
        title: (j['title'] as String?) ?? 'Episode ${j['number']}',
        description: j['description'] as String?,
        image: j['image'] as String?,
        airDate: j['airDate'] as String?,
        duration: (j['duration'] as num?)?.toInt(),
        filler: j['filler'] == true,
        providers: ((j['providers'] as List?) ?? const []).map((e) => '$e').toList(),
      );
}

class EpisodesPayload {
  EpisodesPayload({this.title, required this.sub, required this.dub});
  final String? title;
  final List<WatchEpisode> sub;
  final List<WatchEpisode> dub;

  factory EpisodesPayload.fromJson(Map<String, dynamic> j) => EpisodesPayload(
        title: j['title'] as String?,
        sub: ((j['sub'] as List?) ?? const []).map((e) => WatchEpisode.fromJson(e as Map<String, dynamic>)).toList(),
        dub: ((j['dub'] as List?) ?? const []).map((e) => WatchEpisode.fromJson(e as Map<String, dynamic>)).toList(),
      );
}

class StreamSubtitle {
  StreamSubtitle({required this.label, required this.lang, required this.src, required this.isDefault});
  final String label;
  final String lang;
  final String src;
  final bool isDefault;
}

class StreamSource {
  StreamSource({required this.id, required this.name, required this.soft, required this.hls, required this.src, required this.subtitles, this.intro, this.outro});
  final String id;
  final String name;

  /// true = separate subtitle tracks, false = subtitles burned into the video.
  final bool soft;
  final bool hls;
  final String src;
  final List<StreamSubtitle> subtitles;
  final Range? intro;
  final Range? outro;

  factory StreamSource.fromJson(Map<String, dynamic> j) => StreamSource(
        id: '${j['id']}',
        name: (j['name'] as String?) ?? 'Server',
        soft: j['kind'] != 'hard',
        hls: j['format'] != 'file',
        src: AppConfig.abs(j['src'] as String),
        subtitles: ((j['subtitles'] as List?) ?? const [])
            .map((e) => StreamSubtitle(
                  label: (e['label'] as String?) ?? 'Subtitles',
                  lang: (e['srclang'] as String?) ?? '',
                  src: AppConfig.abs(e['src'] as String),
                  isDefault: e['default'] == true,
                ))
            .toList(),
        intro: Range.from(j['intro']),
        outro: Range.from(j['outro']),
      );
}

class WatchPayload {
  WatchPayload({required this.streams, this.intro, this.outro});
  final List<StreamSource> streams;
  final Range? intro;
  final Range? outro;

  factory WatchPayload.fromJson(Map<String, dynamic> j) => WatchPayload(
        streams: ((j['streams'] as List?) ?? const []).map((e) => StreamSource.fromJson(e as Map<String, dynamic>)).toList(),
        intro: Range.from(j['intro']),
        outro: Range.from(j['outro']),
      );
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

  factory AvatarDeco.fromJson(Map<String, dynamic> j) => AvatarDeco(
        id: '${j['id']}',
        label: (j['label'] as String?) ?? '${j['id']}',
        sheet: AppConfig.abs(j['sheet'] as String),
        frames: (j['frames'] as num).toInt(),
        cols: (j['cols'] as num).toInt(),
        frameSize: (j['frame_size'] as num).toInt(),
        fps: (j['fps'] as num).toDouble(),
        scale: (j['scale'] as num).toDouble(),
      );
}

class SiteApi {
  SiteApi._();
  static final SiteApi instance = SiteApi._();

  Future<dynamic> _get(String path, {Duration timeout = const Duration(seconds: 40)}) async {
    final http.Response res;
    try {
      res = await http.get(Uri.parse(AppConfig.abs(path)), headers: {'Accept': 'application/json'}).timeout(timeout);
    } catch (_) {
      throw ApiException("Couldn't reach AniBiar. Check your connection and try again.");
    }
    dynamic json;
    try {
      json = jsonDecode(res.body);
    } catch (_) {
      json = null;
    }
    if (res.statusCode != 200) {
      final msg = json is Map ? json['error'] as String? : null;
      throw ApiException(msg ?? 'HTTP ${res.statusCode}');
    }
    return json;
  }

  final Map<int, ({DateTime at, EpisodesPayload data})> _eps = {};

  Future<EpisodesPayload> episodes(int animeId, {bool force = false}) async {
    final hit = _eps[animeId];
    if (!force && hit != null && DateTime.now().difference(hit.at).inMinutes < 5) return hit.data;
    final json = await _get('/api/anime/$animeId/episodes');
    final data = EpisodesPayload.fromJson(json as Map<String, dynamic>);
    _eps[animeId] = (at: DateTime.now(), data: data);
    return data;
  }

  Future<WatchPayload> watch(int animeId, {required bool dub, required int episode, List<String> providers = const []}) async {
    final p = providers.isEmpty ? '' : '&p=${providers.join(',')}';
    final json = await _get('/api/anime/$animeId/watch?audio=${dub ? 'dub' : 'sub'}&ep=$episode$p', timeout: const Duration(seconds: 70));
    return WatchPayload.fromJson(json as Map<String, dynamic>);
  }

  Future<SkipTimes> skip(int mal, int episode) async {
    try {
      final json = await _get('/api/skip?mal=$mal&ep=$episode', timeout: const Duration(seconds: 12)) as Map<String, dynamic>;
      return SkipTimes(intro: Range.from(json['intro']), outro: Range.from(json['outro']), length: (json['length'] as num?)?.toDouble());
    } catch (_) {
      return const SkipTimes();
    }
  }

  List<AvatarDeco>? _decos;
  Future<List<AvatarDeco>> decorations() async {
    if (_decos != null) return _decos!;
    try {
      final json = await _get('/api/decorations', timeout: const Duration(seconds: 15)) as Map<String, dynamic>;
      _decos = ((json['decorations'] as List?) ?? const []).map((e) => AvatarDeco.fromJson(e as Map<String, dynamic>)).toList();
    } catch (_) {
      return const [];
    }
    return _decos!;
  }
}
