#!/usr/bin/env bash

set -euo pipefail

missing=()
for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD EXPECTED_SIGNER_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done

if (( ${#missing[@]} != 0 )); then
  echo "ERROR: Stable release signing credentials are required. Missing: ${missing[*]}" >&2
  echo "Refusing to create an APK that cannot update existing TunnelGuard installations." >&2
  exit 1
fi

echo "All permanent stable signing credentials are present."
