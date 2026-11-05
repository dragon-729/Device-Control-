#!/usr/bin/env sh
# Generates gradlew / gradlew.bat and gradle-wrapper.jar (binary files cannot be shipped in this source drop).
# Requires a local Gradle install (>= 8.9). Android Studio users can skip this: Studio downloads Gradle by itself.
set -e
cd "$(dirname "$0")/.."
gradle wrapper --gradle-version 8.9 --distribution-type bin
echo "Done. Commit gradlew, gradlew.bat and gradle/wrapper/gradle-wrapper.jar."
