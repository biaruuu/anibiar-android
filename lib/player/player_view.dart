import 'dart:ui' show FontFeature;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:video_player/video_player.dart';

import '../core/theme.dart';
import '../widgets/cards.dart' show clock;
import '../widgets/glass.dart';
import '../widgets/vicon.dart';
import 'player_model.dart';

/// Switches that live in the settings menu and are remembered by the watch page.
class PlayerPrefs {
  PlayerPrefs({this.autoplay = true, this.autoNext = true, this.autoSkip = true});
  bool autoplay;
  bool autoNext;
  bool autoSkip;
}

double _cl(double min, double v, double max) => v < min ? min : (v > max ? max : v);

Future<void> enterFullscreen(BuildContext context, PlayerModel model, String title, PlayerPrefs prefs, VoidCallback onPrefs) async {
  await SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  if (!context.mounted) return;
  await Navigator.of(context, rootNavigator: true).push(PageRouteBuilder<void>(
    opaque: true,
    transitionDuration: const Duration(milliseconds: 150),
    reverseTransitionDuration: const Duration(milliseconds: 150),
    pageBuilder: (ctx, _, __) => Scaffold(
      backgroundColor: Colors.black,
      body: PlayerView(model: model, title: title, prefs: prefs, onPrefs: onPrefs, fullscreen: true),
    ),
    transitionsBuilder: (_, a, __, child) => FadeTransition(opacity: a, child: child),
  ));
  model.closeMenu();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
}

/// Subtitle font: Trebuchet MS where the phone has it, otherwise Open Sans (a similar humanist face).
TextStyle _captionFont(double size) {
  String? open;
  try {
    open = GoogleFonts.openSans(fontWeight: FontWeight.w700).fontFamily;
  } catch (_) {}
  return TextStyle(
    fontFamily: 'Trebuchet MS',
    fontFamilyFallback: [if (open != null) open, 'sans-serif'],
    fontSize: size,
    fontWeight: FontWeight.w700,
    height: 1.35,
    letterSpacing: 0.05,
  );
}

class PlayerView extends StatefulWidget {
  const PlayerView({super.key, required this.model, required this.title, required this.prefs, required this.onPrefs, this.fullscreen = false});

  final PlayerModel model;
  final String title;
  final PlayerPrefs prefs;
  final VoidCallback onPrefs;
  final bool fullscreen;

  @override
  State<PlayerView> createState() => _PlayerViewState();
}

class _PlayerViewState extends State<PlayerView> {
  Offset? _tap;

  PlayerModel get m => widget.model;

