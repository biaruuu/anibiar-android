import 'dart:convert';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/nav.dart';
import '../core/theme.dart';
import '../player/player_model.dart';
import '../player/player_view.dart';
import '../services/anilist.dart';
import '../services/api.dart';
import '../services/auth.dart';
import '../services/library.dart';
import '../widgets/cards.dart';
import '../widgets/glass.dart';

class WatchScreen extends StatefulWidget {
  const WatchScreen({super.key, required this.id, this.episode = 1, this.dub = false});
  final int id;
  final int episode;
  final bool dub;
  @override
  State<WatchScreen> createState() => _WatchScreenState();
}

class _WatchScreenState extends State<WatchScreen> {
  static const _prefsKey = 'anibiar:prefs';

  final PlayerModel _player = PlayerModel();
  final PlayerPrefs _prefs = PlayerPrefs();

  Detail? _detail;
  EpisodesPayload? _eps;
  String? _epsError;
  WatchPayload? _watch;
  String? _watchError;
  SkipTimes? _backup;

  late bool _dub = widget.dub;
  late int _ep = widget.episode;
  String? _serverName;
  bool _loved = false;
  bool _saved = false;
  bool _grid = true;
  bool _reverse = false;
  bool _synopsis = false;
  int _watchGen = 0;
  HistoryItem? _saved0; // history row used to resume

  @override
  void initState() {
    super.initState();
    _player.onTime = _remember;
    _player.onEnded = _ended;
    _player.onFail = _failed;
    _boot();
  }

  Future<void> _boot() async {
    final sp = await SharedPreferences.getInstance();
    try {
      final j = jsonDecode(sp.getString(_prefsKey) ?? '{}') as Map<String, dynamic>;
      _prefs.autoplay = j['autoplay'] != false;
      _prefs.autoNext = j['autoNext'] != false;
      _prefs.autoSkip = j['autoSkip'] != false;
    } catch (_) {}
    _player.autoplay = _prefs.autoplay;
    _player.autoNext = _prefs.autoNext;
    _player.autoSkip = _prefs.autoSkip;
    final h = await Library.instance.loadHistory();
    _saved0 = h.where((x) => x.animeId == widget.id).cast<HistoryItem?>().firstWhere((_) => true, orElse: () => null);
    final l = await Library.instance.load(Collection.list);
    final f = await Library.instance.load(Collection.favorites);
    if (!mounted) return;
    setState(() {
      _saved = l.any((x) => x.animeId == widget.id);
      _loved = f.any((x) => x.animeId == widget.id);
    });
    AniList.instance.detail(widget.id).then((d) {
      if (mounted) setState(() => _detail = d);
    }).catchError((_) {});
    _loadEpisodes();
  }

