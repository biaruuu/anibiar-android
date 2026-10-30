import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/nav.dart';
import '../core/theme.dart';
import '../services/anilist.dart';
import '../services/auth.dart';
import '../services/library.dart';
import '../services/tmdb.dart';
import '../widgets/cards.dart';
import '../widgets/glass.dart';

class InfoScreen extends StatefulWidget {
  const InfoScreen({super.key, required this.id});
  final int id;
  @override
  State<InfoScreen> createState() => _InfoScreenState();
}

class _InfoScreenState extends State<InfoScreen> {
  Detail? _d;
  String? _error;
  bool _expanded = false;
  bool _loved = false;
  bool _saved = false;
  HistoryItem? _resume;

  @override
  void initState() {
    super.initState();
    _load();
    _flags();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final d = await AniList.instance.detail(widget.id);
      if (!mounted) return;
      if (d == null) {
        setState(() => _error = 'Anime not found.');
      } else {
        setState(() => _d = d);
      }
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
  }

  Future<void> _flags() async {
    final lib = Library.instance;
    final l = await lib.load(Collection.list);
    final f = await lib.load(Collection.favorites);
    final h = await lib.localHistory();
    if (!mounted) return;
    setState(() {
      _saved = l.any((x) => x.animeId == widget.id);
      _loved = f.any((x) => x.animeId == widget.id);
      _resume = h.where((x) => x.animeId == widget.id && !x.finished).cast<HistoryItem?>().firstWhere((_) => true, orElse: () => null);
    });
  }

