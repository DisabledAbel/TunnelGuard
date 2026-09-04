#!/usr/bin/env bash

set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
verify_script="$root_dir/scripts/verify_stable_update_compatibility.sh"
credentials_script="$root_dir/scripts/check_signing_credentials.sh"
gradle_file="$root_dir/app/build.gradle.kts"
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT

# Guard the source identity as well as the built-APK checks in the release script.
grep -q 'applicationId = "com.tunnelguard.app"' "$gradle_file"
grep -q 'applicationIdSuffix = ".alpha"' "$gradle_file"
grep -A5 'release {' "$gradle_file" | grep -q 'applicationIdSuffix = null'

if KEYSTORE_BASE64=x KEYSTORE_PASSWORD=x KEY_ALIAS=x KEY_PASSWORD=x \
  "$credentials_script" >/dev/null 2>&1; then
  echo 'FAIL: missing EXPECTED_SIGNER_SHA256 did not fail closed' >&2
  exit 1
fi
KEYSTORE_BASE64=x KEYSTORE_PASSWORD=x KEY_ALIAS=x KEY_PASSWORD=x \
  EXPECTED_SIGNER_SHA256=x "$credentials_script" >/dev/null

cat > "$test_dir/gh" <<'EOF'
#!/usr/bin/env bash
if [[ " $* " == *' repos/example/TunnelGuard/releases '* ]]; then
  printf 'v1.2.3\thttps://api.example/assets/previous\n'
elif [[ " $* " == *' https://api.example/assets/previous '* ]]; then
  printf 'previous apk'
else
  echo "unexpected gh invocation: $*" >&2
  exit 2
fi
EOF

cat > "$test_dir/aapt" <<'EOF'
#!/usr/bin/env bash
apk=${3:?}
if [[ "$apk" == */new.apk ]]; then
  code=${NEW_CODE:-1020005}
  package=${NEW_PACKAGE:-com.tunnelguard.app}
else
  code=${PREVIOUS_CODE:-1020004}
  package=${PREVIOUS_PACKAGE:-com.tunnelguard.app}
fi
# Keep similarly named attributes after the values under test. Real Android 15
# build tools emit these and previously caused `name` to be parsed as `15`.
printf "package: name='%s' versionCode='%s' versionName='test' compileSdkVersion='35' compileSdkVersionCodename='15'\n" "$package" "$code"
EOF

cat > "$test_dir/apksigner" <<'EOF'
#!/usr/bin/env bash
apk=${*: -1}
if [[ "$apk" == */new.apk ]]; then
  signer=${NEW_SIGNER:-aabb}
else
  signer=${PREVIOUS_SIGNER:-AABB}
fi
echo "Signer #1 certificate SHA-256 digest: $signer"
EOF
chmod +x "$test_dir/gh" "$test_dir/aapt" "$test_dir/apksigner"
printf 'new apk' > "$test_dir/new.apk"

run_verify() {
  PATH="$test_dir:$PATH" AAPT_COMMAND="$test_dir/aapt" \
    APKSIGNER_COMMAND="$test_dir/apksigner" "$verify_script" \
    example/TunnelGuard "$test_dir/new.apk" "${NEW_CODE:-1020005}"
}

run_verify | grep -q 'Stable update compatibility verified'

set +e
output=$(NEW_SIGNER=ccdd run_verify 2>&1)
status=$?
set -e
[[ $status -eq 1 ]]
[[ "$output" == *'cannot update the previous stable release because the signing certificate changed'* ]]

set +e
output=$(NEW_CODE=1020004 run_verify 2>&1)
status=$?
set -e
[[ $status -eq 1 ]]
[[ "$output" == *'must be greater than previous stable versionCode'* ]]

set +e
output=$(NEW_PACKAGE=com.tunnelguard.app.alpha run_verify 2>&1)
status=$?
set -e
[[ $status -eq 1 ]]
[[ "$output" == *'must use package ID com.tunnelguard.app'* ]]

echo 'All stable update compatibility tests passed.'