  Future<void> _savePrefs() async {
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_prefsKey, jsonEncode({'autoplay': _prefs.autoplay, 'autoNext': _prefs.autoNext, 'autoSkip': _prefs.autoSkip}));
  }

  /* --------------------------------- episodes --------------------------------- */

  Future<void> _loadEpisodes({bool force = false}) async {
    setState(() {
      _eps = null;
      _epsError = null;
    });
    try {
      final e = await SiteApi.instance.episodes(widget.id, force: force);
      if (!mounted) return;
      // Fall back to a sensible audio and episode when the requested ones don't exist.
      if (_dub && e.dub.isEmpty) _dub = false;
      if (!_dub && e.sub.isEmpty && e.dub.isNotEmpty) _dub = true;
      final list = _dub ? e.dub : e.sub;
      if (list.isNotEmpty && !list.any((x) => x.number == _ep)) _ep = list.first.number;
      setState(() => _eps = e);
      _loadWatch();
    } catch (e) {
      if (mounted) setState(() => _epsError = friendlyError(e));
    }
  }

  List<WatchEpisode> get _list {
    final e = _eps;
    if (e == null) return const [];
    return _dub && e.dub.isNotEmpty ? e.dub : e.sub;
  }

  WatchEpisode? get _current {
    for (final e in _list) {
      if (e.number == _ep) return e;
    }
    return null;
  }

  int get _pos => _list.indexWhere((e) => e.number == _ep);
  WatchEpisode? get _prev => _pos > 0 ? _list[_pos - 1] : null;
  WatchEpisode? get _next => _pos >= 0 && _pos < _list.length - 1 ? _list[_pos + 1] : null;

  String get _name => _detail?.anime.name ?? _eps?.title ?? 'Anime ${widget.id}';

  /* ---------------------------------- streams ---------------------------------- */

  Future<void> _loadWatch() async {
    final ep = _current;
    if (ep == null) return;
    final gen = ++_watchGen;
    setState(() {
      _watch = null;
      _watchError = null;
      _backup = null;
    });
    try {
      final w = await SiteApi.instance.watch(widget.id, dub: _dub, episode: ep.number, providers: ep.providers);
      if (!mounted || gen != _watchGen) return;
      if (w.streams.isEmpty) throw ApiException('No stream is available for this episode right now.');
      setState(() => _watch = w);
      await _openStream(resume: true);
      final mal = _detail?.idMal;
      if (mal != null) {
        SiteApi.instance.skip(mal, ep.number).then((b) {
          if (mounted && gen == _watchGen) {
            setState(() => _backup = b);
            _applySkip();
          }
        });
      }
    } catch (e) {
      if (mounted && gen == _watchGen) setState(() => _watchError = friendlyError(e));
    }
  }

  StreamSource? get _stream {
    final w = _watch;
    if (w == null || w.streams.isEmpty) return null;
    if (_serverName != null) {
      for (final s in w.streams) {
        if (s.name == _serverName) return s;
      }
    }
    return w.streams.first;
  }

  ({Range? intro, Range? outro, double? length}) _skipData() {
    final s = _stream;
    final w = _watch;
    if (s != null && (s.intro != null || s.outro != null)) return (intro: s.intro, outro: s.outro, length: null);
    if (w != null && (w.intro != null || w.outro != null)) return (intro: w.intro, outro: w.outro, length: null);
    return (intro: _backup?.intro, outro: _backup?.outro, length: _backup?.length);
  }

  void _applySkip() {
    final k = _skipData();
    _player.intro = k.intro;
    _player.outro = k.outro;
    _player.lengthHint = k.length;
  }

  Future<void> _openStream({required bool resume}) async {
    final s = _stream;
    if (s == null) return;
    final k = _skipData();
    double start = 0;
    if (resume) {
      final h = _saved0;
      if (h != null && h.episode == _ep && h.dub == _dub && h.duration > 0 && h.position / h.duration < 0.92) start = h.position;
    } else {
      start = _player.position;
    }
    await _player.open(url: s.src, hls: s.hls, subtitles: s.soft ? s.subtitles : const [], startAt: start, intro: k.intro, outro: k.outro, length: k.length);
  }

  void _failed() {
    // Try the next server automatically when this one can't play.
    final all = _watch?.streams ?? const <StreamSource>[];
    final at = all.indexWhere((x) => x.id == _stream?.id);
    if (at >= 0 && at < all.length - 1) {
      setState(() => _serverName = all[at + 1].name);
      _openStream(resume: false);
    } else if (mounted) {
      setState(() => _watchError = 'This episode could not be played on any server. Try another server or episode.');
    }
  }

  /* --------------------------------- progress --------------------------------- */

  void _remember(double position, double duration, [int? episode]) {
    final d = _detail?.anime;
    final item = HistoryItem(
      animeId: widget.id,
      episode: episode ?? _ep,
      dub: _dub,
      position: episode != null ? 0 : position,
      duration: episode != null ? 0 : duration,
      title: _name,
      image: d?.thumb,
      totalEpisodes: d?.episodes ?? (_list.isNotEmpty ? _list.last.number : null),
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _saved0 = item;
    Library.instance.saveProgress(item);
  }

  void _ended() {
    final n = _next;
    if (n != null) {
      // Watched to the end: Continue Watching should point at the next episode.
      _remember(0, 0, n.number);
      if (_prefs.autoNext) _select(n.number);
    } else {
      _remember(1, 1);
    }
  }

  @override
  void dispose() {
    _player.flush();
    _player.dispose();
    super.dispose();
  }

  void _select(int number) {
    if (number == _ep) return;
    _player.flush();
    setState(() => _ep = number);
    _loadWatch();
  }

  Future<void> _toggle(Collection c) async {
    final a = _detail?.anime;
    final now = await Library.instance.toggle(c, animeId: widget.id, title: _name, image: a?.thumb);
    if (!mounted) return;
    setState(() => c == Collection.favorites ? _loved = now : _saved = now);
  }

  /* ----------------------------------- build ----------------------------------- */

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.of(context).padding.top;
    final ep = _current;
    final title = ep != null ? '$_name · Episode ${ep.number}' : _name;
    return PopScope(
      onPopInvokedWithResult: (_, __) => _player.flush(),
      child: Scaffold(
        backgroundColor: C.bg,
        body: Column(children: [
          // header: back + name (a normal bar above the player, scrolls with nothing floating over the video)
          Container(
            padding: EdgeInsets.fromLTRB(10, top + 6, 14, 8),
            color: Colors.black,
            child: Row(children: [
              GlassIconButton(icon: Icons.arrow_back_rounded, size: 40, iconSize: 21, onTap: () => Navigator.of(context).maybePop(), tooltip: 'Back'),
              const SizedBox(width: 12),
              Expanded(child: Text(_name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700))),
            ]),
          ),
          Stack(children: [
            PlayerView(model: _player, title: title, prefs: _prefs, onPrefs: () {
              _player.autoSkip = _prefs.autoSkip;
              _savePrefs();
              setState(() {});
            }),
            Positioned.fill(
              child: ListenableBuilder(
                listenable: _player,
                builder: (context, _) {
                  final err = _epsError ?? _watchError;
                  final waiting = _watch == null || (_player.loading && !_player.ready);
                  if (err == null && !waiting) return const SizedBox.shrink();
                  return _PlayerStatus(
                    error: err,
                    loadingEpisodes: _eps == null,
                    poster: _detail?.anime.banner ?? _detail?.anime.poster,
                    onRetry: () => _epsError != null ? _loadEpisodes(force: true) : _loadWatch(),
                  );
                },
              ),
            ),
          ]),
          Expanded(child: _body()),
        ]),
      ),
    );
  }

  Widget _body() {
    final ep = _current;
    final e = _eps;
    final a = _detail?.anime;
    final streams = _watch?.streams ?? const <StreamSource>[];
    final hasDub = e?.dub.isNotEmpty ?? false;
    final hasSub = e?.sub.isNotEmpty ?? false;
    final dubSet = e?.dub.map((x) => x.number).toSet() ?? <int>{};
    final list = _reverse ? _list.reversed.toList() : _list;
    return ListView(padding: const EdgeInsets.fromLTRB(16, 16, 16, 40), children: [
      Text(ep == null ? _name : 'Episode ${ep.number}${ep.title.isNotEmpty && ep.title != 'Episode ${ep.number}' ? ' · ${ep.title}' : ''}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, height: 1.25)),
      const SizedBox(height: 4),
      Text(_name, style: TextStyle(fontSize: 13, color: C.w(0.55))),
      const SizedBox(height: 14),
      // audio + server + prev/next
      Row(children: [
        GlassSwitch<bool>(
          value: _dub,
          width: 112,
          onChanged: (v) {
            if (v == _dub) return;
            if ((v && !hasDub) || (!v && !hasSub)) {
              toast(context, v ? 'No English dub for this title yet.' : 'No subbed version found.');
              return;
            }
            setState(() => _dub = v);
            final l = _list;
            if (l.isNotEmpty && !l.any((x) => x.number == _ep)) _ep = l.first.number;
            _player.flush();
            _loadWatch();
          },
          options: const [(false, 'Sub'), (true, 'Dub')],
        ),
        const SizedBox(width: 10),
        Expanded(
          child: GlassButton(
            label: _stream?.name ?? 'Server',
            icon: Icons.dns_rounded,
            height: 36,
            fontSize: 13,
            onTap: streams.length < 2 && _stream != null ? null : (streams.isEmpty ? null : _serverSheet),
          ),
        ),
        const SizedBox(width: 10),
        GlassIconButton(icon: Icons.skip_previous_rounded, size: 36, iconSize: 20, onTap: _prev == null ? null : () => _select(_prev!.number)),
        const SizedBox(width: 8),
        GlassIconButton(icon: Icons.skip_next_rounded, size: 36, iconSize: 20, onTap: _next == null ? null : () => _select(_next!.number)),
      ]),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(child: GlassButton(label: _loved ? 'Favorited' : 'Favorite', icon: _loved ? Icons.favorite_rounded : Icons.favorite_border_rounded, height: 42, radius: 12, onTap: () => _toggle(Collection.favorites))),
        const SizedBox(width: 10),
        Expanded(child: GlassButton(label: _saved ? 'In watchlist' : 'Watchlist', icon: _saved ? Icons.bookmark_rounded : Icons.bookmark_border_rounded, height: 42, radius: 12, onTap: () => _toggle(Collection.list))),
        const SizedBox(width: 10),
        GlassIconButton(icon: Icons.info_outline_rounded, size: 42, radius: 12, onTap: () => openInfo(context, widget.id), tooltip: 'Details'),
      ]),
      const SizedBox(height: 22),
      Row(children: [
        const Expanded(child: Text('Episodes', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800))),
        GlassIconButton(icon: Icons.swap_vert_rounded, size: 34, iconSize: 18, onTap: () => setState(() => _reverse = !_reverse), tooltip: 'Reverse order'),
        const SizedBox(width: 8),
        GlassIconButton(icon: _grid ? Icons.view_list_rounded : Icons.grid_view_rounded, size: 34, iconSize: 18, onTap: () => setState(() => _grid = !_grid), tooltip: _grid ? 'List view' : 'Grid view'),
      ]),
      const SizedBox(height: 12),
      if (_epsError != null)
        ErrorBox(message: _epsError!, onRetry: () => _loadEpisodes(force: true))
      else if (e == null)
        Wrap(spacing: 8, runSpacing: 8, children: [for (var i = 0; i < 12; i++) const Skel(width: 52, height: 44, radius: 12)])
      else if (list.isEmpty)
        Text('No episodes are available yet.', style: TextStyle(color: C.w(0.6)))
      else if (_grid)
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final x in list)
            GestureDetector(
              onTap: () => _select(x.number),
              child: Container(
                width: 52,
                height: 44,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(12),
                  color: x.number == _ep ? Colors.white : C.w(0.06),
                  border: Border.all(color: x.number == _ep ? Colors.white : (x.filler ? const Color(0x66FB923C) : C.w(0.15))),
                ),
                child: Text('${x.number}', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: x.number == _ep ? Colors.black : Colors.white)),
              ),
            ),
        ])
      else
        Column(children: [
          for (final x in list)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => _select(x.number),
              child: Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  color: x.number == _ep ? C.w(0.12) : C.w(0.04),
                  border: Border.all(color: x.number == _ep ? C.w(0.4) : C.w(0.1)),
                ),
                child: Row(children: [
                  SizedBox(
                    width: 96,
                    child: AspectRatio(
                      aspectRatio: 16 / 9,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(8),
                        child: x.image == null ? Container(color: C.w(0.06)) : CachedNetworkImage(imageUrl: x.image!, fit: BoxFit.cover, errorWidget: (_, __, ___) => Container(color: C.w(0.06))),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text('${x.number}. ${x.title}', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700, height: 1.25)),
                      const SizedBox(height: 5),
                      Row(children: [
                        Icon(Icons.closed_caption_outlined, size: 15, color: C.w(0.6)),
                        if (dubSet.contains(x.number)) ...[const SizedBox(width: 6), Icon(Icons.mic_rounded, size: 15, color: C.w(0.6))],
                        if (x.filler) ...[const SizedBox(width: 8), const Text('Filler', style: TextStyle(fontSize: 11, color: C.orange, fontWeight: FontWeight.w700))],
                        if (x.duration != null) ...[const SizedBox(width: 8), Text('${(x.duration! / 60).round()}m', style: TextStyle(fontSize: 11, color: C.w(0.5)))],
                      ]),
                    ]),
                  ),
                ]),
              ),
            ),
        ]),
      if (a != null) ...[
        const SizedBox(height: 22),
        Panel(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(ep?.description != null && _synopsis ? 'Episode synopsis' : 'Synopsis', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800))),
              if (ep?.description != null && ep!.description!.trim().isNotEmpty)
                GestureDetector(onTap: () => setState(() => _synopsis = !_synopsis), child: Text(_synopsis ? 'Show anime' : 'Show episode', style: TextStyle(fontSize: 12, color: C.w(0.6), decoration: TextDecoration.underline))),
            ]),
            const SizedBox(height: 8),
            Text(
              stripHtml(_synopsis && ep?.description != null ? ep!.description! : (a.description ?? '')),
              maxLines: 8,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 13.5, height: 1.5, color: C.w(0.7)),
            ),
          ]),
        ),
      ],
      if (_detail != null && _detail!.relations.isNotEmpty) ...[
        const SizedBox(height: 22),
        const Text('Seasons & Related', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
        const SizedBox(height: 12),
        SizedBox(
          height: 214,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: _detail!.relations.length,
            separatorBuilder: (_, __) => const SizedBox(width: 10),
            itemBuilder: (context, i) {
              final r = _detail!.relations[i];
              return SizedBox(
                width: 104,
                child: GestureDetector(
                  onTap: () => openWatch(context, r.media.id),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Poster(url: r.media.poster),
                    const SizedBox(height: 6),
                    Text(r.media.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, height: 1.25, fontWeight: FontWeight.w600)),
                  ]),
                ),
              );
            },
          ),
        ),
      ],
    ]);
  }

  void _serverSheet() {
    final streams = _watch?.streams ?? const <StreamSource>[];
    showGlassSheet<void>(context, builder: (ctx) {
      return ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * 0.7),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(14, 16, 14, 18),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Padding(padding: EdgeInsets.fromLTRB(4, 0, 4, 10), child: Text('Server', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800))),
            for (final s in streams)
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () {
                  Navigator.of(ctx).pop();
                  if (s.name == _stream?.name) return;
                  setState(() => _serverName = s.name);
                  _openStream(resume: false);
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 12),
                  child: Row(children: [
                    Expanded(child: Text(s.name, style: TextStyle(fontSize: 15, fontWeight: s.name == _stream?.name ? FontWeight.w800 : FontWeight.w600))),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(borderRadius: BorderRadius.circular(99), color: C.w(0.08)),
                      child: Text(s.soft ? 'Soft subs' : 'Hard subs', style: TextStyle(fontSize: 11, color: C.w(0.7))),
                    ),
                    if (s.name == _stream?.name) const Padding(padding: EdgeInsets.only(left: 10), child: Icon(Icons.check_rounded, size: 20)),
                  ]),
                ),
              ),
          ]),
        ),
      );
    });
  }
}

