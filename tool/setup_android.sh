#!/usr/bin/env bash
# Generates the Android project with the installed Flutter SDK and applies AniBiar's manifest and icons.
# Run once after cloning:  bash tool/setup_android.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# `flutter create` must not touch our code, so keep a copy and put it back afterwards.
tmp="$(mktemp -d)"
cp -r lib "$tmp/lib"
cp pubspec.yaml "$tmp/pubspec.yaml"

flutter create --platforms=android --org com.anibiar --project-name anibiar .

rm -rf lib && cp -r "$tmp/lib" lib
cp "$tmp/pubspec.yaml" pubspec.yaml
rm -rf test

cp android_overlay/AndroidManifest.xml android/app/src/main/AndroidManifest.xml
cp -r android_overlay/res/mipmap-* android/app/src/main/res/

echo "Android project ready. Next: flutter pub get && flutter run"
