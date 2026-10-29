import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:url_launcher/url_launcher.dart' show LaunchMode;

import '../core/config.dart';
import 'library.dart';

class Profile {
  Profile({this.username, this.avatarUrl, this.bannerUrl, this.bio, this.handle, this.decoration});
  final String? username;
  final String? avatarUrl;
  final String? bannerUrl;
  final String? bio;
  final String? handle;
  final String? decoration;

  factory Profile.fromRow(Map<String, dynamic> r) => Profile(
        username: r['username'] as String?,
        avatarUrl: r['avatar_url'] as String?,
        bannerUrl: r['banner_url'] as String?,
        bio: r['bio'] as String?,
        handle: r['handle'] as String?,
        decoration: r['decoration'] as String?,
      );
}

/// Signed-in user + profile (same `profiles` table as the website).
class Auth extends ChangeNotifier {
  Auth._();
  static final Auth instance = Auth._();

  SupabaseClient get db => Supabase.instance.client;
  User? user;
  Profile? profile;
  bool ready = false;
  StreamSubscription<AuthState>? _sub;

  Future<void> init() async {
    user = db.auth.currentUser;
    _sub = db.auth.onAuthStateChange.listen((event) {
      final changed = user?.id != event.session?.user.id;
      user = event.session?.user;
      ready = true;
      if (changed) {
        profile = null;
        unawaited(loadProfile());
      }
      notifyListeners();
    });
    await loadProfile();
    ready = true;
    notifyListeners();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> loadProfile() async {
    final u = user;
    if (u == null) {
      profile = null;
      notifyListeners();
      return;
    }
    try {
      final row = await db.from('profiles').select('username, avatar_url, banner_url, bio, handle, decoration').eq('id', u.id).maybeSingle();
      profile = row == null ? Profile() : Profile.fromRow(Map<String, dynamic>.from(row));
    } catch (_) {
      profile ??= Profile();
    }
    notifyListeners();
  }

  /// Called after the profile was saved: refreshes the website's Redis copy first so every device sees the change.
  Future<void> refreshProfile() async {
    await Library.instance.bump();
    await loadProfile();
  }

  String? _meta(List<String> keys) {
    final m = user?.userMetadata ?? const {};
    for (final k in keys) {
      final v = m[k];
      if (v is String && v.isNotEmpty) return v;
    }
    return null;
  }

  bool get signedIn => user != null;
  String? get name {
    final p = profile?.username;
    if (p != null && p.isNotEmpty) return p;
    return _meta(['full_name', 'name', 'user_name']) ?? user?.email?.split('@').first;
  }

  String? get avatar {
    final p = profile?.avatarUrl;
    if (p != null && p.isNotEmpty) return p;
    return _meta(['avatar_url', 'picture']);
  }

  String? get banner => profile?.bannerUrl;
  String? get handle => profile?.handle ?? user?.email?.split('@').first;
  String? get decoration => profile?.decoration;

  /* ------------------------------- sign in / sign up ------------------------------- */

  Future<void> signIn(String email, String password) async {
    await db.auth.signInWithPassword(email: email.trim(), password: password);
  }

  /// Returns true when a confirmation email was sent (no session yet).
  Future<bool> signUp(String email, String password) async {
    final res = await db.auth.signUp(email: email.trim(), password: password, emailRedirectTo: AppConfig.authRedirect);
    return res.session == null;
  }

  Future<void> oauth(OAuthProvider provider) async {
    await db.auth.signInWithOAuth(provider, redirectTo: AppConfig.authRedirect, authScreenLaunchMode: LaunchMode.externalApplication);
  }

  Future<void> resetPassword(String email) => db.auth.resetPasswordForEmail(email.trim(), redirectTo: AppConfig.authRedirect);

  Future<void> signOut() async {
    await db.auth.signOut();
    user = null;
    profile = null;
    notifyListeners();
  }

  /* ---------------------------------- profile edits ---------------------------------- */

  static const _bucket = 'profiles';
  static const _ext = {'png': 'image/png', 'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'webp': 'image/webp', 'gif': 'image/gif'};

  /// Uploads to the viewer's own folder and returns the public address.
  Future<String> uploadImage(File file, String kind) async {
    final u = user;
    if (u == null) throw Exception('Log in first.');
    final ext = file.path.split('.').last.toLowerCase();
    final type = _ext[ext];
    if (type == null) throw Exception('Use a PNG, JPG, WebP or GIF image.');
    if (await file.length() > 8 * 1024 * 1024) throw Exception('Images can be up to 8 MB.');
    final path = '${u.id}/$kind-${DateTime.now().millisecondsSinceEpoch}.${ext == 'jpeg' ? 'jpg' : ext}';
    await db.storage.from(_bucket).upload(path, file, fileOptions: FileOptions(contentType: type, cacheControl: '31536000', upsert: false));
    return db.storage.from(_bucket).getPublicUrl(path);
  }

  Future<void> deleteImage(String? url) async {
    if (url == null) return;
    const marker = '/object/public/$_bucket/';
    final i = url.indexOf(marker);
    if (i < 0) return;
    try {
      await db.storage.from(_bucket).remove([Uri.decodeComponent(url.substring(i + marker.length).split('?').first)]);
    } catch (_) {}
  }

  Future<void> saveProfile(Map<String, dynamic> patch) async {
    final u = user;
    if (u == null) throw Exception('Log in first.');
    await db.from('profiles').upsert({'id': u.id, ...patch}, onConflict: 'id');
    await refreshProfile();
  }

  Future<void> changeEmail(String email) async {
    await db.auth.updateUser(UserAttributes(email: email.trim()), emailRedirectTo: AppConfig.authRedirect);
  }
}

String friendlyError(Object e) {
  if (e is AuthException) return e.message;
  if (e is PostgrestException) return e.code == '23505' ? 'That username is already taken.' : e.message;
  if (e is StorageException) return e.message;
  final s = e.toString();
  return s.startsWith('Exception: ') ? s.substring(11) : s;
}