/// Covers the player while episodes / the stream are loading, or shows why it could not load.
class _PlayerStatus extends StatelessWidget {
  const _PlayerStatus({required this.error, required this.loadingEpisodes, required this.poster, required this.onRetry});
  final String? error;
  final bool loadingEpisodes;
  final String? poster;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 16 / 9,
      child: Container(
        color: C.k(0.88),
        child: Stack(fit: StackFit.expand, children: [
          if (poster != null) Opacity(opacity: 0.25, child: CachedNetworkImage(imageUrl: poster!, fit: BoxFit.cover, errorWidget: (_, __, ___) => const SizedBox())),
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 28),
              child: error != null
                  ? Column(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.error_outline_rounded, size: 30, color: C.w(0.85)),
                      const SizedBox(height: 8),
                      const Text("Couldn't load this episode", style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800)),
                      const SizedBox(height: 4),
                      Text(error!, maxLines: 3, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: C.w(0.7))),
                      const SizedBox(height: 10),
                      GlassButton(label: 'Try again', icon: Icons.refresh_rounded, kind: GlassKind.white, height: 36, fontSize: 12, onTap: onRetry),
                    ])
                  : Column(mainAxisSize: MainAxisSize.min, children: [
                      const SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)),
                      const SizedBox(height: 10),
                      Text(loadingEpisodes ? 'Loading episodes…' : 'Finding the best stream…', style: TextStyle(fontSize: 12, color: C.w(0.7))),
                    ]),
            ),
          ),
        ]),
      ),
    );
  }
}
