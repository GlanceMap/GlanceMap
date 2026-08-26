#!/usr/bin/env bash
set -euo pipefail

./gradlew \
  ktlintCheck \
  detekt \
  :app:compileDebugKotlin \
  :glancemapcompanionapp:compileDebugKotlin \
  :app:testDebugUnitTest \
  :glancemapcompanionapp:testDebugUnitTest \
  :app:lintDebug \
  :glancemapcompanionapp:lintDebug

git diff --check