  void _toggleFullscreen() {
    if (widget.fullscreen) {
      Navigator.of(context).maybePop();
    } else {
      enterFullscreen(context, m, widget.title, widget.prefs, widget.onPrefs);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: m,
      builder: (context, _) {
        final body = LayoutBuilder(builder: (context, box) {
          final w = box.maxWidth;
          final h = box.maxHeight;
          final pad = _cl(8, w * 0.022, 20);
          final btn = _cl(36, w * 0.045, 46);
          final icon = _cl(20, w * 0.034, 28);
          final side = _cl(40, w * 0.06, 64);
          final center = _cl(48, w * 0.07, 74);
          final hidden = !m.controlsVisible && !m.menuOpen;
          final topBar = btn + _cl(4, w * 0.006, 8) + pad;
          return Container(
            color: Colors.black,
            child: Stack(fit: StackFit.expand, children: [
              if (m.ready) Center(child: AspectRatio(aspectRatio: m.aspect, child: VideoPlayer(m.c!))),
              _Captions(model: m, w: w),
              // taps: show/hide controls; double tap: left third back, right third forward, middle fullscreen
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () {
                  if (m.menuOpen) {
                    m.closeMenu();
                  } else {
                    m.toggleControls();
                  }
                },
                onDoubleTapDown: (d) => _tap = d.localPosition,
                onDoubleTap: () {
                  final x = _tap?.dx ?? w / 2;
                  if (x < w / 3) {
                    m.seekBy(-10);
                    m.flash(-1);
                  } else if (x > w * 2 / 3) {
                    m.seekBy(10);
                    m.flash(1);
                  } else {
                    _toggleFullscreen();
                  }
                },
              ),
              if (m.seekFlash != null)
                Positioned(
                  top: h / 2 - side / 2,
                  left: m.seekFlash == -1 ? w * 0.12 : null,
                  right: m.seekFlash == 1 ? w * 0.12 : null,
                  child: IgnorePointer(
                    child: Container(
                      width: side,
                      height: side,
                      decoration: BoxDecoration(color: C.k(0.6), shape: BoxShape.circle),
                      child: Center(child: VIcon(m.seekFlash == -1 ? 'seek_backward_10' : 'seek_forward_10', size: side * 0.74)),
                    ),
                  ),
                ),
              if (m.loading || (m.buffering && m.ready && hidden))
                IgnorePointer(child: Center(child: SizedBox(width: _cl(30, w * 0.06, 52), height: _cl(30, w * 0.06, 52), child: const CircularProgressIndicator(strokeWidth: 3, color: Colors.white)))),
              _SkipLayer(model: m, w: w, controls: !hidden),
              // soft shade behind the controls so they read on bright frames
              IgnorePointer(
                child: AnimatedOpacity(
                  opacity: hidden ? 0 : 1,
                  duration: const Duration(milliseconds: 250),
                  child: const DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0x80000000), Color(0x00000000), Color(0x00000000), Color(0x99000000)], stops: [0, 0.28, 0.6, 1]),
                    ),
                    child: SizedBox.expand(),
                  ),
                ),
              ),
              // ---------------- top bar: volume on the left, tools on the right ----------------
              Positioned(
                top: pad,
                left: pad,
                right: pad,
                child: AnimatedSlide(
                  offset: hidden ? const Offset(0, -0.3) : Offset.zero,
                  duration: const Duration(milliseconds: 250),
                  child: AnimatedOpacity(
                    opacity: hidden ? 0 : 1,
                    duration: const Duration(milliseconds: 250),
                    child: IgnorePointer(
                      ignoring: hidden,
                      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        _Pill(children: [
                          _IconBtn(size: btn, icon: m.muted ? 'mute' : 'volume_high', iconSize: icon, onTap: m.toggleMute),
                        ]),
                        if (widget.fullscreen)
                          Expanded(child: Padding(padding: EdgeInsets.symmetric(horizontal: pad), child: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center, style: poppins(size: _cl(12, w * 0.016, 16), weight: FontWeight.w600).copyWith(shadows: const [Shadow(blurRadius: 4, color: Color(0x99000000))]))))
                        else
                          const Spacer(),
                        _Pill(children: [
                          if (m.tracks.isNotEmpty)
                            _IconBtn(
                              size: btn,
                              icon: m.track >= 0 ? 'closed_captions_on' : 'closed_captions',
                              iconSize: icon,
                              onTap: () => m.setTrack(m.track >= 0 ? -1 : _firstTrack(m)),
                            ),
                          _IconBtn(
                            size: btn,
                            icon: 'settings',
                            iconSize: icon,
                            turns: m.menuOpen ? 0.125 : 0,
                            onTap: () => m.menuOpen ? m.closeMenu() : m.openMenu(),
                          ),
                        ]),
                      ]),
                    ),
                  ),
                ),
              ),
              // ---------------- centre: 10 s buttons well to either side of play/pause ----------------
              Positioned.fill(
                child: AnimatedOpacity(
                  opacity: hidden || m.failed ? 0 : 1,
                  duration: const Duration(milliseconds: 250),
                  child: IgnorePointer(
                    ignoring: hidden,
                    child: Stack(children: [
                      Positioned(left: w * 0.25 - side / 2, top: h / 2 - side / 2, child: _Round(size: side, icon: 'seek_backward_10', onTap: () {
                        m.seekBy(-10);
                        m.touch();
                      })),
                      Positioned(
                        left: w / 2 - center / 2,
                        top: h / 2 - center / 2,
                        child: _Round(
                          size: center,
                          icon: m.playing ? 'pause' : 'play',
                          spinner: m.buffering && m.playing,
                          onTap: m.togglePlay,
                        ),
                      ),
                      Positioned(left: w * 0.75 - side / 2, top: h / 2 - side / 2, child: _Round(size: side, icon: 'seek_forward_10', onTap: () {
                        m.seekBy(10);
                        m.touch();
                      })),
                    ]),
                  ),
                ),
              ),
              // ---------------- bottom: time + fullscreen above a thin seek bar ----------------
              Positioned(
                left: _cl(12, w * 0.035, 48),
                right: _cl(12, w * 0.035, 48),
                bottom: _cl(6, w * 0.02, 28),
                child: AnimatedSlide(
                  offset: hidden ? const Offset(0, 0.2) : Offset.zero,
                  duration: const Duration(milliseconds: 250),
                  child: AnimatedOpacity(
                    opacity: hidden ? 0 : 1,
                    duration: const Duration(milliseconds: 250),
                    child: IgnorePointer(
                      ignoring: hidden,
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                          Expanded(
                            child: Text.rich(
                              TextSpan(children: [
                                TextSpan(text: clock(m.position)),
                                TextSpan(text: ' / ${clock(m.duration)}', style: TextStyle(color: C.w(0.75))),
                                if (w > 560) TextSpan(text: '  •  ${widget.title}', style: TextStyle(color: C.w(0.75))),
                              ]),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: poppins(size: _cl(12, w * 0.018, 16), weight: FontWeight.w500).copyWith(fontFeatures: const [FontFeature.tabularFigures()], shadows: const [Shadow(blurRadius: 3, offset: Offset(0, 1), color: Color(0x99000000))]),
                            ),
                          ),
                          GestureDetector(
                            onTap: _toggleFullscreen,
                            child: Glass(
                              kind: GlassKind.bar,
                              radius: _cl(12, w * 0.024, 20),
                              width: _cl(38, w * 0.056, 54),
                              height: _cl(29, w * 0.041, 42),
                              alignment: Alignment.center,
                              child: VIcon(widget.fullscreen ? 'fullscreen_exit' : 'fullscreen', size: _cl(20, w * 0.034, 28)),
                            ),
                          ),
                        ]),
                        const SizedBox(height: 2),
                        _SeekBar(model: m),
                      ]),
                    ),
                  ),
                ),
              ),
              // ---------------- settings menu ----------------
              if (m.menuOpen) ...[
                Positioned.fill(child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: m.closeMenu, child: const SizedBox.expand())),
                Positioned(
                  top: topBar + 4,
                  right: pad,
                  width: _cl(224, w * 0.4, 320),
                  child: _Menu(model: m, prefs: widget.prefs, onPrefs: widget.onPrefs, maxHeight: (h - topBar - pad - 8).clamp(120.0, 600.0).toDouble(), w: w),
                ),
              ],
            ]),
          );
        });
        return widget.fullscreen ? body : AspectRatio(aspectRatio: 16 / 9, child: body);
      },
    );
  }

  int _firstTrack(PlayerModel m) {
    for (var i = 0; i < m.tracks.length; i++) {
      final l = '${m.tracks[i].label} ${m.tracks[i].lang}'.toLowerCase();
      if (l.contains('english') || l.startsWith('en')) return i;
    }
    return 0;
  }
}

