# AniBiar (Android, Kotlin)

The AniBiar TV website as a native Android app: Kotlin, Jetpack Compose and the Media3 (ExoPlayer) video player.
It talks to the same services as the website: AniList, the Anivexa streaming API, AniSkip, TMDB and your Supabase project
(accounts, watch history, favorites, watchlist, avatar decorations).

## Get the app

Every push to `main` builds an APK. Open **Releases > AniBiar (latest build)** and download `app-release.apk`.
Builds are signed with one fixed key, so a new build installs over the old one. (If you still have the old Flutter
version installed, uninstall it once: it was signed with a different key.)

## Settings (GitHub secrets)

Repo **Settings > Secrets and variables > Actions > Repository secrets > New repository secret**. All are optional:

| Name | What it does |
| --- | --- |
| `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN` | shared cache for episode lists (faster) |
| `TMDB_KEY` | title logos and the artwork gallery |
| `ANIVEXA_URL` | only if your streaming API is not `https://anibiartv-api.vercel.app` |

A key inside an APK can be extracted by anyone who has the file: use a separate Upstash database for the app.
After adding secrets, run **Actions > Build APK > Run workflow**.

Google / Discord login needs `io.anibiar.app://login-callback` in Supabase > Authentication > URL Configuration > Redirect URLs.

## Connection report

Settings > Connection report runs a quick test of AniList, your site and the streaming API with timings and lists the
latest network calls, so a failure can be read instead of guessed.

## Build it yourself

```bash
gradle assembleRelease      # JDK 17 and the Android SDK; the APK is app/build/outputs/apk/release/
```
