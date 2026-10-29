import 'dart:async';
import 'dart:ui' show FontFeature;

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../core/nav.dart';
import '../core/theme.dart';
import '../services/anilist.dart';
import '../services/api.dart';
import '../services/auth.dart';
import '../services/library.dart';
import '../widgets/cards.dart';
import '../widgets/glass.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.onSearch});
  final VoidCallback onSearch;
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  List<Anime>? _trending;
  int _hero = 0;
  Timer? _timer;
  List<HistoryItem>? _history;
  final _scroll = ScrollController();
  final _latestKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    _loadTrending();
    _loadHistory();
    Auth.instance.addListener(_loadHistory);
    _timer = Timer.periodic(const Duration(seconds: 8), (_) {
      final t = _trending;
      if (t != null && t.length > 1 && mounted) setState(() => _hero = (_hero + 1) % t.length);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    Auth.instance.removeListener(_loadHistory);
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _loadTrending() async {
    try {
      final t = await AniList.instance.trending();
      if (mounted) setState(() => _trending = t);
    } catch (_) {
      if (mounted) setState(() => _trending = []);
    }
  }

  Future<void> _loadHistory() async {
    final h = await Library.instance.loadHistory();
    if (mounted) setState(() => _history = h);
  }

  Future<void> _refresh() async {
    setState(() => _trending = null);
    await Future.wait([_loadTrending(), _loadHistory()]);
  }

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.of(context).padding.top;
    final h = MediaQuery.of(context).size.height;
    return RefreshIndicator(
      onRefresh: _refresh,
      color: Colors.white,
      backgroundColor: C.secondary,
      edgeOffset: top + 56,
      child: ListView(
        controller: _scroll,
        padding: EdgeInsets.zero,
        children: [
          Stack(children: [
            _Hero(items: _trending, index: _hero, height: (h * 0.74).clamp(440.0, 640.0).toDouble(), onSelect: (i) => setState(() => _hero = i), onRetry: _refresh),
            Positioned(
              top: top + 6,
              left: 16,
              right: 16,
              child: Row(children: [
                Image.asset('assets/logo.png', height: 30, errorBuilder: (_, __, ___) => const Text('AniBiar TV', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800))),
                const Spacer(),
                GlassIconButton(icon: Icons.search_rounded, size: 40, iconSize: 21, onTap: widget.onSearch, tooltip: 'Search'),
              ]),
            ),
          ]),
          _ContinueWatching(items: _history, onChanged: _loadHistory),
          _Latest(key: _latestKey),
          const _RankedPanel(title: 'Top Airing', dot: C.green, top: true),
          const _RankedPanel(title: 'Upcoming', dot: C.orange, top: false),
          _Footer(),
          const SizedBox(height: 96),
        ],
      ),
    );
  }
}

/* ------------------------------------ hero ------------------------------------ */

class _Hero extends StatelessWidget {
  const _Hero({required this.items, required this.index, required this.height, required this.onSelect, required this.onRetry});
  final List<Anime>? items;
  final int index;
  final double height;
  final ValueChanged<int> onSelect;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final list = items;
    if (list == null) return SizedBox(height: height, child: const Center(child: SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white54))));
    if (list.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 120, 16, 24),
        child: Column(children: [
          Text("Couldn't load featured anime.", style: TextStyle(color: C.w(0.6))),
          const SizedBox(height: 12),
          GlassButton(label: 'Try again', onTap: onRetry, height: 40),
        ]),
      );
    }
    final a = list[index.clamp(0, list.length - 1).toInt()];
    final desc = stripHtml(a.description ?? '');
    final chips = <String>[
      if (a.episodes != null) '${a.episodes} Episodes',
      if (a.seasonYear != null) '${a.seasonYear}',
      ...a.genres.take(2),
    ];
    return SizedBox(
      height: height,
      child: Stack(fit: StackFit.expand, children: [
        // artwork fades into the page background at the bottom
        ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (r) => const LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Colors.black, Colors.black, Colors.transparent], stops: [0, 0.72, 1]).createShader(r),
          child: Stack(fit: StackFit.expand, children: [
            for (var i = 0; i < list.length; i++)
              AnimatedOpacity(
                opacity: i == index ? 1 : 0,
                duration: const Duration(milliseconds: 700),
                child: CachedNetworkImage(imageUrl: list[i].cover ?? list[i].banner ?? '', fit: BoxFit.cover, errorWidget: (_, __, ___) => const SizedBox()),
              ),
            Container(color: C.k(0.4)),
          ]),
        ),
        Positioned(
          left: 16,
          right: 16,
          bottom: 20,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(a.name, maxLines: 3, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 28, height: 1.1, fontWeight: FontWeight.w800, letterSpacing: -0.5, color: Colors.white, shadows: [Shadow(blurRadius: 10, color: Color(0x99000000))])),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final c in chips)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                  decoration: BoxDecoration(color: C.w(0.08), borderRadius: BorderRadius.circular(99), border: Border.all(color: C.w(0.15))),
                  child: Text(c, style: TextStyle(fontSize: 12, color: C.w(0.9))),
                ),
            ]),
            if (desc.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(desc, maxLines: 3, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 14, height: 1.45, color: C.w(0.7))),
            ],
            const SizedBox(height: 16),
            Row(children: [
              GlassButton(label: 'Watch Now', icon: Icons.play_arrow_rounded, kind: GlassKind.white, height: 42, radius: 10, onTap: () => openWatch(context, a.id, episode: 1)),
              const SizedBox(width: 10),
              GlassButton(label: 'Details', icon: Icons.info_outline_rounded, height: 42, radius: 10, onTap: () => openInfo(context, a.id)),
              const SizedBox(width: 10),
              _AddButton(anime: a),
            ]),
            if (list.length > 1) ...[
              const SizedBox(height: 16),
              Row(children: [
                for (var i = 0; i < list.length; i++)
                  GestureDetector(
                    onTap: () => onSelect(i),
                    child: Padding(
                      padding: const EdgeInsets.only(right: 6),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 250),
                        height: 6,
                        width: i == index ? 24 : 6,
                        decoration: BoxDecoration(color: i == index ? Colors.white : C.w(0.4), borderRadius: BorderRadius.circular(99)),
                      ),
                    ),
                  ),
              ]),
            ],
          ]),
        ),
      ]),
    );
  }
}

