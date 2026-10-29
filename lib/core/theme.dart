import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Discord "Onyx" dark theme, same tokens as the website.
class C {
  static const bg = Color(0xFF000000);
  static const fg = Color(0xFFF2F3F5);
  static const card = Color(0xFF0B0B0D);
  static const secondary = Color(0xFF141416);
  static const muted = Color(0xFF949BA4);
  static const border = Color(0xFF26272B);
  static const input = Color(0xFF2B2D31);
  static const accent = Color(0xFF5865F2);
  static const lime = Color(0xFFBEF264);
  static const red = Color(0xFFDC2626);
  static const green = Color(0xFF22C55E);
  static const orange = Color(0xFFFB923C);

  /// White with an alpha (0..1), like Tailwind's bg-white/10.
  static Color w(double a) => Color.fromRGBO(255, 255, 255, a);
  static Color k(double a) => Color.fromRGBO(0, 0, 0, a);
}

ThemeData buildTheme() {
  final base = ThemeData(
    brightness: Brightness.dark,
    useMaterial3: true,
    scaffoldBackgroundColor: C.bg,
    colorScheme: const ColorScheme.dark(
      primary: C.fg,
      onPrimary: Colors.black,
      secondary: C.accent,
      surface: C.card,
      onSurface: C.fg,
      error: Color(0xFFF87171),
    ),
    splashFactory: NoSplash.splashFactory,
    highlightColor: Colors.transparent,
    dividerColor: C.border,
  );
  TextTheme text;
  try {
    text = GoogleFonts.karlaTextTheme(base.textTheme).apply(bodyColor: C.fg, displayColor: C.fg);
  } catch (_) {
    text = base.textTheme.apply(bodyColor: C.fg, displayColor: C.fg);
  }
  return base.copyWith(
    textTheme: text,
    appBarTheme: const AppBarTheme(backgroundColor: C.bg, elevation: 0, scrolledUnderElevation: 0),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: const Color(0xFF1B1B1F),
      contentTextStyle: const TextStyle(color: C.fg),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
  );
}

/// Poppins, used for captions and a few headings.
TextStyle poppins({double size = 14, FontWeight weight = FontWeight.w500, Color color = C.fg, double? height}) {
  try {
    return GoogleFonts.poppins(fontSize: size, fontWeight: weight, color: color, height: height);
  } catch (_) {
    return TextStyle(fontSize: size, fontWeight: weight, color: color, height: height);
  }
}
