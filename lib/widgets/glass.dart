import 'package:flutter/material.dart';

import '../core/theme.dart';

/// Surface styles. The names stay (btn, white, accent, ...) but every surface is now flat Onyx:
/// a solid fill and a hairline border, no blur and no gradient.
enum GlassKind { btn, white, accent, danger, panel, nav, track, bar }

class _Spec {
  const _Spec(this.fill, this.border);
  final Color fill;
  final Color border;
}

_Spec _spec(GlassKind k) {
  switch (k) {
    case GlassKind.btn:
      return const _Spec(C.raised, C.line);
    case GlassKind.white:
      return const _Spec(Color(0xFFF2F3F5), Color(0xFFF2F3F5));
    case GlassKind.accent:
      return const _Spec(C.accent, C.accent);
    case GlassKind.danger:
      return const _Spec(C.red, C.red);
    case GlassKind.panel:
      return const _Spec(C.surface, C.line);
    case GlassKind.nav:
      return const _Spec(Color(0xF2111113), C.line);
    case GlassKind.bar:
      return const _Spec(Color(0x8C000000), Color(0x1FFFFFFF));
    case GlassKind.track:
      return const _Spec(Color(0xFF0E0E10), C.line);
  }
}

/// A flat surface with rounded corners, a hairline border and a gentle press animation.
class Glass extends StatefulWidget {
  const Glass({
    super.key,
    this.kind = GlassKind.btn,
    this.radius = 999,
    this.width,
    this.height,
    this.padding,
    this.onTap,
    this.child,
    this.alignment,
  });

  final GlassKind kind;
  final double radius;
  final double? width;
  final double? height;
  final EdgeInsetsGeometry? padding;
  final VoidCallback? onTap;
  final Widget? child;
  final AlignmentGeometry? alignment;

  @override
  State<Glass> createState() => _GlassState();
}

class _GlassState extends State<Glass> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final s = _spec(widget.kind);
    final box = Container(
      width: widget.width,
      height: widget.height,
      padding: widget.padding,
      alignment: widget.alignment,
      decoration: BoxDecoration(color: s.fill, borderRadius: BorderRadius.circular(widget.radius), border: Border.all(color: s.border, width: 1)),
      child: widget.child,
    );
    if (widget.onTap == null) return box;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) => setState(() => _down = true),
      onTapUp: (_) => setState(() => _down = false),
      onTapCancel: () => setState(() => _down = false),
      onTap: widget.onTap,
      child: AnimatedScale(scale: _down ? 0.96 : 1, duration: const Duration(milliseconds: 110), curve: Curves.easeOut, child: box),
    );
  }
}

/// Text colour that reads on a glass kind.
Color glassInk(GlassKind k) => k == GlassKind.white ? Colors.black : Colors.white;

/// Rounded glass button with an optional icon and label (the website's Button).
class GlassButton extends StatelessWidget {
  const GlassButton({
    super.key,
    required this.label,
    this.icon,
    this.onTap,
    this.kind = GlassKind.btn,
    this.height = 44,
    this.radius = 999,
    this.expand = false,
    this.busy = false,
    this.fontSize = 14,
    this.padding = const EdgeInsets.symmetric(horizontal: 20),
  });

  final String label;
  final IconData? icon;
  final VoidCallback? onTap;
  final GlassKind kind;
  final double height;
  final double radius;
  final bool expand;
  final bool busy;
  final double fontSize;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final ink = glassInk(kind);
    final enabled = onTap != null && !busy;
    final content = Row(
      mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        if (busy)
          SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: ink))
        else if (icon != null)
          Icon(icon, size: 18, color: ink),
        if ((busy || icon != null) && label.isNotEmpty) const SizedBox(width: 8),
        if (label.isNotEmpty)
          Flexible(
            child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: ink, fontWeight: FontWeight.w700, fontSize: fontSize)),
          ),
      ],
    );
    return Opacity(
      opacity: onTap == null && !busy ? 0.5 : 1,
      child: Glass(kind: kind, radius: radius, height: height, padding: padding, onTap: enabled ? onTap : null, child: content),
    );
  }
}

/// Round glass icon button (close, back, menu, share…).
class GlassIconButton extends StatelessWidget {
  const GlassIconButton({super.key, required this.icon, required this.onTap, this.size = 44, this.iconSize = 22, this.kind = GlassKind.btn, this.tooltip, this.radius});

