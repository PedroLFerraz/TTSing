#!/bin/sh
# Builds the signed release APK and drops it here in the project folder as TTSing.apk,
# ready for `adb install -r TTSing.apk`.
set -e
cd "$(dirname "$0")"

if [ ! -f keystore.properties ]; then
    echo "keystore.properties is missing, so the release APK would be unsigned." >&2
    echo "See \"Build the APK\" in README.md." >&2
    exit 1
fi

./gradlew :app:assembleRelease

cp app/build/outputs/apk/release/app-release.apk TTSing.apk
echo "APK: $(pwd)/TTSing.apk ($(du -m TTSing.apk | cut -f1) MB)"
