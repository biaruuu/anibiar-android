import 'package:flutter/material.dart';

import '../screens/info_screen.dart';
import '../screens/login_screen.dart';
import '../screens/watch_screen.dart';

Route<T> _route<T>(Widget page) => PageRouteBuilder<T>(
      pageBuilder: (_, __, ___) => page,
      transitionDuration: const Duration(milliseconds: 220),
      reverseTransitionDuration: const Duration(milliseconds: 180),
      transitionsBuilder: (_, a, __, child) {
        final curve = CurvedAnimation(parent: a, curve: Curves.easeOutCubic);
        return FadeTransition(opacity: curve, child: SlideTransition(position: Tween(begin: const Offset(0, 0.03), end: Offset.zero).animate(curve), child: child));
      },
    );

Future<void> openInfo(BuildContext context, int id) => Navigator.of(context).push(_route<void>(InfoScreen(id: id)));

Future<void> openWatch(BuildContext context, int id, {int episode = 1, bool dub = false}) =>
    Navigator.of(context).push(_route<void>(WatchScreen(id: id, episode: episode, dub: dub)));

Future<void> openLogin(BuildContext context) => Navigator.of(context).push(_route<void>(const LoginScreen()));

void toast(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message), duration: const Duration(seconds: 3)));
}
