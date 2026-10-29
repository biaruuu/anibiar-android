import 'dart:ui' show FontFeature;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';

import '../core/theme.dart';
import '../widgets/cards.dart' show clock;
import '../widgets/glass.dart';
import 'player_model.dart';

/// Small bag of switches that live in the settings menu and are remembered by the watch page.
class PlayerPrefs {
  PlayerPrefs({this.autoplay = true, this.autoNext = true, this.autoSkip = true});
  bool autoplay;
  bool autoNext;
  bool autoSkip;
}

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
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
}

class PlayerView extends StatelessWidget {
  const PlayerView({super.key, required this.model, required this.title, required this.prefs, required this.onPrefs, this.fullscreen = false});

  final PlayerModel model;
  final String title;
  final PlayerPrefs prefs;
  final VoidCallback onPrefs;
  final bool fullscreen;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: model,
      builder: (context, _) {
        final video = Container(
          color: Colors.black,
          child: Stack(fit: StackFit.expand, children: [
            if (model.ready)
              Center(child: AspectRatio(aspectRatio: model.aspect, child: VideoPlayer(model.c!))),
            _Captions(model: model, fullscreen: fullscreen),
            _Gestures(model: model, fullscreen: fullscreen, title: title, prefs: prefs, onPrefs: onPrefs),
            if (model.loading || (model.buffering && model.ready))
              const IgnorePointer(child: Center(child: SizedBox(width: 38, height: 38, child: CircularProgressIndicator(strokeWidth: 3, color: Colors.white)))),
            _SkipLayer(model: model),
            AnimatedOpacity(
              opacity: model.controlsVisible ? 1 : 0,
              duration: const Duration(milliseconds: 180),
              child: IgnorePointer(
                ignoring: !model.controlsVisible,
                child: _Controls(model: model, title: title, fullscreen: fullscreen, prefs: prefs, onPrefs: onPrefs),
              ),
            ),
          ]),
        );
        return fullscreen ? video : AspectRatio(aspectRatio: 16 / 9, child: video);
      },
    );
  }
}

/* --------------------------------- captions --------------------------------- */

TextStyle _stroke(double size) {
  final base = poppins(size: size, weight: FontWeight.w600, height: 1.25);
  return TextStyle(
    fontFamily: base.fontFamily,
    fontFamilyFallback: base.fontFamilyFallback,
    fontSize: size,
    fontWeight: FontWeight.w600,
    height: 1.25,
    foreground: Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.4
      ..strokeJoin = StrokeJoin.round
      ..color = Colors.black,
  );
}

class _Captions extends StatelessWidget {
  const _Captions({required this.model, required this.fullscreen});
  final PlayerModel model;
  final bool fullscreen;

  @override
  Widget build(BuildContext context) {
    final text = model.caption.replaceAll(RegExp(r'<[^>]*>'), '').trim();
    if (text.isEmpty) return const SizedBox.shrink();
    final size = fullscreen ? 20.0 : 13.5;
    final lift = model.controlsVisible ? (fullscreen ? 64.0 : 44.0) : (fullscreen ? 26.0 : 10.0);
    return Positioned(
      left: 24,
      right: 24,
      bottom: lift,
      child: IgnorePointer(
        child: AnimatedPadding(
          duration: const Duration(milliseconds: 180),
          padding: EdgeInsets.zero,
          child: Stack(alignment: Alignment.center, children: [
            // heavy dark outline so white text reads on any frame (no box)
            Text(text, textAlign: TextAlign.center, style: _stroke(size)),
            Text(text, textAlign: TextAlign.center, style: poppins(size: size, weight: FontWeight.w600, height: 1.25)),
          ]),
        ),
      ),
    );
  }
}

/* ---------------------------------- gestures --------------------------------- */

class _Gestures extends StatefulWidget {
  const _Gestures({required this.model, required this.fullscreen, required this.title, required this.prefs, required this.onPrefs});
  final PlayerModel model;
  final bool fullscreen;
  final String title;
  final PlayerPrefs prefs;
  final VoidCallback onPrefs;

  @override
  State<_Gestures> createState() => _GesturesState();
}

