import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../core/config.dart';

/// Continue Watching, Favorites and Watchlist. Always saved on this phone; when signed in it is also
/// saved in the same Supabase tables the website uses, so everything follows the account.

class HistoryItem {
  HistoryItem({
    required this.animeId,
    required this.episode,
    required this.dub,
    required this.position,
    required this.duration,
    required this.title,
    this.image,
    this.totalEpisodes,
    required this.updatedAt,
  });

  final int animeId;
  final int episode;
  final bool dub;
  final double position;
  final double duration;
  final String title;
  final String? image;
  final int? totalEpisodes;
  final int updatedAt; // ms since epoch

  /// A show you finished (past 92%) of its last episode is not worth "continuing".
  bool get finished => duration > 0 && position / duration > 0.92 && (totalEpisodes == null || episode >= totalEpisodes!);

  Map<String, dynamic> toLocal() => {
        'animeId': animeId,
        'episode': episode,
        'audio': dub ? 'dub' : 'sub',
        'position': position,
        'duration': duration,
        'title': title,
        'image': image,
        'totalEpisodes': totalEpisodes,
        'updatedAt': updatedAt,
      };

  factory HistoryItem.fromLocal(Map<String, dynamic> j) => HistoryItem(
        animeId: (j['animeId'] as num).toInt(),
        episode: (j['episode'] as num).toInt(),
        dub: j['audio'] == 'dub',
        position: (j['position'] as num).toDouble(),
        duration: (j['duration'] as num).toDouble(),
        title: j['title'] as String? ?? '',
        image: j['image'] as String?,
        totalEpisodes: (j['totalEpisodes'] as num?)?.toInt(),
        updatedAt: (j['updatedAt'] as num).toInt(),
      );

  factory HistoryItem.fromRow(Map<String, dynamic> r) => HistoryItem(
        animeId: (r['anime_id'] as num).toInt(),
        episode: (r['episode'] as num).toInt(),
        dub: r['audio'] == 'dub',
        position: (r['position'] as num).toDouble(),
        duration: (r['duration'] as num).toDouble(),
        title: r['title'] as String? ?? '',
        image: r['image'] as String?,
        totalEpisodes: (r['total_episodes'] as num?)?.toInt(),
        updatedAt: DateTime.parse(r['updated_at'] as String).millisecondsSinceEpoch,
      );

  Map<String, dynamic> toRow(String userId) => {
        'user_id': userId,
        'anime_id': animeId,
        'episode': episode,
        'audio': dub ? 'dub' : 'sub',
        'position': position,
        'duration': duration,
        'title': title,
        'image': image,
        'total_episodes': totalEpisodes,
        'updated_at': DateTime.fromMillisecondsSinceEpoch(updatedAt, isUtc: true).toIso8601String(),
      };
}

class ListItem {
  ListItem({required this.animeId, required this.title, this.image, required this.addedAt});
  final int animeId;
  final String title;
  final String? image;
  final int addedAt;

  Map<String, dynamic> toLocal() => {'animeId': animeId, 'title': title, 'image': image, 'addedAt': addedAt};
  factory ListItem.fromLocal(Map<String, dynamic> j) => ListItem(
        animeId: (j['animeId'] as num).toInt(),
        title: j['title'] as String? ?? '',
        image: j['image'] as String?,
        addedAt: (j['addedAt'] as num).toInt(),
      );
  factory ListItem.fromRow(Map<String, dynamic> r) => ListItem(
        animeId: (r['anime_id'] as num).toInt(),
        title: r['title'] as String? ?? '',
        image: r['image'] as String?,
        addedAt: DateTime.parse(r['added_at'] as String).millisecondsSinceEpoch,
      );
}

enum Collection { list, favorites }

class Library {
  Library._();
  static final Library instance = Library._();

  SupabaseClient get _db => Supabase.instance.client;
  String? get _uid => _db.auth.currentUser?.id;

  static const _historyKey = 'anibiar:history';
  static const _listKey = 'anibiar:mylist';
  static const _favKey = 'anibiar:favorites';

  Future<List<Map<String, dynamic>>> _read(String key) async {
    final p = await SharedPreferences.getInstance();
    try {
      final v = jsonDecode(p.getString(key) ?? '[]');
      if (v is List) return v.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList();
    } catch (_) {}
    return [];
  }

