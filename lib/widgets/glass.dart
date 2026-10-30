import 'dart:ui';

import 'package:flutter/material.dart';

import '../core/theme.dart';

/// The website's glass styles (glass-btn, glass-white, glass-accent, glass-danger, glass-panel, glass-nav, glass-track).
enum GlassKind { btn, white, accent, danger, panel, nav, track, bar }

class _Spec {
  const _Spec(this.top, this.bottom, this.border, this.blur, this.highlight, this.shadow);
  final Color top;
  final Color bottom;
  final Color border;
  final double blur;
  final double highlight;
  final double shadow;
}

_Spec _spec(GlassKind k) {
  switch (k) {
    case GlassKind.btn:
      return _Spec(const Color.fromRGBO(84, 84, 92, 0.42), const Color.fromRGBO(22, 22, 26, 0.62), C.w(0.14), 18, 0.22, 0.4);
    case GlassKind.white:
      return _Spec(const Color.fromRGBO(255, 255, 255, 0.98), const Color.fromRGBO(226, 226, 232, 0.9), C.w(0.6), 18, 1, 0.4);
    case GlassKind.accent:
      return _Spec(const Color.fromRGBO(120, 132, 255, 0.95), const Color.fromRGBO(74, 86, 220, 0.9), C.w(0.25), 18, 0.45, 0.4);
    case GlassKind.danger:
      return _Spec(const Color.fromRGBO(255, 90, 90, 0.9), const Color.fromRGBO(200, 40, 50, 0.86), C.w(0.22), 18, 0.4, 0.4);
    case GlassKind.panel:
      return _Spec(const Color.fromRGBO(44, 44, 50, 0.72), const Color.fromRGBO(12, 12, 15, 0.84), C.w(0.14), 26, 0.2, 0.55);
    case GlassKind.nav:
      return _Spec(const Color.fromRGBO(70, 70, 76, 0.5), const Color.fromRGBO(22, 22, 25, 0.64), C.w(0.14), 24, 0.24, 0.65);
    case GlassKind.bar:
      // thin frosted glass of the website's video player
      return _Spec(const Color.fromRGBO(255, 255, 255, 0.14), const Color.fromRGBO(255, 255, 255, 0.07), C.w(0.16), 16, 0.22, 0.25);
    case GlassKind.track:
      return _Spec(const Color.fromRGBO(70, 70, 76, 0.45), const Color.fromRGBO(20, 20, 23, 0.62), C.w(0.14), 20, 0.2, 0.4);
  }
}

/// A frosted glass surface: blurred backdrop, soft gradient, light edge and a bright top highlight.
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
    final r = BorderRadius.circular(widget.radius);
    final surface = ClipRRect(
      borderRadius: r,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: s.blur / 2, sigmaY: s.blur / 2),
        child: Container(
          width: widget.width,
          height: widget.height,
          padding: widget.padding,
          alignment: widget.alignment,
          decoration: BoxDecoration(
            borderRadius: r,
            gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [s.top, s.bottom]),
            border: Border.all(color: s.border, width: 1),
          ),
          foregroundDecoration: BoxDecoration(
            borderRadius: r,
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.center,
              colors: [C.w(s.highlight * 0.35), const Color(0x00FFFFFF)],
            ),
          ),
          child: widget.child,
        ),
      ),
    );
    final shadowed = DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: r,
        boxShadow: [BoxShadow(color: C.k(s.shadow * 0.7), blurRadius: widget.kind == GlassKind.panel || widget.kind == GlassKind.nav ? 28 : 12, offset: const Offset(0, 5))],
      ),
      child: surface,
    );
    if (widget.onTap == null) return shadowed;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) => setState(() => _down = true),
      onTapUp: (_) => setState(() => _down = false),
      onTapCancel: () => setState(() => _down = false),
      onTap: widget.onTap,
      child: AnimatedScale(
        scale: _down ? 0.96 : 1,
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
        child: shadowed,
      ),
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

/// Segmented glass switch: dark glass track, white glass bubble that slides to the chosen option.
class GlassSwitch<T> extends StatelessWidget {
  const GlassSwitch({super.key, required this.value, required this.onChanged, required this.options, this.height = 36, this.width});

  final T value;
  final ValueChanged<T> onChanged;
  final List<(T, String)> options;
  final double height;
  final double? width;

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
          final inner = box.maxWidth - 6;
          final seg = inner / n;
          return Stack(children: [
            AnimatedPositioned(
              duration: const Duration(milliseconds: 300),
              curve: Curves.easeOutBack,
              left: 3 + index * seg,
              top: 3,
              bottom: 3,
              width: seg,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(999),
                  gradient: const LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color.fromRGBO(255, 255, 255, 0.98), Color.fromRGBO(226, 226, 232, 0.9)]),
                  boxShadow: [BoxShadow(color: C.k(0.45), blurRadius: 8, offset: const Offset(0, 3))],
                ),
              ),
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
                        duration: const Duration(milliseconds: 200),
                        style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: o.$1 == value ? Colors.black : C.w(0.75)),
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

/// Dark rounded pill used on posters (episode number, rating, time left).
class Chip2 extends StatelessWidget {
  const Chip2(this.text, {super.key, this.icon, this.glass = false});
  final String text;
  final IconData? icon;
  final bool glass;

  @override
  Widget build(BuildContext context) {
    final inner = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) ...[Icon(icon, size: 11, color: C.w(0.75)), const SizedBox(width: 3)],
        Text(text, style: TextStyle(fontSize: 10.5, height: 1, color: C.w(0.75))),
      ]),
    );
    if (glass) return Glass(kind: GlassKind.btn, radius: 999, child: inner);
    return DecoratedBox(decoration: BoxDecoration(color: C.w(0.07), borderRadius: BorderRadius.circular(5)), child: inner);
  }
}

/// Bottom sheet styled like the website's glass panels.
Future<T?> showGlassSheet<T>(BuildContext context, {required WidgetBuilder builder, bool scroll = true}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: scroll,
    backgroundColor: Colors.transparent,
    barrierColor: C.k(0.7),
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