  Future<void> _toggle(Collection c) async {
    final a = _d?.anime;
    if (a == null) return;
    final now = await Library.instance.toggle(c, animeId: a.id, title: a.name, image: a.thumb);
    if (!mounted) return;
    setState(() => c == Collection.favorites ? _loved = now : _saved = now);
    toast(context, c == Collection.favorites ? (now ? 'Added to favorites' : 'Removed from favorites') : (now ? 'Added to your watchlist' : 'Removed from your watchlist'));
  }

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.of(context).padding.top;
    final d = _d;
    return Scaffold(
      backgroundColor: C.bg,
      body: Stack(children: [
        if (d == null)
          _error != null
              ? Center(child: Padding(padding: const EdgeInsets.all(24), child: ErrorBox(message: _error!, onRetry: _load)))
              : const Center(child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white54))
        else
          _content(d, top),
        Positioned(top: top + 8, left: 12, child: GlassIconButton(icon: Icons.arrow_back_rounded, size: 42, onTap: () => Navigator.of(context).maybePop(), tooltip: 'Back')),
      ]),
    );
  }

  Widget _content(Detail d, double top) {
    final a = d.anime;
    final desc = stripHtml(a.description ?? '');
    final status = statusName(a.status);
    final rows = <(String, String)>[
      ('Format', formatName(a.format)),
      ('Status', status),
      ('Episodes', a.episodes?.toString() ?? (a.nextEpisode != null ? '${a.nextEpisode! - 1}+' : '?')),
      if (d.duration != null) ('Duration', '${d.duration} min'),
      if (d.season != null && a.seasonYear != null) ('Season', '${d.season![0]}${d.season!.substring(1).toLowerCase()} ${a.seasonYear}'),
      if (!d.start.empty) ('Aired', d.start.toString()),
      if (!d.end.empty) ('Ended', d.end.toString()),
      if (d.studio != null) ('Studio', d.studio!),
      if (a.averageScore != null) ('Score', '${a.averageScore}%'),
    ];
    return RefreshIndicator(
      onRefresh: () async {
        await _load();
        await _flags();
      },
      child: ListView(padding: EdgeInsets.zero, children: [
        Stack(children: [
        // banner
        SizedBox(
          height: 230 + top,
          width: double.infinity,
          child: Stack(fit: StackFit.expand, children: [
            CachedNetworkImage(imageUrl: a.banner ?? a.poster ?? '', fit: BoxFit.cover, memCacheWidth: 900, errorWidget: (_, __, ___) => const SizedBox()),
            Container(color: C.k(0.5)),
          ]),
        ),
        Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(height: 166 + top),
        Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                SizedBox(width: 118, child: Poster(url: a.poster, radius: 14, cacheWidth: 420)),
                const SizedBox(width: 14),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(a.name, maxLines: 4, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 21, height: 1.15, fontWeight: FontWeight.w800, letterSpacing: -0.3)),
                      if (a.title.native != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(a.title.native!, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 12.5, color: C.w(0.5)))),
                    ]),
                  ),
                ),
              ]),
              const SizedBox(height: 14),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final c in [formatName(a.format), status, if (a.seasonYear != null) '${a.seasonYear}', if (a.averageScore != null) '★ ${a.averageScore}'])
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                    decoration: BoxDecoration(color: C.w(0.08), borderRadius: BorderRadius.circular(99), border: Border.all(color: C.w(0.15))),
                    child: Text(c, style: TextStyle(fontSize: 12, color: C.w(0.9))),
                  ),
              ]),
              const SizedBox(height: 16),
              Row(children: [
                Expanded(
                  child: GlassButton(
                    label: _resume != null ? 'Continue EP ${_resume!.episode}' : 'Watch Now',
                    icon: Icons.play_arrow_rounded,
                    kind: GlassKind.white,
                    height: 46,
                    radius: 12,
                    onTap: () async {
                      await openWatch(context, a.id, episode: _resume?.episode ?? 1, dub: _resume?.dub ?? false);
                      _flags();
                    },
                  ),
                ),
                const SizedBox(width: 10),
                GlassIconButton(icon: _loved ? Icons.favorite_rounded : Icons.favorite_border_rounded, size: 46, radius: 12, onTap: () => _toggle(Collection.favorites), tooltip: _loved ? 'Remove from favorites' : 'Add to favorites'),
                const SizedBox(width: 10),
                GlassIconButton(icon: _saved ? Icons.bookmark_rounded : Icons.bookmark_border_rounded, size: 46, radius: 12, onTap: () => _toggle(Collection.list), tooltip: _saved ? 'Remove from watchlist' : 'Add to watchlist'),
              ]),
              if (a.genres.isNotEmpty) ...[
                const SizedBox(height: 18),
                Wrap(spacing: 8, runSpacing: 8, children: [for (final g in a.genres) Chip2(g, glass: true)]),
              ],
              if (desc.isNotEmpty) ...[
                const SizedBox(height: 20),
                const Text('Synopsis', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                GestureDetector(
                  onTap: () => setState(() => _expanded = !_expanded),
                  child: AnimatedSize(
                    duration: const Duration(milliseconds: 200),
                    alignment: Alignment.topCenter,
                    child: Text(desc, maxLines: _expanded ? null : 5, overflow: _expanded ? TextOverflow.visible : TextOverflow.ellipsis, style: TextStyle(fontSize: 14.5, height: 1.5, color: C.w(0.75))),
                  ),
                ),
                if (desc.length > 260)
                  GestureDetector(
                    onTap: () => setState(() => _expanded = !_expanded),
                    child: Padding(padding: const EdgeInsets.only(top: 6), child: Text(_expanded ? 'Show less' : 'Show more', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Colors.white))),
                  ),
              ],
              const SizedBox(height: 22),
              const Text('Details', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
              const SizedBox(height: 10),
              Panel(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
                child: Column(children: [
                  for (var i = 0; i < rows.length; i++)
                    Container(
                      padding: const EdgeInsets.symmetric(vertical: 11),
                      decoration: BoxDecoration(border: i == 0 ? null : Border(top: BorderSide(color: C.w(0.08)))),
                      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        SizedBox(width: 92, child: Text(rows[i].$1, style: TextStyle(fontSize: 13.5, color: C.w(0.5)))),
                        Expanded(child: Text(rows[i].$2, style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600))),
                      ]),
                    ),
                ]),
              ),
              if (d.trailerId != null && d.trailerSite == 'youtube') ...[
                const SizedBox(height: 14),
                GlassButton(
                  label: 'Watch trailer',
                  icon: Icons.smart_display_rounded,
                  expand: true,
                  height: 46,
                  radius: 12,
                  onTap: () => launchUrl(Uri.parse('https://www.youtube.com/watch?v=${d.trailerId}'), mode: LaunchMode.externalApplication),
                ),
              ],
              _Gallery(anime: a),
              if (d.relations.isNotEmpty) ...[
                const SizedBox(height: 26),
                const SectionTitle('Seasons & Related'),
                const SizedBox(height: 12),
              ],
            ]),
          ),
        if (d.relations.isNotEmpty) _Rail(items: [for (final r in d.relations) (r.media, r.type)], onTap: (m) => openInfo(context, m.id)),
        if (d.recommendations.isNotEmpty) ...[
          const Padding(padding: EdgeInsets.fromLTRB(16, 22, 16, 12), child: SectionTitle('You might also like')),
          _Rail(items: [for (final r in d.recommendations) (r, null)], onTap: (m) => openInfo(context, m.id)),
        ],
        ]),
        ]),
        const SizedBox(height: 40),
      ]),
    );
  }
}