/* ----------------------------------- pieces ----------------------------------- */

class _Pill extends StatelessWidget {
  const _Pill({required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Glass(kind: GlassKind.bar, radius: 999, padding: const EdgeInsets.all(2), child: Row(mainAxisSize: MainAxisSize.min, children: children));
}

class _IconBtn extends StatelessWidget {
  const _IconBtn({required this.size, required this.icon, required this.iconSize, required this.onTap, this.turns = 0});
  final double size;
  final String icon;
  final double iconSize;
  final VoidCallback onTap;
  final double turns;
  @override
  Widget build(BuildContext context) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: SizedBox(
          width: size,
          height: size,
          child: Center(child: AnimatedRotation(turns: turns, duration: const Duration(milliseconds: 250), child: VIcon(icon, size: iconSize, shadow: true))),
        ),
      );
}

class _Round extends StatelessWidget {
  const _Round({required this.size, required this.icon, required this.onTap, this.spinner = false});
  final double size;
  final String icon;
  final VoidCallback onTap;
  final bool spinner;
  @override
  Widget build(BuildContext context) => Glass(
        kind: GlassKind.bar,
        radius: size / 2,
        width: size,
        height: size,
        alignment: Alignment.center,
        onTap: onTap,
        child: spinner ? SizedBox(width: size * 0.42, height: size * 0.42, child: const CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)) : VIcon(icon, size: size * 0.74),
      );
}

class _Captions extends StatelessWidget {
  const _Captions({required this.model, required this.w});
  final PlayerModel model;
  final double w;

