import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player/video_player.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../services/api.dart';

class QualityLevel {
  QualityLevel(this.height, this.url);
  final int height;
  final String url;
}

enum SkipKind { intro, outro }

/// Everything the player knows: the video, captions, quality, speed and intro/outro skipping.
/// The controls (inline and fullscreen) only draw this and call its methods.
class PlayerModel extends ChangeNotifier {
  VideoPlayerController? c;
  String? _master;
  bool _hls = true;
  bool _disposed = false;
  int _gen = 0;

  // state shown by the UI
  bool loading = true;
  bool failed = false;
  bool controlsVisible = true;
  bool seeking = false;
  double speed = 1;
  List<QualityLevel> levels = [];
  int quality = -1; // -1 = Auto
  List<StreamSubtitle> tracks = [];
  int track = -1;
  String caption = '';
  List<Caption> _caps = [];
  SkipKind? skipTarget;
  ({SkipKind kind, double from})? skipToast;

  // menu + visuals shared by the inline and the fullscreen player
  bool menuOpen = false;
  String menuView = 'main';
  bool muted = false;
  double volume = 1;
  int? seekFlash; // -1 back, 1 forward (brief)
  double captionScale = 0.88; // Small
  bool captionBox = false; // Outline
  Map<String, String> _headers = const {};

  // data from the page
  Range? intro;
  Range? outro;
  double? lengthHint;
  bool autoSkip = true;
  bool autoplay = true;
  bool autoNext = true;

  // callbacks
  VoidCallback? onEnded;
  VoidCallback? onFail;
  void Function(double position, double duration)? onTime;

  Timer? _hide;
  Timer? _toast;
  final _skipped = {SkipKind.intro: false, SkipKind.outro: false};
  final _inside = {SkipKind.intro: false, SkipKind.outro: false};
  bool _endedSent = false;
  double _lastReport = -10;

  bool get ready => c != null && c!.value.isInitialized;
  bool get playing => c?.value.isPlaying ?? false;
  bool get buffering => c?.value.isBuffering ?? false;
  double get position => (c?.value.position.inMilliseconds ?? 0) / 1000;
  double get duration => (c?.value.duration.inMilliseconds ?? 0) / 1000;
  double get aspect => ready && c!.value.aspectRatio > 0 ? c!.value.aspectRatio : 16 / 9;

  @override
  void notifyListeners() {
    if (!_disposed) super.notifyListeners();
  }

  /* ---------------------------------- opening ---------------------------------- */

  /// Opens a new video. Intro/outro times, subtitles and the resume point come with it.
  Future<void> open({
    required String url,
    required bool hls,
    required List<StreamSubtitle> subtitles,
    Map<String, String> headers = const {},
    double startAt = 0,
    Range? intro,
    Range? outro,
    double? length,
  }) async {
    this.intro = intro;
    this.outro = outro;
    lengthHint = length;
    _skipped[SkipKind.intro] = false;
    _skipped[SkipKind.outro] = false;
    _inside[SkipKind.intro] = false;
    _inside[SkipKind.outro] = false;
    skipTarget = null;
    skipToast = null;
    _endedSent = false;
    tracks = subtitles;
    levels = [];
    quality = -1;
    _master = url;
    _hls = hls;
    _headers = headers;
    await _start(url, startAt, play: autoplay);
    if (_disposed) return;
    // captions: English (or the default one) first
    var pick = -1;
    for (var i = 0; i < subtitles.length && pick < 0; i++) {
      if (subtitles[i].isDefault) pick = i;
    }
    for (var i = 0; i < subtitles.length && pick < 0; i++) {
      final l = '${subtitles[i].label} ${subtitles[i].lang}'.toLowerCase();
      if (l.contains('english') || l.startsWith('en')) pick = i;
    }
    if (pick < 0 && subtitles.isNotEmpty) pick = 0;
    unawaited(setTrack(pick));
    if (hls) unawaited(_parseLevels(url));
  }