  Future<void> _write(String key, List<Map<String, dynamic>> value) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(key, jsonEncode(value));
  }

  /// Tells the website's server something was written so its Redis copy is refreshed (best effort).
  Future<void> bump() async {
    final token = _db.auth.currentSession?.accessToken;
    if (token == null) return;
    try {
      await http.post(Uri.parse('${AppConfig.siteUrl}/api/me/library'), headers: {'Authorization': 'Bearer $token'}).timeout(const Duration(seconds: 8));
    } catch (_) {}
  }

  /* ----------------------------------- history ----------------------------------- */

  Future<List<HistoryItem>> localHistory() async {
    final l = (await _read(_historyKey)).map(HistoryItem.fromLocal).toList();
    l.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return l;
  }

  DateTime _lastBump = DateTime.fromMillisecondsSinceEpoch(0);

  Future<void> saveProgress(HistoryItem item) async {
    final all = await localHistory();
    final next = [item, ...all.where((h) => h.animeId != item.animeId)].take(60).map((h) => h.toLocal()).toList();
    await _write(_historyKey, next);
    final uid = _uid;
    if (uid == null) return;
    try {
      await _db.from('watch_history').upsert(item.toRow(uid), onConflict: 'user_id,anime_id');
      if (DateTime.now().difference(_lastBump).inSeconds > 15) {
        _lastBump = DateTime.now();
        bump();
      }
    } catch (_) {
      // offline: the local copy is still there
    }
  }

  Future<List<HistoryItem>> loadHistory() async {
    final local = await localHistory();
    final uid = _uid;
    if (uid == null) return local;
    try {
      final rows = await _db.from('watch_history').select().eq('user_id', uid).order('updated_at', ascending: false).limit(60);
      final remote = (rows as List).map((e) => HistoryItem.fromRow(Map<String, dynamic>.from(e as Map))).toList();
      final byId = {for (final h in remote) h.animeId: h};
      final push = <HistoryItem>[];
      for (final h in local) {
        final r = byId[h.animeId];
        if (r == null || h.updatedAt > r.updatedAt) {
          byId[h.animeId] = h;
          push.add(h);
        }
      }
      if (push.isNotEmpty) {
        await _db.from('watch_history').upsert(push.map((h) => h.toRow(uid)).toList(), onConflict: 'user_id,anime_id');
        bump();
      }
      final merged = byId.values.toList()..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
      await _write(_historyKey, merged.take(60).map((h) => h.toLocal()).toList());
      return merged;
    } catch (_) {
      return local;
    }
  }

  Future<void> removeHistory(int animeId) async {
    final all = await _read(_historyKey);
    await _write(_historyKey, all.where((h) => h['animeId'] != animeId).toList());
    final uid = _uid;
    if (uid == null) return;
    try {
      await _db.from('watch_history').delete().eq('user_id', uid).eq('anime_id', animeId);
      bump();
    } catch (_) {}
  }

  /* ------------------------------ my list and favorites ------------------------------ */

  String _table(Collection c) => c == Collection.favorites ? 'favorites' : 'my_list';
  String _key(Collection c) => c == Collection.favorites ? _favKey : _listKey;

  Future<List<ListItem>> localCollection(Collection c) async {
    final l = (await _read(_key(c))).map(ListItem.fromLocal).toList();
    l.sort((a, b) => b.addedAt.compareTo(a.addedAt));
    return l;
  }

  Future<List<ListItem>> load(Collection c) async {
    final mine = await localCollection(c);
    final uid = _uid;
    if (uid == null) return mine;
    try {
      final rows = await _db.from(_table(c)).select().eq('user_id', uid).order('added_at', ascending: false).limit(500);
      final remote = (rows as List).map((e) => ListItem.fromRow(Map<String, dynamic>.from(e as Map))).toList();
      final have = remote.map((r) => r.animeId).toSet();
      final extra = mine.where((l) => !have.contains(l.animeId)).toList();
      if (extra.isNotEmpty) {
        await _db.from(_table(c)).upsert(
              extra
                  .map((l) => {
                        'user_id': uid,
                        'anime_id': l.animeId,
                        'title': l.title,
                        'image': l.image,
                        'added_at': DateTime.fromMillisecondsSinceEpoch(l.addedAt, isUtc: true).toIso8601String(),
                      })
                  .toList(),
              onConflict: 'user_id,anime_id',
            );
        bump();
      }
      final merged = [...remote, ...extra]..sort((a, b) => b.addedAt.compareTo(a.addedAt));
      await _write(_key(c), merged.map((e) => e.toLocal()).toList());
      return merged;
    } catch (_) {
      return mine;
    }
  }

  Future<bool> contains(Collection c, int animeId) async => (await load(c)).any((l) => l.animeId == animeId);

  /// Returns true when the title is in the collection afterwards.
  Future<bool> toggle(Collection c, {required int animeId, required String title, String? image}) async {
    final all = await _read(_key(c));
    final has = all.any((l) => l['animeId'] == animeId);
    final next = has
        ? all.where((l) => l['animeId'] != animeId).toList()
        : [ListItem(animeId: animeId, title: title, image: image, addedAt: DateTime.now().millisecondsSinceEpoch).toLocal(), ...all];
    await _write(_key(c), next);
    final uid = _uid;
    if (uid != null) {
      try {
        final t = _db.from(_table(c));
        if (has) {
          await t.delete().eq('user_id', uid).eq('anime_id', animeId);
        } else {
          await t.upsert({'user_id': uid, 'anime_id': animeId, 'title': title, 'image': image}, onConflict: 'user_id,anime_id');
        }
        bump();
      } catch (_) {}
    }
    return !has;
  }
}