/// "Artwork & Posters": the banner and poster from AniList plus backgrounds, posters and logos from TMDB.
class _Gallery extends StatefulWidget {
  const _Gallery({required this.anime});
  final Anime anime;
  @override
  State<_Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<_Gallery> {
  List<Artwork> _extra = const [];
  String _filter = 'All';
  bool _open = false;

  @override
  void initState() {
    super.initState();
    final a = widget.anime;
    Tmdb.instance.artwork(a.title.english ?? a.title.romaji ?? a.name, a.format == 'MOVIE').then((l) {
      if (mounted) setState(() => _extra = l);
    });
  }

  @override
  Widget build(BuildContext context) {
    final a = widget.anime;
    final images = <Artwork>[
      if (a.banner != null) Artwork('Banners', a.banner!),
      if (a.poster != null) Artwork('Posters', a.poster!),
      ..._extra,
    ];
    if (images.length < 2) return const SizedBox();
    const order = ['Backgrounds', 'Banners', 'Posters', 'Logos'];
    final kinds = [for (final k in order) if (images.any((i) => i.kind == k)) k];
    if (!kinds.contains(_filter)) _filter = 'All';
    final all = _filter == 'All' ? images : images.where((i) => i.kind == _filter).toList();
    final list = all.take(80).toList();
    final width = MediaQuery.of(context).size.width - 32;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: 26),
      const SectionTitle('Artwork & Posters'),
      const SizedBox(height: 4),
      Text('${all.length} images', style: TextStyle(fontSize: 12.5, color: C.w(0.55))),
      const SizedBox(height: 12),
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: [
          for (final k in ['All', ...kinds])
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Glass(
                kind: _filter == k ? GlassKind.white : GlassKind.btn,
                radius: 999,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                onTap: () => setState(() => _filter = k),
                child: Text(k, style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700, color: _filter == k ? Colors.black : Colors.white)),
              ),
            ),
        ]),
      ),
      const SizedBox(height: 12),
      if (_open)
        GridView.count(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisCount: 2,
          mainAxisSpacing: 10,
          crossAxisSpacing: 10,
          childAspectRatio: 1.45,
          children: [
            for (final i in list)
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: ColoredBox(
                  color: C.w(0.05),
                  child: CachedNetworkImage(imageUrl: i.url, fit: i.kind == 'Logos' ? BoxFit.contain : BoxFit.cover, memCacheWidth: 500, errorWidget: (_, __, ___) => const SizedBox()),
                ),
              ),
          ],
        )
      else if (list.isNotEmpty)
        ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: SizedBox(
            width: width,
            height: width * 0.56,
            child: CachedNetworkImage(imageUrl: list.first.url, fit: list.first.kind == 'Logos' ? BoxFit.contain : BoxFit.cover, memCacheWidth: 800, errorWidget: (_, __, ___) => const SizedBox()),
          ),
        ),
      if (list.length > 1)
        Center(
          child: TextButton(
            onPressed: () => setState(() => _open = !_open),
            child: Text(_open ? 'Show less' : 'View all ${all.length} images', style: TextStyle(color: C.w(0.65))),
          ),
        ),
    ]);
  }
}

class _Rail extends StatelessWidget {
  const _Rail({required this.items, required this.onTap});
  final List<(Anime, String?)> items;
  final ValueChanged<Anime> onTap;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: 214,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          itemCount: items.length,
          separatorBuilder: (_, __) => const SizedBox(width: 10),
          itemBuilder: (context, i) {
            final (a, tag) = items[i];
            return SizedBox(
              width: 104,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => onTap(a),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Poster(
                    url: a.thumb,
                    child: tag == null || tag.isEmpty
                        ? null
                        : Align(
                            alignment: Alignment.topLeft,
                            child: Padding(padding: const EdgeInsets.all(6), child: Chip2(tag[0] + tag.substring(1).toLowerCase(), glass: true)),
                          ),
                  ),
                  const SizedBox(height: 6),
                  Text(a.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, height: 1.25, fontWeight: FontWeight.w600)),
                ]),
              ),
            );
          },
        ),
      );
}
