import 'package:flutter/material.dart';

import '../core/nav.dart';
import '../core/theme.dart';
import '../services/auth.dart';
import '../widgets/avatar.dart';
import '../widgets/glass.dart';
import 'home_screen.dart';
import 'profile_screen.dart';
import 'search_screen.dart';
import 'settings_screen.dart';

/// Home / Search / Profile / Settings with the floating liquid-glass bar.
class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _tab = 0;
  final _search = GlobalKey<SearchScreenState>();

  void _go(int i) {
    if (i == 2 && !Auth.instance.signedIn && Auth.instance.ready) {
      openLogin(context);
      return;
    }
    setState(() => _tab = i);
    if (i == 1) _search.currentState?.focus();
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).padding.bottom;
    return PopScope(
      canPop: _tab == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) setState(() => _tab = 0);
      },
      child: Scaffold(
        backgroundColor: C.bg,
        body: Stack(children: [
          IndexedStack(index: _tab, children: [
            HomeScreen(onSearch: () => _go(1)),
            SearchScreen(key: _search),
            const ProfileScreen(),
            const SettingsScreen(),
          ]),
          Positioned(
            left: 16,
            right: 16,
            bottom: 11 + bottom,
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 336),
                child: Glass(
                  kind: GlassKind.nav,
                  radius: 999,
                  height: 52,
                  padding: const EdgeInsets.all(5),
                  child: ListenableBuilder(
                    listenable: Auth.instance,
                    builder: (context, _) => Row(children: [
                      _NavTab(on: _tab == 0, label: 'Home', icon: Icon(_tab == 0 ? Icons.home_rounded : Icons.home_outlined, size: 21), onTap: () => _go(0)),
                      _NavTab(on: _tab == 1, label: 'Search', icon: const Icon(Icons.search_rounded, size: 21), onTap: () => _go(1)),
                      _NavTab(
                        on: _tab == 2,
                        label: 'Profile',
                        icon: Auth.instance.signedIn && Auth.instance.avatar != null
                            ? Container(
                                decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: C.w(0.4))),
                                child: Avatar(url: Auth.instance.avatar, size: 21),
                              )
                            : const Icon(Icons.person_outline_rounded, size: 21),
                        onTap: () => _go(2),
                      ),
                      _NavTab(on: _tab == 3, label: 'Settings', icon: Icon(_tab == 3 ? Icons.settings_rounded : Icons.settings_outlined, size: 21), onTap: () => _go(3)),
                    ]),
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }
}

class _NavTab extends StatefulWidget {
  const _NavTab({required this.on, required this.label, required this.icon, required this.onTap});
  final bool on;
  final String label;
  final Widget icon;
  final VoidCallback onTap;
  @override
  State<_NavTab> createState() => _NavTabState();
}

class _NavTabState extends State<_NavTab> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final show = widget.on || _down;
    return Expanded(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (_) => setState(() => _down = true),
        onTapUp: (_) => setState(() => _down = false),
        onTapCancel: () => setState(() => _down = false),
        onTap: widget.onTap,
        child: Stack(alignment: Alignment.center, children: [
          // glass bubble behind the current (or pressed) tab
          Positioned.fill(
            child: AnimatedScale(
              scale: show ? 1 : 0.75,
              duration: const Duration(milliseconds: 300),
              curve: Curves.easeOut,
              child: AnimatedOpacity(
                opacity: show ? 1 : 0,
                duration: const Duration(milliseconds: 300),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: C.w(0.14)),
                    gradient: RadialGradient(center: const Alignment(-0.4, -1), radius: 1.6, colors: [C.w(0.3), C.w(0.11), C.w(0.06)], stops: const [0, 0.55, 1]),
                    boxShadow: [BoxShadow(color: C.k(0.35), blurRadius: 12, offset: const Offset(0, 4))],
                  ),
                ),
              ),
            ),
          ),
          Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            IconTheme(data: IconThemeData(color: widget.on ? Colors.white : C.w(0.8)), child: SizedBox(height: 22, child: Center(child: widget.icon))),
            const SizedBox(height: 2),
            Text(widget.label, style: TextStyle(fontSize: 10, height: 1, fontWeight: FontWeight.w700, color: widget.on ? Colors.white : C.w(0.75))),
          ]),
        ]),
      ),
    );
  }
}