  Future<void> _start(String url, double startAt, {required bool play}) async {
    final gen = ++_gen;
    loading = true;
    failed = false;
    final old = c;
    c = null;
    old?.removeListener(_tick);
    notifyListeners();
    await old?.dispose();
    if (_disposed || gen != _gen) return;
    final ctrl = VideoPlayerController.networkUrl(
      Uri.parse(url),
      formatHint: _hls ? VideoFormat.hls : VideoFormat.other,
      httpHeaders: _headers,
      videoPlayerOptions: VideoPlayerOptions(mixWithOthers: false),
    );
    try {
      await ctrl.initialize();
      if (_disposed || gen != _gen) {
        await ctrl.dispose();
        return;
      }
      c = ctrl;
      ctrl.addListener(_tick);
      await ctrl.setPlaybackSpeed(speed);
      await ctrl.setVolume(muted ? 0 : volume);
      if (startAt > 1) await ctrl.seekTo(Duration(milliseconds: (startAt * 1000).round()));
      loading = false;
      notifyListeners();
      if (play) await ctrl.play();
      _bump();
    } catch (_) {
      await ctrl.dispose();
      if (_disposed || gen != _gen) return;
      loading = false;
      failed = true;
      notifyListeners();
      onFail?.call();
    }
  }

  Future<void> _parseLevels(String master) async {
    try {
      final r = await http.get(Uri.parse(master), headers: _headers).timeout(const Duration(seconds: 10));
      final text = utf8.decode(r.bodyBytes);
      if (!text.contains('#EXT-X-STREAM-INF')) return;
      final lines = const LineSplitter().convert(text);
      final byHeight = <int, String>{};
      for (var i = 0; i < lines.length; i++) {
        if (!lines[i].startsWith('#EXT-X-STREAM-INF')) continue;
        final m = RegExp(r'RESOLUTION=\d+x(\d+)').firstMatch(lines[i]);
        var j = i + 1;
        while (j < lines.length && (lines[j].trim().isEmpty || lines[j].startsWith('#'))) {
          j++;
        }
        if (m == null || j >= lines.length) continue;
        byHeight[int.parse(m.group(1)!)] = Uri.parse(master).resolve(lines[j].trim()).toString();
      }
      if (_disposed || _master != master) return;
      levels = (byHeight.entries.map((e) => QualityLevel(e.key, e.value)).toList()..sort((a, b) => b.height.compareTo(a.height)));
      notifyListeners();
    } catch (_) {}
  }

  Future<void> setQuality(int index) async {
    if (_master == null || index == quality) return;
    final url = index < 0 ? _master! : levels[index].url;
    quality = index;
    final at = position;
    final wasPlaying = playing;
    await _start(url, at, play: wasPlaying || !ready);
  }

  /* --------------------------------- captions --------------------------------- */

  Future<void> setTrack(int i) async {
    track = i;
    _caps = [];
    caption = '';
    notifyListeners();
    if (i < 0 || i >= tracks.length) return;
    try {
      final r = await http.get(Uri.parse(tracks[i].src), headers: tracks[i].headers).timeout(const Duration(seconds: 20));
      final text = utf8.decode(r.bodyBytes, allowMalformed: true);
      if (track != i || _disposed) return;
      var caps = WebVTTCaptionFile(text).captions;
      if (caps.isEmpty) caps = SubRipCaptionFile(text).captions;
      _caps = caps;
    } catch (_) {}
  }

  String _captionAt(Duration p) {
    for (final cap in _caps) {
      if (p >= cap.start && p <= cap.end) return cap.text;
    }
    return '';
  }

  /* ---------------------------------- controls ---------------------------------- */

  Future<void> togglePlay() async {
    final v = c;
    if (v == null) return;
    if (v.value.isPlaying) {
      await v.pause();
    } else {
      if (duration > 0 && position >= duration - 0.5) await v.seekTo(Duration.zero);
      await v.play();
    }
    _bump();
  }

  Future<void> seekTo(double seconds) async {
    final v = c;
    if (v == null) return;
    final t = seconds.clamp(0, duration > 0 ? duration : seconds).toDouble();
    await v.seekTo(Duration(milliseconds: (t * 1000).round()));
  }

  Future<void> seekBy(double delta) => seekTo(position + delta);

  Future<void> setSpeed(double s) async {
    speed = s;
    await c?.setPlaybackSpeed(s);
    notifyListeners();
  }

  Future<void> toggleMute() async {
    muted = !muted;
    await c?.setVolume(muted ? 0 : volume);
    notifyListeners();
    _bump();
  }

  void flash(int dir) {
    seekFlash = dir;
    notifyListeners();
    Timer(const Duration(milliseconds: 550), () {
      if (seekFlash == dir) {
        seekFlash = null;
        notifyListeners();
      }
    });
  }

  void openMenu() {
    menuView = 'main';
    menuOpen = true;
    _hide?.cancel();
    controlsVisible = true;
    notifyListeners();
  }

  void closeMenu() {
    menuOpen = false;
    notifyListeners();
    _bump();
  }

  void setMenuView(String v) {
    menuView = v;
    notifyListeners();
  }