  final IconData icon;
  final VoidCallback? onTap;
  final double size;
  final double iconSize;
  final GlassKind kind;
  final String? tooltip;
  final double? radius;

  @override
  Widget build(BuildContext context) {
    final w = Glass(
      kind: kind,
      radius: radius ?? size / 2,
      width: size,
      height: size,
      onTap: onTap,
      alignment: Alignment.center,
      child: Icon(icon, size: iconSize, color: glassInk(kind)),
    );
    return tooltip == null ? w : Tooltip(message: tooltip!, child: w);
  }
}

/// Segmented switch: dark track, white pill that slides to the chosen option. Sized to fit small phones.
class GlassSwitch<T> extends StatelessWidget {
  const GlassSwitch({super.key, required this.value, required this.onChanged, required this.options, this.height = 32, this.width, this.fontSize = 12.5});

  final T value;
  final ValueChanged<T> onChanged;
  final List<(T, String)> options;
  final double height;
  final double? width;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    final n = options.length;
    final index = options.indexWhere((o) => o.$1 == value).clamp(0, n - 1).toInt();
    return SizedBox(
      width: width,
      height: height,
      child: Glass(
        kind: GlassKind.track,
        radius: 999,
        child: LayoutBuilder(builder: (context, box) {
          final seg = (box.maxWidth - 6) / n;
          return Stack(children: [
            AnimatedPositioned(
              duration: const Duration(milliseconds: 260),
              curve: Curves.easeOutCubic,
              left: 3 + index * seg,
              top: 3,
              bottom: 3,
              width: seg,
              child: DecoratedBox(decoration: BoxDecoration(borderRadius: BorderRadius.circular(999), color: const Color(0xFFF2F3F5))),
            ),
            Row(children: [
              const SizedBox(width: 3),
              for (final o in options)
                Expanded(
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => onChanged(o.$1),
                    child: Center(
                      child: AnimatedDefaultTextStyle(
                        duration: const Duration(milliseconds: 180),
                        style: TextStyle(fontSize: fontSize, fontWeight: FontWeight.w700, color: o.$1 == value ? Colors.black : C.w(0.7)),
                        child: Text(o.$2),
                      ),
                    ),
                  ),
                ),
              const SizedBox(width: 3),
            ]),
          ]);
        }),
      ),
    );
  }
}

/// Small flat pill used on posters (episode, format, year).
class Chip2 extends StatelessWidget {
  const Chip2(this.text, {super.key, this.icon, this.glass = false});
  final String text;
  final IconData? icon;
  final bool glass;

  @override
  Widget build(BuildContext context) {
    final inner = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3.5),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) ...[Icon(icon, size: 10.5, color: C.w(0.75)), const SizedBox(width: 3)],
        Text(text, maxLines: 1, style: TextStyle(fontSize: 10, height: 1, fontWeight: FontWeight.w600, color: C.w(0.78))),
      ]),
    );
    return DecoratedBox(
      decoration: BoxDecoration(color: glass ? C.raised : C.w(0.07), borderRadius: BorderRadius.circular(glass ? 999 : 6), border: glass ? Border.all(color: C.line) : null),
      child: inner,
    );
  }
}

/// A row of chips that always stays on one line: it shrinks to fit narrow cards instead of wrapping.
class ChipRow extends StatelessWidget {
  const ChipRow({super.key, required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: double.infinity,
        child: FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Row(mainAxisSize: MainAxisSize.min, children: [for (var i = 0; i < children.length; i++) ...[if (i > 0) const SizedBox(width: 4), children[i]]]),
        ),
      );
}

/// Bottom sheet styled like the website's glass panels.
Future<T?> showGlassSheet<T>(BuildContext context, {required WidgetBuilder builder, bool scroll = true}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: scroll,
    backgroundColor: Colors.transparent,
    barrierColor: C.k(0.72),
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: Glass(kind: GlassKind.panel, radius: 24, child: SafeArea(top: false, child: builder(ctx))),
    ),
  );
}

/// Grey placeholder block while data loads.
class Skel extends StatelessWidget {
  const Skel({super.key, this.width, this.height, this.radius = 12});
  final double? width;
  final double? height;
  final double radius;
  @override
  Widget build(BuildContext context) => Container(width: width, height: height, decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(radius)));
}
