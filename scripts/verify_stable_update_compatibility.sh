#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 OWNER/REPOSITORY NEW_APK NEW_VERSION_CODE" >&2
  exit 2
fi

repository=$1
new_apk=$2
new_version_code=$3
aapt_command=${AAPT_COMMAND:-aapt}
apksigner_command=${APKSIGNER_COMMAND:-apksigner}

[[ -f "$new_apk" ]] || { echo "ERROR: New stable APK not found: $new_apk" >&2; exit 1; }
[[ "$new_version_code" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: Invalid new versionCode: $new_version_code" >&2; exit 1; }

release_rows=$(gh api --paginate "repos/${repository}/releases" \
  --jq '.[] | select(.draft == false and .prerelease == false) | [.tag_name, ([.assets[]? | select(.name | test("^TunnelGuard-v?[0-9]+\\.[0-9]+\\.[0-9]+-release\\.apk$"))] | first | .url // "")] | @tsv') || {
  echo "ERROR: Could not inspect previous stable GitHub Releases; failing closed." >&2
  exit 1
}

previous_code=-1
previous_tag=
previous_url=
while IFS=$'\t' read -r tag asset_url; do
  [[ "$tag" =~ ^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || continue
  code=$((10#${BASH_REMATCH[1]} * 10000000 + 10#${BASH_REMATCH[2]} * 10000 + 10#${BASH_REMATCH[3]}))
  if (( code > previous_code )); then
    previous_code=$code
    previous_tag=$tag
    previous_url=$asset_url
  fi
done <<< "$release_rows"

if (( previous_code < 0 )); then
  echo "No previous stable GitHub Release exists; update compatibility comparison skipped."
  exit 0
fi
if [[ -z "$previous_url" ]]; then
  echo "ERROR: Previous stable Release ${previous_tag} has no canonical TunnelGuard release APK." >&2
  exit 1
fi

previous_apk=$(mktemp --suffix=.apk)
trap 'rm -f "$previous_apk"' EXIT
gh api "$previous_url" -H 'Accept: application/octet-stream' > "$previous_apk" || {
  echo "ERROR: Could not download the APK from previous stable Release ${previous_tag}." >&2
  exit 1
}

read_badging_value() {
  local apk=$1 field=$2 badging value
  badging=$($aapt_command dump badging "$apk") || return 1
  value=$(sed -n "s/^package: .*${field}='\([^']*\)'.*/\1/p" <<< "$badging" | head -n 1)
  [[ -n "$value" ]] || return 1
  printf '%s\n' "$value"
}

read_signer() {
  local apk=$1 output digest
  output=$($apksigner_command verify --verbose --print-certs "$apk") || return 1
  digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$output" | head -n 1 | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')
  [[ -n "$digest" ]] || return 1
  printf '%s\n' "$digest"
}

previous_package=$(read_badging_value "$previous_apk" name) || { echo "ERROR: Could not read the previous stable APK package ID." >&2; exit 1; }
new_package=$(read_badging_value "$new_apk" name) || { echo "ERROR: Could not read the new stable APK package ID." >&2; exit 1; }
if [[ "$previous_package" != com.tunnelguard.app || "$new_package" != com.tunnelguard.app ]]; then
  echo "ERROR: Stable TunnelGuard APKs must use package ID com.tunnelguard.app (previous: $previous_package, new: $new_package)." >&2
  exit 1
fi

previous_apk_code=$(read_badging_value "$previous_apk" versionCode) || { echo "ERROR: Could not read the previous stable APK versionCode." >&2; exit 1; }
new_apk_code=$(read_badging_value "$new_apk" versionCode) || { echo "ERROR: Could not read the new stable APK versionCode." >&2; exit 1; }
if [[ "$new_apk_code" != "$new_version_code" ]]; then
  echo "ERROR: New APK versionCode $new_apk_code does not match calculated versionCode $new_version_code." >&2
  exit 1
fi
if (( new_apk_code <= previous_apk_code )); then
  echo "ERROR: New stable versionCode $new_apk_code must be greater than previous stable versionCode $previous_apk_code." >&2
  exit 1
fi

previous_signer=$(read_signer "$previous_apk") || { echo "ERROR: Could not read the previous stable APK signing certificate." >&2; exit 1; }
new_signer=$(read_signer "$new_apk") || { echo "ERROR: Could not read the new stable APK signing certificate." >&2; exit 1; }
if [[ "$new_signer" != "$previous_signer" ]]; then
  echo "ERROR: New TunnelGuard APK cannot update the previous stable release because the signing certificate changed." >&2
  exit 1
fi

echo "Stable update compatibility verified against ${previous_tag}: package ID, signer, and versionCode are compatible."