  @override
  Widget build(BuildContext context) {
    final text = model.caption.replaceAll(RegExp(r'<[^>]*>'), '').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>').trim();
    if (text.isEmpty || model.track < 0) return const SizedBox.shrink();
    // same sizing as the website: clamp(0.85rem, 2.6cqw, 2.5rem) times the chosen size
    final size = _cl(13.6, w * 0.026, 40) * model.captionScale;
    final lift = _cl(34, w * 0.065, 80);
    final fill = _captionFont(size).copyWith(color: Colors.white);
    Widget label;
    if (model.captionBox) {
      label = Container(
        padding: EdgeInsets.symmetric(horizontal: size * 0.6, vertical: size * 0.12),
        decoration: BoxDecoration(color: C.k(0.75), borderRadius: BorderRadius.circular(size * 0.35)),
        child: Text(text, textAlign: TextAlign.center, style: fill),
      );
    } else {
      // dark outline, no box: white text reads on any frame
      final stroke = _captionFont(size).copyWith(
        foreground: Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = size * 0.2
          ..strokeJoin = StrokeJoin.round
          ..color = const Color(0xEB000000),
        shadows: [Shadow(blurRadius: size * 0.3, color: const Color(0xA6000000))],
      );
      label = Stack(alignment: Alignment.center, children: [
        Text(text, textAlign: TextAlign.center, style: stroke),
        Text(text, textAlign: TextAlign.center, style: fill),
      ]);
    }
    return Positioned(
      left: w * 0.05,
      right: w * 0.05,
      bottom: lift,
      child: IgnorePointer(child: Center(child: ConstrainedBox(constraints: BoxConstraints(maxWidth: size * 52), child: label))),
    );
  }
}

class _SkipLayer extends StatelessWidget {
  const _SkipLayer({required this.model, required this.w, required this.controls});
  final PlayerModel model;
  final double w;
  final bool controls;

  @override
  Widget build(BuildContext context) {
    final toast = model.skipToast;
    final target = model.skipTarget;
    if (toast == null && target == null) return const SizedBox.shrink();
    final fs = _cl(12, w * 0.02, 16);
    return Positioned(
      right: _cl(12, w * 0.035, 48),
      bottom: controls ? _cl(46, w * 0.1, 96) : _cl(14, w * 0.03, 40),
      child: toast != null
          ? Glass(
              kind: GlassKind.panel,
              radius: 14,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                Text(toast.kind == SkipKind.intro ? 'Intro skipped' : 'Outro skipped', style: poppins(size: fs, weight: FontWeight.w500)),
                const SizedBox(width: 12),
                GestureDetector(onTap: model.undoSkip, child: Text('Undo', style: poppins(size: fs, weight: FontWeight.w700).copyWith(decoration: TextDecoration.underline))),
              ]),
            )
          : GestureDetector(
              onTap: () => model.skipTo(target!, automatic: false),
              child: Container(
                padding: EdgeInsets.symmetric(horizontal: fs * 1.1, vertical: fs * 0.6),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99), boxShadow: [BoxShadow(color: C.k(0.4), blurRadius: 18, offset: const Offset(0, 6))]),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Text(target == SkipKind.intro ? 'Skip Intro' : 'Skip Outro', style: poppins(size: fs, weight: FontWeight.w600, color: Colors.black)),
                  SizedBox(width: fs * 0.4),
                  VIcon('seek_forward_10', size: fs * 1.5, color: Colors.black),
                ]),
              ),
            ),
    );
  }
}

/// Thin progress bar: grows a little while held, white thumb.
class _SeekBar extends StatefulWidget {
  const _SeekBar({required this.model});
  final PlayerModel model;
  @override
  State<_SeekBar> createState() => _SeekBarState();
}

class _SeekBarState extends State<_SeekBar> {
  double? _drag;