  Future<void> loadCaptionPrefs() async {
    try {
      final p = await SharedPreferences.getInstance();
      captionScale = p.getDouble('anibiar:capScale') ?? 0.88;
      captionBox = p.getBool('anibiar:capBox') ?? false;
      notifyListeners();
    } catch (_) {}
  }

  Future<void> setCaptionStyle({double? scale, bool? box}) async {
    if (scale != null) captionScale = scale;
    if (box != null) captionBox = box;
    notifyListeners();
    try {
      final p = await SharedPreferences.getInstance();
      await p.setDouble('anibiar:capScale', captionScale);
      await p.setBool('anibiar:capBox', captionBox);
    } catch (_) {}
  }

  /* ------------------------------ controls visibility ------------------------------ */

  void showControls() {
    controlsVisible = true;
    notifyListeners();
    _bump();
  }

  void hideControls() {
    _hide?.cancel();
    controlsVisible = false;
    notifyListeners();
  }

  void toggleControls() => controlsVisible ? hideControls() : showControls();

  /// Restart the auto-hide timer (controls hide 3.5s after the last touch while playing).
  void _bump() {
    _hide?.cancel();
    if (!controlsVisible || !playing) return;
    _hide = Timer(const Duration(milliseconds: 3500), () {
      if (playing && !seeking && !menuOpen) hideControls();
    });
  }

  void touch() => _bump();

  /* ----------------------------------- skipping ----------------------------------- */

  Range? _range(SkipKind k) => k == SkipKind.intro ? intro : outro;

  void skipTo(SkipKind kind, {required bool automatic}) {
    final r = _range(kind);
    if (r == null || !ready) return;
    final from = position;
    _skipped[kind] = true;
    skipTarget = null;
    seekTo(r.end);
    if (automatic) {
      skipToast = (kind: kind, from: from);
      _toast?.cancel();
      _toast = Timer(const Duration(seconds: 5), () {
        skipToast = null;
        notifyListeners();
      });
    }
    notifyListeners();
  }

  void undoSkip() {
    final t = skipToast;
    if (t == null) return;
    seekTo(t.from);
    skipToast = null;
    notifyListeners();
  }

  void _checkSkip() {
    final len = duration;
    // Times made for a differently cut video would skip the wrong part: ignore them.
    bool inside(Range? r) => r != null && position >= r.start && position < r.end && !(len > 0 && r.end > len + 1);
    final now = {SkipKind.intro: inside(intro), SkipKind.outro: inside(outro)};
    for (final k in SkipKind.values) {
      // The viewer seeked into the range by hand: never auto-skip it, but keep the Skip button.
      if (now[k]! && !_inside[k]! && position - (_range(k)?.start ?? 0) > 2) _skipped[k] = true;
      _inside[k] = now[k]!;
    }
    final kind = now[SkipKind.intro]! ? SkipKind.intro : (now[SkipKind.outro]! ? SkipKind.outro : null);
    if (kind != null && autoSkip && !_skipped[kind]! && playing) {
      skipTo(kind, automatic: true);
      return;
    }
    if (kind != skipTarget) skipTarget = kind;
  }

  /* ------------------------------------- tick ------------------------------------- */

  bool _wasPlaying = false;

  void _tick() {
    final v = c;
    if (v == null) return;
    if (v.value.hasError && !failed) {
      failed = true;
      notifyListeners();
      onFail?.call();
      return;
    }
    final p = v.value.position;
    caption = _captionAt(p);
    _checkSkip();
    if (v.value.isPlaying != _wasPlaying) {
      _wasPlaying = v.value.isPlaying;
      if (_wasPlaying) {
        WakelockPlus.enable();
        _bump();
      } else {
        WakelockPlus.disable();
        _hide?.cancel();
        controlsVisible = true;
      }
    }
    if ((position - _lastReport).abs() >= 5 && duration > 0) {
      _lastReport = position;
      onTime?.call(position, duration);
    }
    if (!_endedSent && duration > 0 && position >= duration - 0.6 && !v.value.isPlaying) {
      _endedSent = true;
      onTime?.call(duration, duration);
      onEnded?.call();
    }
    notifyListeners();
  }

  /// Report where the viewer is (called when leaving the page).
  void flush() {
    if (ready && duration > 0) onTime?.call(position, duration);
  }

  @override
  void dispose() {
    _disposed = true;
    _hide?.cancel();
    _toast?.cancel();
    WakelockPlus.disable();
    c?.removeListener(_tick);
    c?.dispose();
    super.dispose();
  }
}
