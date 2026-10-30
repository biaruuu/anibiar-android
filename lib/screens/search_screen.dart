import 'dart:async';

import 'package:flutter/material.dart';

import '../core/nav.dart';
import '../core/theme.dart';
import '../services/anilist.dart';
import '../services/auth.dart';
import '../widgets/cards.dart';
import '../widgets/glass.dart';

class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key});
  @override
  State<SearchScreen> createState() => SearchScreenState();
}

class SearchScreenState extends State<SearchScreen> {
  static const _perPage = 24;
  final _ctrl = TextEditingController();
  final _focus = FocusNode();
  Timer? _debounce;
  String _q = '';
  List<Anime>? _items;
  List<Anime> _trending = const [];
  bool _hasNext = false;
  bool _loadingMore = false;
  String? _error;
  int _page = 1;
  int _token = 0;

  void focus() => Future.delayed(const Duration(milliseconds: 80), () {
        if (mounted) _focus.requestFocus();
      });

  @override
  void initState() {
    super.initState();
    AniList.instance.trending().then((t) {
      if (mounted) setState(() => _trending = t);
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _ctrl.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), () => _run(v.trim()));
  }

  Future<void> _run(String q) async {
    final token = ++_token;
    setState(() {
      _q = q;
      _error = null;
      _page = 1;
      _items = q.isEmpty ? const [] : null;
    });
    if (q.isEmpty) return;
    try {
      final r = await AniList.instance.searchPage(q, 1, _perPage);
      if (!mounted || token != _token) return;
      setState(() {
        _items = r.items;
        _hasNext = r.hasNext;
      });
    } catch (e) {
      if (mounted && token == _token) setState(() => _error = friendlyError(e));
    }
  }

  Future<void> _more() async {
    setState(() => _loadingMore = true);
    try {
      final r = await AniList.instance.searchPage(_q, _page + 1, _perPage);
      if (!mounted) return;
      setState(() {
        _items = [...?_items, ...r.items];
        _hasNext = r.hasNext;
        _page++;
      });
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.of(context).padding.top;
    final items = _items;
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onTap: () => FocusScope.of(context).unfocus(),
      child: ListView(
        padding: EdgeInsets.fromLTRB(16, top + 14, 16, 110),
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        children: [
          Glass(
            kind: GlassKind.panel,
            radius: 16,
            height: 52,
            padding: const EdgeInsets.symmetric(horizontal: 14),
            child: Row(children: [
              Icon(Icons.search_rounded, color: C.w(0.7)),
              const SizedBox(width: 10),
              Expanded(
                child: TextField(
                  controller: _ctrl,
                  focusNode: _focus,
                  onChanged: _onChanged,
                  onSubmitted: (v) => _run(v.trim()),
                  textInputAction: TextInputAction.search,
                  style: const TextStyle(fontSize: 16, color: Colors.white),
                  cursorColor: Colors.white,
                  decoration: InputDecoration(border: InputBorder.none, hintText: 'Search anime…', hintStyle: TextStyle(color: C.w(0.4))),
                ),
              ),
              if (_ctrl.text.isNotEmpty)
                GestureDetector(
                  onTap: () {
                    _ctrl.clear();
                    _run('');
                  },
                  child: Icon(Icons.close_rounded, color: C.w(0.7)),
                ),
            ]),
          ),
          const SizedBox(height: 22),
          if (_q.isEmpty) ...[
            if (_trending.isNotEmpty) ...[
              Row(children: [Icon(Icons.trending_up_rounded, size: 16, color: C.w(0.5)), const SizedBox(width: 6), Text('Trending now', style: TextStyle(fontSize: 12, color: C.w(0.5)))]),
              const SizedBox(height: 12),
              for (final a in _trending) _Row(a: a),
            ],
          ] else if (_error != null)
            ErrorBox(message: _error!, onRetry: () => _run(_q))
          else if (items == null)
            const PosterGridSkeleton(count: 9)
          else if (items.isEmpty)
            Padding(padding: const EdgeInsets.symmetric(vertical: 60), child: Center(child: Text('No results for "$_q".', style: TextStyle(color: C.w(0.6)))))
          else ...[
            Text('Results for "$_q"', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 16),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: posterGrid(context),
              itemCount: items.length,
              itemBuilder: (context, i) {
                final a = items[i];
                final eps = a.aired;
                return PosterTile(
                  title: a.name,
                  image: a.thumb,
                  onTap: () => openInfo(context, a.id),
                  chips: [
                    if (eps > 0) Chip2('$eps', icon: Icons.closed_caption_outlined),
                    if (a.averageScore != null) Chip2('${a.averageScore}', icon: Icons.star_rounded),
                  ],
                );
              },
            ),
            if (_hasNext)
              Padding(
                padding: const EdgeInsets.only(top: 20),
                child: Center(child: GlassButton(label: 'Load more', busy: _loadingMore, onTap: _more, height: 42)),
              ),
          ],
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.a});
  final Anime a;
  @override
  Widget build(BuildContext context) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => openInfo(context, a.id),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(children: [
            SizedBox(width: 48, child: Poster(url: a.thumb, radius: 8)),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(a.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700)),
                const SizedBox(height: 3),
                Text([a.formatLabel, if (a.seasonYear != null) '${a.seasonYear}', if (a.episodes != null) '${a.episodes} eps'].join(' · '), style: TextStyle(fontSize: 12, color: C.w(0.55))),
              ]),
            ),
          ]),
        ),
      );
}