class _AddButton extends StatefulWidget {
  const _AddButton({required this.anime});
  final Anime anime;
  @override
  State<_AddButton> createState() => _AddButtonState();
}

class _AddButtonState extends State<_AddButton> {
  bool _in = false;
  @override
  void initState() {
    super.initState();
    _check();
  }

  @override
  void didUpdateWidget(covariant _AddButton old) {
    super.didUpdateWidget(old);
    if (old.anime.id != widget.anime.id) _check();
  }

  Future<void> _check() async {
    final id = widget.anime.id;
    final has = (await Library.instance.localCollection(Collection.list)).any((l) => l.animeId == id);
    if (mounted && id == widget.anime.id) setState(() => _in = has);
  }

  @override
  Widget build(BuildContext context) => GlassIconButton(
        icon: _in ? Icons.check_rounded : Icons.add_rounded,
        size: 42,
        radius: 10,
        tooltip: 'Add to list',
        onTap: () async {
          final a = widget.anime;
          final now = await Library.instance.toggle(Collection.list, animeId: a.id, title: a.name, image: a.thumb);
          if (!mounted) return;
          setState(() => _in = now);
          toast(context, now ? 'Added to your watchlist' : 'Removed from your watchlist');
        },
      );
}

/* ------------------------------- continue watching ------------------------------ */

class _ContinueWatching extends StatelessWidget {
  const _ContinueWatching({required this.items, required this.onChanged});
  final List<HistoryItem>? items;
  final Future<void> Function() onChanged;

  @override
  Widget build(BuildContext context) {
    final list = (items ?? []).where((h) => !h.finished).take(12).toList();
    if (list.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 22),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: const SectionTitle('Continue Watching')),
        const SizedBox(height: 12),
        SizedBox(
          height: 170,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            itemCount: list.length,
            separatorBuilder: (_, __) => const SizedBox(width: 12),
            itemBuilder: (context, i) {
              final h = list[i];
              return SizedBox(
                width: MediaQuery.of(context).size.width * 0.74,
                child: HistoryCard(
                  h: h,
                  onTap: () async {
                    await openWatch(context, h.animeId, episode: h.episode, dub: h.dub);
                    onChanged();
                  },
                  onRemove: () async {
                    await Library.instance.removeHistory(h.animeId);
                    onChanged();
                  },
                ),
              );
            },
          ),
        ),
      ]),
    );
  }
}

/* ------------------------------- latest episodes -------------------------------- */

final Map<String, bool> _dubCache = {};

class _Latest extends StatefulWidget {
  const _Latest({super.key});
  @override
  State<_Latest> createState() => _LatestState();
}

