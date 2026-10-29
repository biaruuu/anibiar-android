/// Build-time settings. Override with --dart-define=NAME=value.
class AppConfig {
  /// Your deployed AniBiar TV site. The app uses its /api routes for episodes, streams,
  /// skip times and the cached AniList door, so it sees exactly the same data as the website.
  static const siteUrl = String.fromEnvironment('SITE_URL', defaultValue: 'https://anibiartv.vercel.app');

  /// Same Supabase project as the website (the publishable key is public by design; row-level security protects the data).
  static const supabaseUrl = String.fromEnvironment('SUPABASE_URL', defaultValue: 'https://zrlzsrakfzfayegvvisg.supabase.co');
  static const supabaseKey = String.fromEnvironment('SUPABASE_KEY', defaultValue: 'sb_publishable_PIaz0dn6UgqXTLwm80fBfw_hImeudBy');

  /// OAuth (Google / Discord) returns here. Add it to Supabase > Authentication > URL Configuration > Redirect URLs.
  static const authRedirect = 'io.anibiar.app://login-callback';

  static const appName = 'AniBiar';

  /// Turns a site-relative address ("/api/proxy?t=...") into a full one.
  static String abs(String path) {
    if (path.startsWith('http://') || path.startsWith('https://')) return path;
    return path.startsWith('/') ? '$siteUrl$path' : '$siteUrl/$path';
  }
}