  @override
  Widget build(BuildContext context) {
    final m = widget.model;
    final dur = m.duration <= 0 ? 1.0 : m.duration;
    final value = ((_drag ?? m.position) / dur).clamp(0.0, 1.0).toDouble();
    double buffered = 0;
    final v = m.c?.value;
    if (v != null && v.buffered.isNotEmpty) buffered = (v.buffered.last.end.inMilliseconds / 1000 / dur).clamp(0.0, 1.0).toDouble();
    final h = _drag != null ? 6.0 : 4.0;
    return SizedBox(
      height: 26,
      child: LayoutBuilder(builder: (context, box) {
        final width = box.maxWidth;
        double at(double dx) => (dx / width).clamp(0.0, 1.0).toDouble();
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapUp: (d) => m.seekTo(at(d.localPosition.dx) * dur),
          onHorizontalDragStart: (d) {
            m.seeking = true;
            setState(() => _drag = at(d.localPosition.dx) * dur);
          },
          onHorizontalDragUpdate: (d) => setState(() => _drag = at(d.localPosition.dx) * dur),
          onHorizontalDragEnd: (_) async {
            final t = _drag;
            if (t != null) await m.seekTo(t);
            m.seeking = false;
            if (mounted) setState(() => _drag = null);
            m.touch();
          },
          child: Stack(alignment: Alignment.centerLeft, clipBehavior: Clip.none, children: [
            AnimatedContainer(duration: const Duration(milliseconds: 120), height: h, width: width, decoration: BoxDecoration(color: C.w(0.3), borderRadius: BorderRadius.circular(99))),
            AnimatedContainer(duration: const Duration(milliseconds: 120), height: h, width: width * buffered, decoration: BoxDecoration(color: C.w(0.5), borderRadius: BorderRadius.circular(99))),
            AnimatedContainer(duration: const Duration(milliseconds: 120), height: h, width: width * value, decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99))),
            Positioned(
              left: (width * value - 6).clamp(0.0, width - 12).toDouble(),
              child: AnimatedOpacity(
                opacity: _drag != null ? 1 : 0.0,
                duration: const Duration(milliseconds: 120),
                child: Container(width: 12, height: 12, decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle)),
              ),
            ),
          ]),
        );
      }),
    );
  }
}

/* ------------------------------------ menu ------------------------------------- */

class _Menu extends StatelessWidget {
  const _Menu({required this.model, required this.prefs, required this.onPrefs, required this.maxHeight, required this.w});
  final PlayerModel model;
  final PlayerPrefs prefs;
  final VoidCallback onPrefs;
  final double maxHeight;
  final double w;

  @override
  Widget build(BuildContext context) {
    final m = model;
    final fs = _cl(12.5, w * 0.022, 15);
    final view = m.menuView;
    Widget row(String label, String value, {VoidCallback? onTap, bool chevron = true}) => _MenuRow(label: label, value: value, onTap: onTap, chevron: chevron, fs: fs);
    Widget opt(String label, bool on, VoidCallback onTap, {String? hint}) => _MenuOpt(label: label, selected: on, onTap: onTap, hint: hint, fs: fs);
    final items = <Widget>[];
    var title = 'Settings';
    switch (view) {
      case 'captions':
        title = 'Captions';
        items.add(opt('Off', m.track < 0, () {
          m.setTrack(-1);
          m.setMenuView('main');
        }));
        for (var i = 0; i < m.tracks.length; i++) {
          final idx = i;
          items.add(opt(m.tracks[i].label, m.track == i, () {
            m.setTrack(idx);
            m.setMenuView('main');
          }));
        }
      case 'style':
        title = 'Caption style';
        items.add(_MenuHead('Size', fs));
        for (final e in const [('Small', 0.88), ('Medium', 1.1), ('Large', 1.4)]) {
          items.add(opt(e.$1, (m.captionScale - e.$2).abs() < 0.01, () => m.setCaptionStyle(scale: e.$2)));
        }
        items.add(_MenuHead('Style', fs));
        items.add(opt('Outline', !m.captionBox, () => m.setCaptionStyle(box: false)));
        items.add(opt('Dark box', m.captionBox, () => m.setCaptionStyle(box: true)));
      case 'speed':
        title = 'Speed';
        for (final s in const [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]) {
          items.add(opt(s == 1 ? 'Normal' : '${s}x', m.speed == s, () async {
            await m.setSpeed(s);
            m.setMenuView('main');
          }));
        }
      case 'quality':
        title = 'Quality';
        items.add(opt('Auto', m.quality < 0, () {
          m.setQuality(-1);
          m.closeMenu();
        }, hint: 'Picks the best quality your connection can handle.'));
        for (var i = 0; i < m.levels.length; i++) {
          final idx = i;
          items.add(opt('${m.levels[i].height}p', m.quality == i, () {
            m.setQuality(idx);
            m.closeMenu();
          }));
        }
      default:
        final q = m.quality < 0 || m.quality >= m.levels.length ? 'Auto' : '${m.levels[m.quality].height}p';
        items.addAll([
          if (m.levels.isNotEmpty) row('Quality', q, onTap: () => m.setMenuView('quality')),
          row('Captions', m.tracks.isEmpty ? 'Built in' : (m.track < 0 ? 'Off' : m.tracks[m.track].label), onTap: m.tracks.isEmpty ? null : () => m.setMenuView('captions')),
          if (m.tracks.isNotEmpty) row('Caption style', '', onTap: () => m.setMenuView('style')),
          row('Speed', m.speed == 1 ? 'Normal' : '${m.speed}x', onTap: () => m.setMenuView('speed')),
          row('Autoplay', prefs.autoplay ? 'On' : 'Off', chevron: false, onTap: () {
            prefs.autoplay = !prefs.autoplay;
            m.autoplay = prefs.autoplay;
            onPrefs();
            m.setMenuView('main');
          }),
          row('Auto skip intro/outro', m.intro == null && m.outro == null ? 'No data' : (prefs.autoSkip ? 'On' : 'Off'), chevron: false, onTap: () {
            prefs.autoSkip = !prefs.autoSkip;
            m.autoSkip = prefs.autoSkip;
            onPrefs();
            m.setMenuView('main');
          }),
          row('Auto next episode', prefs.autoNext ? 'On' : 'Off', chevron: false, onTap: () {
            prefs.autoNext = !prefs.autoNext;
            m.autoNext = prefs.autoNext;
            onPrefs();
            m.setMenuView('main');
          }),
        ]);
    }
    return Glass(
      kind: GlassKind.panel,
      radius: 18,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: maxHeight),
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(8),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(6, 2, 6, 8),
              child: Row(children: [
                if (view != 'main') GestureDetector(onTap: () => m.setMenuView('main'), child: Padding(padding: const EdgeInsets.only(right: 8), child: VIcon('chevron_left', size: fs + 10))),
                Expanded(child: Text(title, style: poppins(size: fs + 1.5, weight: FontWeight.w600))),
              ]),
            ),
            ...items,
          ]),
        ),
      ),
    );
  }
}

