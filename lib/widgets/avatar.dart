import 'dart:async';
import 'dart:ui' as ui;

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../services/api.dart';

/// Profile picture, or a grey disc when there is none.
class Avatar extends StatelessWidget {
  const Avatar({super.key, this.url, this.size});
  final String? url;
  final double? size;

  @override
  Widget build(BuildContext context) {
    final w = url == null || url!.isEmpty
        ? Container(color: C.input, child: Icon(Icons.person_rounded, color: C.w(0.5), size: (size ?? 40) * 0.55))
        : CachedNetworkImage(imageUrl: url!, fit: BoxFit.cover, errorWidget: (_, __, ___) => Container(color: C.input));
    return SizedBox(width: size, height: size, child: ClipOval(child: w));
  }
}

/// The animated decoration. The website uses animated PNGs; here the sprite sheet of the same
/// decoration is played frame by frame (the decoration list comes from the same Supabase table).
class _SpriteSheet {
  _SpriteSheet(this.image);
  final ui.Image image;
}

final Map<String, Future<ui.Image>> _sheets = {};

Future<ui.Image> _loadSheet(String url) {
  return _sheets.putIfAbsent(url, () {
    final c = Completer<ui.Image>();
    final stream = CachedNetworkImageProvider(url).resolve(ImageConfiguration.empty);
    late ImageStreamListener l;
    l = ImageStreamListener((info, _) {
      if (!c.isCompleted) c.complete(info.image.clone());
      stream.removeListener(l);
    }, onError: (e, st) {
      if (!c.isCompleted) c.completeError(e, st);
      stream.removeListener(l);
      _sheets.remove(url);
    });
    stream.addListener(l);
    return c.future;
  });
}

class _SheetPainter extends CustomPainter {
  _SheetPainter(this.sheet, this.deco, this.frame);
  final ui.Image sheet;
  final AvatarDeco deco;
  final int frame;

  @override
  void paint(Canvas canvas, Size size) {
    final fs = deco.frameSize.toDouble();
    final col = frame % deco.cols;
    final row = frame ~/ deco.cols;
    canvas.drawImageRect(
      sheet,
      Rect.fromLTWH(col * fs, row * fs, fs, fs),
      Offset.zero & size,
      Paint()..filterQuality = FilterQuality.high,
    );
  }

  @override
  bool shouldRepaint(covariant _SheetPainter old) => old.frame != frame || old.sheet != sheet;
}

class DecoLayer extends StatefulWidget {
  const DecoLayer({super.key, required this.deco, this.still = false});
  final AvatarDeco deco;
  final bool still;

  @override
  State<DecoLayer> createState() => _DecoLayerState();
}

class _DecoLayerState extends State<DecoLayer> with SingleTickerProviderStateMixin {
  ui.Image? _img;
  late final AnimationController _ctrl = AnimationController(vsync: this, duration: Duration(milliseconds: (widget.deco.frames / widget.deco.fps * 1000).round()));

  @override
  void initState() {
    super.initState();
    _loadSheet(widget.deco.sheet).then((i) {
      if (!mounted) return;
      setState(() => _img = i);
      if (!widget.still) _ctrl.repeat();
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final img = _img;
    if (img == null) return const SizedBox.shrink();
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (_, __) {
        final f = widget.still ? 0 : (_ctrl.value * widget.deco.frames).floor().clamp(0, widget.deco.frames - 1).toInt();
        return CustomPaint(painter: _SheetPainter(img, widget.deco, f));
      },
    );
  }
}

/// Avatar with a thin black ring and an animated decoration on top, fitted the way Discord does it:
/// the decoration is drawn `scale` times the avatar picture, centred on it.
class DecoratedAvatar extends StatelessWidget {
  const DecoratedAvatar({super.key, required this.size, required this.child, this.deco, this.still = false});
  final double size;
  final Widget child;
  final AvatarDeco? deco;
  final bool still;

  @override
  Widget build(BuildContext context) {
    final pic = size - 8;
    final d = deco;
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.center,
        children: [
          Container(decoration: const BoxDecoration(color: Colors.black, shape: BoxShape.circle)),
          Positioned.fill(child: Padding(padding: const EdgeInsets.all(4), child: ClipOval(child: child))),
          if (d != null)
            Positioned(
              left: (size - pic * d.scale) / 2,
              top: (size - pic * d.scale) / 2,
              width: pic * d.scale,
              height: pic * d.scale,
              child: IgnorePointer(child: DecoLayer(key: ValueKey('${d.id}$still'), deco: d, still: still)),
            ),
        ],
      ),
    );
  }
}

AvatarDeco? findDeco(String? id, List<AvatarDeco> all) {
  if (id == null) return null;
  for (final d in all) {
    if (d.id == id) return d;
  }
  return null;
}

/// Loads the decoration list once and rebuilds when it arrives.
class DecoCatalog extends StatefulWidget {
  const DecoCatalog({super.key, required this.builder});
  final Widget Function(BuildContext, List<AvatarDeco>) builder;
  @override
  State<DecoCatalog> createState() => _DecoCatalogState();
}

class _DecoCatalogState extends State<DecoCatalog> {
  List<AvatarDeco> _list = const [];
  @override
  void initState() {
    super.initState();
    SiteApi.instance.decorations().then((l) {
      if (mounted) setState(() => _list = l);
    });
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, _list);
}

/// Grid of None + every decoration (the website's DecorationPicker).
class DecorationPicker extends StatelessWidget {
  const DecorationPicker({super.key, required this.value, required this.onChanged});
  final String? value;
  final ValueChanged<String?> onChanged;

  @override
  Widget build(BuildContext context) {
    return DecoCatalog(builder: (context, list) {
      final items = <(String?, String, AvatarDeco?)>[(null, 'None', null), for (final d in list) (d.id, d.label, d)];
      return GridView.count(
        crossAxisCount: 4,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: 8,
        crossAxisSpacing: 8,
        childAspectRatio: 0.82,
        children: [
          for (final it in items)
            GestureDetector(
              onTap: () => onChanged(it.$1),
              child: Container(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: value == it.$1 ? C.accent : C.w(0.2)),
                  color: value == it.$1 ? const Color(0x1A5865F2) : Colors.transparent,
                ),
                padding: const EdgeInsets.fromLTRB(4, 12, 4, 8),
                child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                  DecoratedAvatar(size: 48, deco: it.$3, still: true, child: Container(color: C.input)),
                  const SizedBox(height: 8),
                  Text(it.$2, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: value == it.$1 ? Colors.white : C.muted)),
                ]),
              ),
            ),
        ],
      );
    });
  }
}
