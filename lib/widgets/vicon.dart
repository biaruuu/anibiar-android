import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// Vidstack media-icons (MIT), the same SVG icons the website's video player uses.
/// `name` is the file name in assets/icons without ".svg", for example `play` or `seek_forward_10`.
class VIcon extends StatelessWidget {
  const VIcon(this.name, {super.key, this.size = 24, this.color = Colors.white, this.shadow = false});
  final String name;
  final double size;
  final Color color;
  final bool shadow;

  @override
  Widget build(BuildContext context) {
    final icon = SvgPicture.asset('assets/icons/$name.svg', width: size, height: size, colorFilter: ColorFilter.mode(color, BlendMode.srcIn));
    if (!shadow) return icon;
    // soft drop shadow like the website's drop-shadow-[0_1px_2px_rgba(0,0,0,0.6)]
    return Stack(children: [
      Transform.translate(offset: const Offset(0, 1), child: SvgPicture.asset('assets/icons/$name.svg', width: size, height: size, colorFilter: const ColorFilter.mode(Color(0x99000000), BlendMode.srcIn))),
      icon,
    ]);
  }
}