class _GesturesState extends State<_Gestures> {
  Offset? _tap;

  @override
  Widget build(BuildContext context) {
    final model = widget.model;
    return LayoutBuilder(builder: (context, box) {
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: model.toggleControls,
        onDoubleTapDown: (d) => _tap = d.localPosition,
        // Double tap: left third rewinds, right third skips ahead, middle toggles fullscreen.
        onDoubleTap: () {
          final x = _tap?.dx ?? box.maxWidth / 2;
          if (x < box.maxWidth / 3) {
            model.seekBy(-10);
            model.showControls();
          } else if (x > box.maxWidth * 2 / 3) {
            model.seekBy(10);
            model.showControls();
          } else if (widget.fullscreen) {
            Navigator.of(context).maybePop();
          } else {
            enterFullscreen(context, model, widget.title, widget.prefs, widget.onPrefs);
          }
        },
      );
    });
  }
}

/* ------------------------------- skip intro/outro ------------------------------ */

class _SkipLayer extends StatelessWidget {
  const _SkipLayer({required this.model});
  final PlayerModel model;

  @override
  Widget build(BuildContext context) {
    final toast = model.skipToast;
    final target = model.skipTarget;
    if (toast == null && target == null) return const SizedBox.shrink();
    return Positioned(
      right: 14,
      bottom: model.controlsVisible ? 58 : 16,
      child: toast != null
          ? Glass(
              kind: GlassKind.panel,
              radius: 14,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                Text(toast.kind == SkipKind.intro ? 'Intro skipped' : 'Outro skipped', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                const SizedBox(width: 12),
                GestureDetector(onTap: model.undoSkip, child: const Text('Undo', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800, color: Colors.white, decoration: TextDecoration.underline))),
              ]),
            )
          : GlassButton(
              label: target == SkipKind.intro ? 'Skip Intro' : 'Skip Outro',
              icon: Icons.skip_next_rounded,
              kind: GlassKind.white,
              height: 38,
              fontSize: 13,
              padding: const EdgeInsets.symmetric(horizontal: 14),
              onTap: () => model.skipTo(target!, automatic: false),
            ),
    );
  }
}

/* ----------------------------------- controls ---------------------------------- */

class _Controls extends StatelessWidget {
  const _Controls({required this.model, required this.title, required this.fullscreen, required this.prefs, required this.onPrefs});
  final PlayerModel model;
  final String title;
  final bool fullscreen;
  final PlayerPrefs prefs;
  final VoidCallback onPrefs;

  @override
  Widget build(BuildContext context) {
    final pad = fullscreen ? 20.0 : 8.0;
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0xB3000000), Color(0x00000000), Color(0x00000000), Color(0xCC000000)], stops: [0, 0.3, 0.6, 1]),
      ),
      child: Stack(children: [
        // top bar
        Positioned(
          top: pad,
          left: pad + 4,
          right: pad + 4,
          child: Row(children: [
            if (fullscreen) ...[
              GlassIconButton(icon: Icons.arrow_back_rounded, size: 38, iconSize: 20, onTap: () => Navigator.of(context).maybePop()),
              const SizedBox(width: 10),
            ],
            Expanded(child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: fullscreen ? 16 : 13, fontWeight: FontWeight.w700, color: Colors.white))),
            GlassIconButton(
              icon: Icons.settings_rounded,
              size: 38,
              iconSize: 20,
              onTap: () {
                model.touch();
                showPlayerMenu(context, model, prefs, onPrefs);
              },
            ),
          ]),
        ),
        // centre
        Center(
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            _Round(icon: Icons.replay_10_rounded, size: fullscreen ? 52 : 40, onTap: () {
              model.seekBy(-10);
              model.touch();
            }),
            SizedBox(width: fullscreen ? 36 : 22),
            _Round(
              icon: model.playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
              size: fullscreen ? 72 : 56,
              iconSize: fullscreen ? 40 : 32,
              white: true,
              onTap: model.togglePlay,
            ),
            SizedBox(width: fullscreen ? 36 : 22),
            _Round(icon: Icons.forward_10_rounded, size: fullscreen ? 52 : 40, onTap: () {
              model.seekBy(10);
              model.touch();
            }),
          ]),
        ),
        // bottom: seek bar + time + fullscreen
        Positioned(
          left: pad,
          right: pad,
          bottom: fullscreen ? 14 : 2,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            _SeekBar(model: model),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Row(children: [
                Text('${clock(model.position)} / ${clock(model.duration)}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Colors.white, fontFeatures: [FontFeature.tabularFigures()])),
                const Spacer(),
                if (model.speed != 1) Padding(padding: const EdgeInsets.only(right: 10), child: Text('${model.speed}x', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: Colors.white))),
                GestureDetector(
                  onTap: () {
                    if (fullscreen) {
                      Navigator.of(context).maybePop();
                    } else {
                      enterFullscreen(context, model, title, prefs, onPrefs);
                    }
                  },
                  child: Padding(padding: const EdgeInsets.all(6), child: Icon(fullscreen ? Icons.fullscreen_exit_rounded : Icons.fullscreen_rounded, size: 24, color: Colors.white)),
                ),
              ]),
            ),
          ]),
        ),
      ]),
    );
  }
}

