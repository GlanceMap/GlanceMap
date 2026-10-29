#!/usr/bin/env bash
# ICU is a pinned validation tool, not an Android application dependency.
set -euo pipefail

translation_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
translation_cache="${XDG_CACHE_HOME:-${HOME}/.cache}/glancemap-translation-checks"
translation_jar="${ICU4J_JAR:-${translation_cache}/icu4j-79.1.jar}"
translation_sha="28b292e60d0d00c496bae88a15b2615f89f781be13670aeac9dc90f189570e07"

if [[ ! -f "$translation_jar" ]]; then
  mkdir -p "$(dirname "$translation_jar")"
  translation_download="$(mktemp "${translation_jar}.XXXXXX")"
  trap 'rm -f "$translation_download"' EXIT
  curl --fail --location --silent --show-error --retry 2 \
    'https://repo.maven.apache.org/maven2/com/ibm/icu/icu4j/79.1/icu4j-79.1.jar' \
    --output "$translation_download"
  python3 -c 'import hashlib,sys; sys.exit(0 if hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest() == sys.argv[2] else "ICU4J checksum mismatch")' "$translation_download" "$translation_sha"
  mv "$translation_download" "$translation_jar"
fi
python3 -c 'import hashlib,sys; sys.exit(0 if hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest() == sys.argv[2] else "ICU4J checksum mismatch")' "$translation_jar" "$translation_sha"
python3 "$translation_root/scripts/check-watch-translations.py" --icu4j-jar "$translation_jar" "$@"
