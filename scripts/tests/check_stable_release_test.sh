#!/usr/bin/env bash

set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$root_dir/scripts/check_stable_release.sh"
workflow="$root_dir/.github/workflows/release.yml"
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT

cat >"$test_dir/gh" <<'EOF'
#!/usr/bin/env bash
endpoint=${2:?expected gh api endpoint}
if [[ "$endpoint" == 'repos/DisabledAbel/TunnelGuard' ]]; then
  if [[ "${MOCK_GH_STATE:?}" == inaccessible_repository ]]; then
    echo 'gh: repository not found (HTTP 404)' >&2
    exit 1
  fi
  echo 'DisabledAbel/TunnelGuard'
  exit 0
fi

if [[ "$endpoint" != 'repos/DisabledAbel/TunnelGuard/releases/tags/v1.1.9' ]]; then
  echo "unexpected endpoint: $endpoint" >&2
  exit 2
fi

case "${MOCK_GH_STATE:?}" in
  published) printf 'false\tfalse\n' ;;
  missing) echo 'gh: release not found (HTTP 404)' >&2; exit 1 ;;
  draft) printf 'true\tfalse\n' ;;
  prerelease) printf 'false\ttrue\n' ;;
  api_failure) echo 'gh: authentication failed (HTTP 401)' >&2; exit 1 ;;
  parse_failure) echo 'unexpected response' ;;
esac
EOF
chmod +x "$test_dir/gh"

run_case() {
  local state=$1 expected_status=$2 expected_message=$3
  : >"$test_dir/env"
  : >"$test_dir/summary"
  set +e
  output=$(PATH="$test_dir:$PATH" MOCK_GH_STATE="$state" \
    GITHUB_ENV="$test_dir/env" GITHUB_STEP_SUMMARY="$test_dir/summary" \
    "$script" DisabledAbel/TunnelGuard v1.1.9 2>&1)
  status=$?
  set -e
  if [[ $status -ne $expected_status ]]; then
    echo "FAIL: $state returned $status, expected $expected_status" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected_message"* ]]; then
    echo "FAIL: $state did not report '$expected_message'" >&2
    exit 1
  fi
}

run_case published 0 'successful no-op'
grep -q '^STABLE_RELEASE_EXISTS=true$' "$test_dir/env"
grep -q 'without testing, building, signing' "$test_dir/summary"

run_case missing 0 'publishing remains enabled'
grep -q '^STABLE_RELEASE_EXISTS=false$' "$test_dir/env"

run_case draft 1 'exists as a draft. Manual review is required'
run_case prerelease 1 'exists as a prerelease. Manual review is required'
run_case api_failure 1 'failing closed'
run_case parse_failure 1 'Could not parse'
run_case inaccessible_repository 1 'Could not access repository'
if [[ -s "$test_dir/env" ]]; then
  echo 'FAIL: inaccessible repository wrote a release-existence flag' >&2
  exit 1
fi

# The repository-wide lock makes differently shaped manual and tag inputs share
# one publishing lane. A queued duplicate performs the lookup only after the
# first run has finished creating its release.
grep -q '^concurrency:$' "$workflow"
grep -q '^  group: stable-release$' "$workflow"
grep -q '^  cancel-in-progress: false$' "$workflow"

echo 'All stable release lookup tests passed.'