class _Round extends StatelessWidget {
  const _Round({required this.icon, required this.onTap, this.size = 44, this.iconSize, this.white = false});
  final IconData icon;
  final VoidCallback onTap;
  final double size;
  final double? iconSize;
  final bool white;
  @override
  Widget build(BuildContext context) => GlassIconButton(icon: icon, onTap: onTap, size: size, iconSize: iconSize ?? size * 0.58, kind: white ? GlassKind.white : GlassKind.btn);
}

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
    return SizedBox(
      height: 28,
      child: Stack(alignment: Alignment.center, children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Stack(children: [
            Container(height: 4, decoration: BoxDecoration(color: C.w(0.25), borderRadius: BorderRadius.circular(2))),
            FractionallySizedBox(widthFactor: buffered, child: Container(height: 4, decoration: BoxDecoration(color: C.w(0.35), borderRadius: BorderRadius.circular(2)))),
          ]),
        ),
        SliderTheme(
          data: SliderThemeData(
            trackHeight: 4,
            activeTrackColor: Colors.white,
            inactiveTrackColor: Colors.transparent,
            thumbColor: Colors.white,
            overlayColor: C.w(0.15),
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
            overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
            trackShape: const RoundedRectSliderTrackShape(),
          ),
          child: Slider(
            value: value,
            onChangeStart: (_) {
              m.seeking = true;
            },
            onChanged: (x) => setState(() => _drag = x * dur),
            onChangeEnd: (x) async {
              await m.seekTo(x * dur);
              m.seeking = false;
              setState(() => _drag = null);
              m.touch();
            },
          ),
        ),
      ]),
    );
  }
}

/* ------------------------------------ menu ------------------------------------- */

enum _View { main, speed, quality, captions }

