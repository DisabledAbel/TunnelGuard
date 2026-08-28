#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 OWNER/REPOSITORY TAG" >&2
  exit 2
fi

repository=$1
tag=$2
response_file=$(mktemp)
error_file=$(mktemp)
trap 'rm -f "$response_file" "$error_file"' EXIT

set +e
gh api "repos/${repository}/releases/tags/${tag}" \
  --jq '[.draft, .prerelease] | @tsv' >"$response_file" 2>"$error_file"
api_status=$?
set -e

if (( api_status != 0 )); then
  if grep -Eq 'HTTP 404|404 Not Found' "$error_file"; then
    echo "No GitHub Release exists with exact tag ${tag}; publishing remains enabled."
    echo "STABLE_RELEASE_EXISTS=false" >>"${GITHUB_ENV:?GITHUB_ENV must be set}"
    exit 0
  fi

  echo "ERROR: Could not determine whether GitHub Release ${tag} exists; failing closed." >&2
  cat "$error_file" >&2
  exit 1
fi

release_state=$(cat "$response_file")
case "$release_state" in
  $'false\tfalse')
    echo "GitHub Release ${tag} is already published as a stable release. This run is a successful no-op."
    echo "STABLE_RELEASE_EXISTS=true" >>"${GITHUB_ENV:?GITHUB_ENV must be set}"
    {
      echo "## Stable release already exists"
      echo
      echo "GitHub Release \`${tag}\` is already published. This duplicate run succeeded without testing, building, signing, generating metadata or notes, uploading assets, or changing the release's Latest state."
    } >>"${GITHUB_STEP_SUMMARY:?GITHUB_STEP_SUMMARY must be set}"
    ;;
  $'true\tfalse')
    echo "ERROR: GitHub Release ${tag} exists as a draft. Manual review is required; refusing to publish or modify it." >&2
    exit 1
    ;;
  $'false\ttrue'|$'true\ttrue')
    echo "ERROR: GitHub Release ${tag} exists as a prerelease. Manual review is required; refusing to publish or modify it." >&2
    exit 1
    ;;
  *)
    echo "ERROR: Could not parse the GitHub API state for Release ${tag}; failing closed." >&2
    exit 1
    ;;
esac