class _LatestState extends State<_Latest> {
  static const _maxPages = 5;
  int _page = 1;
  int _last = _maxPages;
  List<LatestEpisode>? _items;
  String? _error;
  bool _dub = false;
  int _pending = 0;
  int _token = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final token = ++_token;
    setState(() {
      _items = null;
      _error = null;
    });
    try {
      final r = await AniList.instance.latestEpisodes(_page);
      if (!mounted || token != _token) return;
      setState(() {
        _items = r.items;
        _last = r.lastPage.clamp(1, _maxPages).toInt();
      });
      if (_dub) _checkDubs(r.items, token);
    } catch (e) {
      if (mounted && token == _token) setState(() => _error = friendlyError(e));
    }
  }

  /// Dub is checked against the streaming servers, three titles at a time.
  Future<void> _checkDubs(List<LatestEpisode> items, int token) async {
    final todo = items.where((i) => !_dubCache.containsKey('${i.media.id}:${i.episode}')).toList();
    if (todo.isEmpty) return;
    setState(() => _pending = todo.length);
    var next = 0;
    Future<void> worker() async {
      while (mounted && token == _token && next < todo.length) {
        final it = todo[next++];
        try {
          final data = await SiteApi.instance.episodes(it.media.id);
          _dubCache['${it.media.id}:${it.episode}'] = data.dub.any((e) => e.number == it.episode);
        } catch (_) {
          _dubCache['${it.media.id}:${it.episode}'] = false;
        }
        if (mounted && token == _token) setState(() => _pending = (todo.length - next).clamp(0, todo.length).toInt());
      }
    }

    await Future.wait([worker(), worker(), worker()]);
    if (mounted && token == _token) setState(() => _pending = 0);
  }

  void _go(int p) {
    _page = p;
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final items = _items;
    final shown = items == null ? null : (_dub ? items.where((i) => _dubCache['${i.media.id}:${i.episode}'] == true).toList() : items);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 26, 16, 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SectionTitle(
          'Latest Episodes',
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            GlassSwitch<bool>(
              value: _dub,
              width: 112,
              onChanged: (v) {
                setState(() => _dub = v);
                if (v && _items != null) _checkDubs(_items!, _token);
              },
              options: const [(false, 'Sub'), (true, 'Dub')],
            ),
            const SizedBox(width: 8),
            _Pager(page: _page, last: _last, onPage: _go),
          ]),
        ),
        const SizedBox(height: 16),
        if (_dub && items != null)
          Container(
            margin: const EdgeInsets.only(bottom: 16),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(12), border: Border.all(color: C.w(0.1))),
            child: Row(children: [
              if (_pending > 0) ...[const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white70)), const SizedBox(width: 10)],
              Expanded(
                child: Text(
                  _pending > 0
                      ? 'Checking which of these episodes have an English dub…'
                      : (shown != null && shown.isNotEmpty ? 'Showing only episodes that have an English dub on the servers.' : 'None of the latest episodes on this page have a dub yet. Try another page or switch back to Sub.'),
                  style: TextStyle(fontSize: 13.5, color: C.w(0.7)),
                ),
              ),
            ]),
          ),
        if (_error != null)
          ErrorBox(message: _error!, onRetry: _load)
        else if (shown == null)
          const PosterGridSkeleton()
        else
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 3, mainAxisSpacing: 20, crossAxisSpacing: 10, childAspectRatio: 0.45),
            itemCount: shown.length,
            itemBuilder: (context, i) {
              final it = shown[i];
              return EpisodeCard(
                item: it,
                onTap: () => _dub ? openWatch(context, it.media.id, episode: it.episode, dub: true) : openInfo(context, it.media.id),
              );
            },
          ),
      ]),
    );
  }
}

class _Pager extends StatelessWidget {
  const _Pager({required this.page, required this.last, required this.onPage});
  final int page;
  final int last;
  final ValueChanged<int> onPage;

  @override
  Widget build(BuildContext context) {
    Widget btn(IconData i, bool on, VoidCallback f) => GestureDetector(
          onTap: on ? f : null,
          child: Container(width: 28, height: 28, alignment: Alignment.center, child: Icon(i, size: 18, color: on ? C.w(0.85) : C.w(0.3))),
        );
    return Container(
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(99), border: Border.all(color: C.w(0.2))),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        btn(Icons.chevron_left_rounded, page > 1, () => onPage(page - 1)),
        Text('$page/$last', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, fontFeatures: [FontFeature.tabularFigures()])),
        btn(Icons.chevron_right_rounded, page < last, () => onPage(page + 1)),
      ]),
    );
  }
}

/* ---------------------------- top airing / upcoming ---------------------------- */

class _RankedPanel extends StatefulWidget {
  const _RankedPanel({required this.title, required this.dot, required this.top});
  final String title;
  final Color dot;
  final bool top;
  @override
  State<_RankedPanel> createState() => _RankedPanelState();
}