void showPlayerMenu(BuildContext context, PlayerModel model, PlayerPrefs prefs, VoidCallback onPrefs) {
  var view = _View.main;
  showGlassSheet<void>(context, builder: (ctx) {
    return StatefulBuilder(builder: (ctx, set) {
      Widget row(String label, String value, {VoidCallback? onTap, IconData? icon}) => _Row(label: label, value: value, icon: icon, onTap: onTap);
      Widget opt(String label, bool on, VoidCallback onTap, {String? hint}) => _Opt(label: label, selected: on, onTap: onTap, hint: hint);
      final children = <Widget>[];
      var title = '';
      switch (view) {
        case _View.main:
          title = 'Settings';
          final q = model.quality < 0 || model.quality >= model.levels.length ? 'Auto' : '${model.levels[model.quality].height}p';
          children.addAll([
            if (model.levels.isNotEmpty) row('Quality', q, icon: Icons.high_quality_rounded, onTap: () => set(() => view = _View.quality)),
            row('Speed', model.speed == 1 ? 'Normal' : '${model.speed}x', icon: Icons.speed_rounded, onTap: () => set(() => view = _View.speed)),
            row('Captions', model.tracks.isEmpty ? 'Built in' : (model.track < 0 ? 'Off' : model.tracks[model.track].label), icon: Icons.closed_caption_rounded, onTap: model.tracks.isEmpty ? null : () => set(() => view = _View.captions)),
            row('Auto skip intro/outro', model.intro == null && model.outro == null ? 'No data' : (prefs.autoSkip ? 'On' : 'Off'), icon: Icons.fast_forward_rounded, onTap: () {
              prefs.autoSkip = !prefs.autoSkip;
              model.autoSkip = prefs.autoSkip;
              onPrefs();
              set(() {});
            }),
            row('Autoplay', prefs.autoplay ? 'On' : 'Off', icon: Icons.play_circle_outline_rounded, onTap: () {
              prefs.autoplay = !prefs.autoplay;
              model.autoplay = prefs.autoplay;
              onPrefs();
              set(() {});
            }),
            row('Auto next episode', prefs.autoNext ? 'On' : 'Off', icon: Icons.skip_next_rounded, onTap: () {
              prefs.autoNext = !prefs.autoNext;
              model.autoNext = prefs.autoNext;
              onPrefs();
              set(() {});
            }),
          ]);
        case _View.speed:
          title = 'Speed';
          for (final s in const [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]) {
            children.add(opt(s == 1 ? 'Normal' : '${s}x', model.speed == s, () async {
              await model.setSpeed(s);
              set(() => view = _View.main);
            }));
          }
        case _View.quality:
          title = 'Quality';
          children.add(opt('Auto', model.quality < 0, () {
            model.setQuality(-1);
            Navigator.of(ctx).pop();
          }, hint: 'Picks the best quality your connection can handle.'));
          for (var i = 0; i < model.levels.length; i++) {
            final idx = i;
            children.add(opt('${model.levels[i].height}p', model.quality == i, () {
              model.setQuality(idx);
              Navigator.of(ctx).pop();
            }));
          }
        case _View.captions:
          title = 'Captions';
          children.add(opt('Off', model.track < 0, () async {
            await model.setTrack(-1);
            set(() => view = _View.main);
          }));
          for (var i = 0; i < model.tracks.length; i++) {
            final idx = i;
            children.add(opt(model.tracks[i].label, model.track == i, () async {
              await model.setTrack(idx);
              set(() => view = _View.main);
            }));
          }
      }
      return ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * 0.8),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(8, 10, 8, 14),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
              child: Row(children: [
                if (view != _View.main) GestureDetector(onTap: () => set(() => view = _View.main), child: const Padding(padding: EdgeInsets.only(right: 8), child: Icon(Icons.arrow_back_rounded, size: 22))),
                Expanded(child: Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700))),
                GestureDetector(onTap: () => Navigator.of(ctx).pop(), child: Icon(Icons.close_rounded, color: C.w(0.8))),
              ]),
            ),
            ...children,
          ]),
        ),
      );
    });
  });
}

class _Row extends StatelessWidget {
  const _Row({required this.label, required this.value, this.icon, this.onTap});
  final String label;
  final String value;
  final IconData? icon;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Opacity(
          opacity: onTap == null ? 0.5 : 1,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 13),
            child: Row(children: [
              if (icon != null) ...[Icon(icon, size: 20, color: C.w(0.8)), const SizedBox(width: 12)],
              Expanded(child: Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600))),
              Flexible(child: Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 14, color: C.w(0.6)))),
              if (onTap != null) Icon(Icons.chevron_right_rounded, color: C.w(0.4)),
            ]),
          ),
        ),
      );
}

class _Opt extends StatelessWidget {
  const _Opt({required this.label, required this.selected, required this.onTap, this.hint});
  final String label;
  final bool selected;
  final VoidCallback onTap;
  final String? hint;
  @override
  Widget build(BuildContext context) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(label, style: TextStyle(fontSize: 15, fontWeight: selected ? FontWeight.w800 : FontWeight.w600))),
              if (selected) const Icon(Icons.check_rounded, size: 20),
            ]),
            if (hint != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(hint!, style: TextStyle(fontSize: 12, color: C.w(0.5)))),
          ]),
        ),
      );
}
