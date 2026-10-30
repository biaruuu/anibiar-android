import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../services/anilist.dart';
import '../services/library.dart';
import 'glass.dart';

/// Poster image with the faint ring the website uses.
class Poster extends StatelessWidget {
  const Poster({super.key, this.url, this.radius = 12, this.aspect = 2 / 3, this.child, this.cacheWidth = 340});
  final String? url;
  final double radius;
  final double aspect;
  final Widget? child;
  final int cacheWidth;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: aspect,
      child: Container(
        decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(radius)),
        foregroundDecoration: BoxDecoration(borderRadius: BorderRadius.circular(radius), border: Border.all(color: C.w(0.12))),
        clipBehavior: Clip.antiAlias,
        child: Stack(fit: StackFit.expand, children: [
          if (url != null && url!.isNotEmpty) CachedNetworkImage(imageUrl: url!, fit: BoxFit.cover, memCacheWidth: cacheWidth, fadeInDuration: const Duration(milliseconds: 120), errorWidget: (_, __, ___) => const SizedBox()),
          if (child != null) child!,
        ]),
      ),
    );
  }
}

String clock(double seconds) {
  final s = seconds.isNaN || seconds < 0 ? 0 : seconds.floor();
  final h = s ~/ 3600;
  final m = (s % 3600) ~/ 60;
  final ss = s % 60;
  final mm = h > 0 ? m.toString().padLeft(2, '0') : '$m';
  return '${h > 0 ? '$h:' : ''}$mm:${ss.toString().padLeft(2, '0')}';
}

/// A card in the "Latest Episodes" grid.
class EpisodeCard extends StatelessWidget {
  const EpisodeCard({super.key, required this.item, required this.onTap});
  final LatestEpisode item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final a = item.media;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Poster(
          url: a.thumb,
          child: Stack(children: [
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Container(
                padding: const EdgeInsets.fromLTRB(8, 5, 8, 5),
                color: const Color(0xA6000000),
                child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                  Text('EP ${item.episode}', style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white)),
                  Text(timeAgo(item.airingAt), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white)),
                ]),
              ),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        ChipRow(children: [
          Chip2('${item.episode}', icon: Icons.closed_caption_outlined),
          Chip2(a.formatLabel),
          if (a.seasonYear != null) Chip2('${a.seasonYear}'),
        ]),
        const SizedBox(height: 6),
        Text(a.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12.5, height: 1.25, fontWeight: FontWeight.w600, color: Colors.white)),
      ]),
    );
  }
}

/// Poster + title (search results, watchlist, favorites).
class PosterTile extends StatelessWidget {
  const PosterTile({super.key, required this.title, this.image, required this.onTap, this.chips = const [], this.onRemove});
  final String title;
  final String? image;
  final VoidCallback onTap;
  final List<Widget> chips;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Poster(
          url: image,
          child: onRemove == null
              ? null
              : Align(
                  alignment: Alignment.topRight,
                  child: Padding(padding: const EdgeInsets.all(6), child: GlassIconButton(icon: Icons.close_rounded, size: 28, iconSize: 16, onTap: onRemove)),
                ),
        ),
        if (chips.isNotEmpty) ...[const SizedBox(height: 8), ChipRow(children: chips)],
        const SizedBox(height: 6),
        Text(title, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12.5, height: 1.25, fontWeight: FontWeight.w600, color: Colors.white)),
      ]),
    );
  }
}

/// A Continue Watching card: 16:9 picture, time left, red progress bar.
class HistoryCard extends StatelessWidget {
  const HistoryCard({super.key, required this.h, required this.onTap, required this.onRemove});
  final HistoryItem h;
  final VoidCallback onTap;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final pct = h.duration > 0 ? (h.position / h.duration).clamp(0.02, 1.0).toDouble() : 0.02;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Poster(
          url: h.image,
          aspect: 16 / 9,
          child: Stack(children: [
            if (h.duration > 0)
              Positioned(
                right: 8,
                bottom: 12,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(color: C.k(0.6), borderRadius: BorderRadius.circular(4)),
                  child: Text('${clock(h.duration - h.position)} left', style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.white)),
                ),
              ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Container(
                height: 4,
                color: C.w(0.25),
                alignment: Alignment.centerLeft,
                child: FractionallySizedBox(widthFactor: pct, child: Container(color: C.red)),
              ),
            ),
            Positioned(top: 8, right: 8, child: GlassIconButton(icon: Icons.close_rounded, size: 30, iconSize: 16, onTap: onRemove)),
          ]),
        ),
        const SizedBox(height: 8),
        Text(h.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white)),
        Text('Episode ${h.episode}${h.totalEpisodes != null ? ' / ${h.totalEpisodes}' : ''}${h.dub ? ' · Dub' : ''}', style: TextStyle(fontSize: 12, color: C.w(0.6))),
      ]),
    );
  }
}

/// Section heading with an optional trailing widget.
class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(children: [
      Expanded(child: Text(text, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w700, letterSpacing: -0.2, color: Colors.white))),
      if (trailing != null) trailing!,
    ]);
  }
}

/// Bordered panel like the website's rounded-2xl border-white/20 boxes.
class Panel extends StatelessWidget {
  const Panel({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.radius = 18});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  @override
  Widget build(BuildContext context) => Container(
        padding: padding,
        decoration: BoxDecoration(color: C.surface, borderRadius: BorderRadius.circular(radius), border: Border.all(color: C.line)),
        child: child,
      );
}

/// Error box with a Try again button.
class ErrorBox extends StatelessWidget {
  const ErrorBox({super.key, required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(28),
        decoration: BoxDecoration(color: C.surface, borderRadius: BorderRadius.circular(18), border: Border.all(color: C.line)),
        child: Column(children: [
          const Icon(Icons.error_outline_rounded, size: 32, color: Color(0xFFF87171)),
          const SizedBox(height: 10),
          Text(message, textAlign: TextAlign.center, style: TextStyle(color: C.w(0.8))),
          const SizedBox(height: 12),
          GlassButton(label: 'Try again', onTap: onRetry, height: 40),
        ]),
      );
}

class PosterGridSkeleton extends StatelessWidget {
  const PosterGridSkeleton({super.key, this.count = 12});
  final int count;
  @override
  Widget build(BuildContext context) => GridView.builder(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        gridDelegate: posterGrid(context),
        itemCount: count,
        itemBuilder: (_, __) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: const [
          AspectRatio(aspectRatio: 2 / 3, child: Skel()),
          SizedBox(height: 10),
          Skel(height: 12, width: 80, radius: 4),
          SizedBox(height: 6),
          Skel(height: 10, radius: 4),
        ]),
      );
}

/// Poster grid that adapts to the screen: 3 columns on phones, more on wider screens. Each row is exactly as tall
/// as its poster plus the chip line and a two line title, so nothing overflows whatever the width.
SliverGridDelegate posterGrid(BuildContext context, {double sidePadding = 32}) {
  final w = MediaQuery.of(context).size.width;
  final cols = w < 520 ? 3 : (w < 780 ? 4 : (w < 1040 ? 5 : 6));
  const gap = 10.0;
  final colW = (w - sidePadding - gap * (cols - 1)) / cols;
  return SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: cols, mainAxisSpacing: 16, crossAxisSpacing: gap, mainAxisExtent: colW * 1.5 + 76);
}