class _RankedPanelState extends State<_RankedPanel> {
  static const _step = 5;
  List<Anime>? _items;
  bool _failed = false;
  int _shown = _step;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _failed = false);
    try {
      final r = await (widget.top ? AniList.instance.topAiring() : AniList.instance.upcoming());
      if (mounted) setState(() => _items = r);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final items = _items;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Panel(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 4, 12),
            child: Row(children: [
              const Icon(Icons.chevron_right_rounded, size: 22),
              const SizedBox(width: 4),
              Text(widget.title.toUpperCase(), style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, letterSpacing: 0.6)),
            ]),
          ),
          if (_failed)
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(children: [
                Expanded(child: Text("Couldn't load this list.", style: TextStyle(color: C.w(0.6), fontSize: 14))),
                GlassButton(label: 'Try again', onTap: _load, height: 34, fontSize: 12),
              ]),
            )
          else if (items == null)
            for (var i = 0; i < _step; i++) const Padding(padding: EdgeInsets.only(bottom: 8), child: Skel(height: 96)),
          if (items != null)
            for (final a in items.take(_shown)) Padding(padding: const EdgeInsets.only(bottom: 8), child: _RankedRow(a: a, dot: widget.dot)),
          if (items != null && items.length > _step)
            GestureDetector(
              onTap: () => setState(() => _shown = _shown >= items.length ? _step : items.length),
              child: Container(
                height: 40,
                width: double.infinity,
                decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(12)),
                child: Icon(_shown >= items.length ? Icons.keyboard_arrow_up_rounded : Icons.keyboard_arrow_down_rounded, color: C.w(0.8)),
              ),
            ),
        ]),
      ),
    );
  }
}

class _RankedRow extends StatelessWidget {
  const _RankedRow({required this.a, required this.dot});
  final Anime a;
  final Color dot;

  @override
  Widget build(BuildContext context) {
    final eps = a.nextEpisode != null ? a.nextEpisode! - 1 : a.episodes;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => openInfo(context, a.id),
      child: Container(
        height: 96,
        decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(12)),
        clipBehavior: Clip.antiAlias,
        child: Stack(children: [
          // faint artwork on the right
          Positioned(
            right: 0,
            top: 0,
            bottom: 0,
            width: 220,
            child: ShaderMask(
              blendMode: BlendMode.dstIn,
              shaderCallback: (r) => const LinearGradient(begin: Alignment.centerRight, end: Alignment.centerLeft, colors: [Colors.black, Colors.transparent]).createShader(r),
              child: Opacity(
                opacity: 0.2,
                child: ColorFiltered(
                  colorFilter: const ColorFilter.matrix(<double>[0.33, 0.33, 0.33, 0, 0, 0.33, 0.33, 0.33, 0, 0, 0.33, 0.33, 0.33, 0, 0, 0, 0, 0, 1, 0]),
                  child: CachedNetworkImage(imageUrl: a.banner ?? a.poster ?? '', fit: BoxFit.cover, errorWidget: (_, __, ___) => const SizedBox()),
                ),
              ),
            ),
          ),
          Row(children: [
            SizedBox(width: 64, height: 96, child: CachedNetworkImage(imageUrl: a.thumb ?? '', fit: BoxFit.cover, errorWidget: (_, __, ___) => Container(color: C.w(0.1)))),
            const SizedBox(width: 12),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(right: 12),
                child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Padding(padding: const EdgeInsets.only(top: 6, right: 8), child: Container(width: 8, height: 8, decoration: BoxDecoration(color: dot, shape: BoxShape.circle))),
                    Expanded(child: Text(a.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14, height: 1.25, fontWeight: FontWeight.w700))),
                  ]),
                  const SizedBox(height: 8),
                  Padding(
                    padding: const EdgeInsets.only(left: 16),
                    child: Wrap(spacing: 4, runSpacing: 4, children: [
                      if (a.format != null) Chip2(a.format!.replaceAll('_', ' ')),
                      if (a.seasonYear != null) Chip2('${a.seasonYear}', icon: Icons.calendar_today_rounded),
                      if (eps != null && eps > 0) Chip2('$eps', icon: Icons.closed_caption_outlined),
                      if (a.averageScore != null) Chip2('${a.averageScore}', icon: Icons.star_rounded),
                    ]),
                  ),
                ]),
              ),
            ),
          ]),
        ]),
      ),
    );
  }
}

class _Footer extends StatelessWidget {
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 28, 16, 0),
        child: Column(children: [
          Divider(color: C.w(0.12)),
          const SizedBox(height: 14),
          Text('AniBiar does not host any video files. Streams are provided by third-party servers.', textAlign: TextAlign.center, style: TextStyle(fontSize: 12, height: 1.5, color: C.w(0.45))),
        ]),
      );
}
