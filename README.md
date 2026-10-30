# AniBiar for Android

The AniBiar TV website as a native Android app, written in Dart with Flutter.

It uses the same data as the website:

| What | Where it comes from |
| --- | --- |
| Anime lists, search, details | AniList (through your site's cached `/api/anilist`, falling back to AniList) |
| Episodes, streams, subtitles, skip times | Your site's `/api/anime/:id/episodes`, `/api/anime/:id/watch`, `/api/skip` |
| Accounts, profile, History, Favorites, Watchlist | The same Supabase project (`profiles`, `watch_history`, `favorites`, `my_list`, `avatar_decorations`, `profiles` storage bucket) |
| Avatar decorations | `/api/decorations` (sprite sheets played frame by frame) |

So a show you watch on the website shows up under Continue Watching in the app, and the other way round.

## Look and feel

Discord "Onyx" dark theme with the same liquid-glass style as the website: floating glass bottom bar
(Home, Search, Profile, Settings), glass buttons, glass Sub/Dub switch and glass panels.

## Video player

A custom player on top of ExoPlayer: tap to show or hide controls, double-tap left/right to go back or forward 10 s,
double-tap the middle for fullscreen, seek bar, speed, HLS quality levels, soft-subtitle tracks drawn in Poppins with an outline,
Skip Intro / Skip Outro (with auto skip and undo), autoplay, auto next episode, resume where you left off, and automatic fallback to the next server.

## Get the APK

Every push to `main` builds the app on GitHub Actions. Open **Releases > AniBiar (latest build)** and download `app-release.apk`,
or open the **Actions** tab and download the `AniBiar-apk` artifact.

### Point it at your site

The app needs your deployed website address (the one that has `ANIVEXA_API_URL` set, because that is what serves episodes and streams).
The default is `https://anibiartv.vercel.app`. To change it:

* GitHub: **Settings > Secrets and variables > Actions > Variables > New repository variable**, name `SITE_URL`, value `https://your-site.vercel.app`.
* Local builds: `flutter run --dart-define=SITE_URL=https://your-site.vercel.app`

### Google / Discord sign-in

Email and password work right away. For Google and Discord, add this to **Supabase > Authentication > URL Configuration > Redirect URLs**:

```
io.anibiar.app://login-callback
```

## Adding your Upstash Redis (optional, makes episodes load faster)

The app already works without it (it keeps its own copy on the phone). To add Upstash as a shared cache:

1. Open the repo on GitHub: **Settings > Secrets and variables > Actions > Repository secrets > New repository secret**.
   (Use *Repository secrets*, not *Environments*.)
2. Add `UPSTASH_REDIS_REST_URL` (looks like `https://xxxx.upstash.io`) and `UPSTASH_REDIS_REST_TOKEN`.
   Use a **separate** Upstash database for the app, or at least a token that can only touch app keys.
3. For the title logos and artwork (same as the website) add `TMDB_KEY` with your free TMDB API key (the v3 key, same one as in Vercel).
4. If your streaming API is not `https://anibiartv-api.vercel.app`, also add a secret named `ANIVEXA_URL`.
5. Go to **Actions > Build APK > Run workflow**. When it finishes, install the new APK from Releases.

A key inside an APK can be extracted by anyone who has the file, so never put a token with write access to data you care about in it.

## Build it yourself

```bash
git clone https://github.com/biaruuu/anibiar-android.git
cd anibiar-android
bash tool/setup_android.sh      # generates the android/ folder and applies the manifest + icons
flutter pub get
flutter run                     # or: flutter build apk --release
```

Needs Flutter (stable) and the Android SDK.

## Project layout

```
lib/
  main.dart                 app start (Supabase + auth)
  core/                     config, theme (Onyx colours), navigation helpers
  services/                 anilist.dart, api.dart (your site), auth.dart, library.dart (Supabase + local)
  player/                   player_model.dart (logic), player_view.dart (controls + menus)
  widgets/                  glass.dart (glass buttons, switch, panels), cards, avatar + decorations
  screens/                  shell (glass nav), home, search, info, watch, profile, settings, login
android_overlay/            AndroidManifest.xml and launcher icons applied by tool/setup_android.sh
```

## Not in the app yet

* The profile "Share" picture / Live photo maker (it needs the canvas video encoder from the website).
* Desktop and tablet layouts: this is a phone app.