class _MenuHead extends StatelessWidget {
  const _MenuHead(this.text, this.fs);
  final String text;
  final double fs;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.fromLTRB(8, 8, 8, 4), child: Text(text.toUpperCase(), style: poppins(size: fs - 2, weight: FontWeight.w600, color: C.w(0.5))));
}

class _MenuRow extends StatelessWidget {
  const _MenuRow({required this.label, required this.value, required this.onTap, required this.chevron, required this.fs});
  final String label;
  final String value;
  final VoidCallback? onTap;
  final bool chevron;
  final double fs;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Opacity(
            opacity: onTap == null ? 0.5 : 1,
            child: Container(
              padding: EdgeInsets.symmetric(horizontal: 12, vertical: fs * 0.75),
              decoration: BoxDecoration(color: C.w(0.07), borderRadius: BorderRadius.circular(12)),
              child: Row(children: [
                Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: poppins(size: fs, weight: FontWeight.w500))),
                if (value.isNotEmpty) Flexible(child: Padding(padding: const EdgeInsets.only(left: 8), child: Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: poppins(size: fs - 1, color: C.w(0.65))))),
                if (chevron) VIcon('chevron_right', size: fs + 6, color: C.w(0.45)),
              ]),
            ),
          ),
        ),
      );
}

class _MenuOpt extends StatelessWidget {
  const _MenuOpt({required this.label, required this.selected, required this.onTap, required this.fs, this.hint});
  final String label;
  final bool selected;
  final VoidCallback onTap;
  final double fs;
  final String? hint;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Container(
            padding: EdgeInsets.symmetric(horizontal: 12, vertical: fs * 0.7),
            decoration: BoxDecoration(color: selected ? C.w(0.16) : C.w(0.06), borderRadius: BorderRadius.circular(12)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: poppins(size: fs, weight: selected ? FontWeight.w700 : FontWeight.w500))),
                if (selected) VIcon('check', size: fs + 6),
              ]),
              if (hint != null) Padding(padding: const EdgeInsets.only(top: 3), child: Text(hint!, style: poppins(size: fs - 2.5, color: C.w(0.5)))),
            ]),
          ),
        ),
      );
}
