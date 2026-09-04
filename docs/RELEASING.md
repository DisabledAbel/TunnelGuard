# Releasing TunnelGuard

## Stable Android signing identity

Android permits an APK to update an installed application only when both APKs use
the same application ID and signing certificate. The release workflow therefore
requires these GitHub Actions secrets:

The stable package ID `com.tunnelguard.app` and the permanent signing certificate
are TunnelGuard's permanent Android identity. Alpha builds use their separate
`com.tunnelguard.app.alpha` identity and cannot be published as stable builds.

- `KEYSTORE_BASE64`: the permanent release keystore, encoded with `base64`;
- `KEYSTORE_PASSWORD`: the keystore password;
- `KEY_ALIAS`: the permanent release-key alias; and
- `KEY_PASSWORD`: the release-key password; and
- `EXPECTED_SIGNER_SHA256`: the protected SHA-256 certificate digest printed by
  `apksigner verify --print-certs` for the permanent release key.

Back up the keystore securely. Losing or replacing it permanently breaks update
compatibility. Do not replace it or generate a key during a release.
Changing the key makes an in-place update impossible and Android may display a
package-conflict error. The workflow intentionally fails when any signing secret is
missing so it can never publish an APK with a one-off certificate.

This workflow invokes `apksigner` without a signing lineage. Therefore, if an APK
signed with a different temporary certificate is already installed, that installation
must be removed before installing a permanently signed release. Android 9 (API 28)
and newer may permit migration when the old signing key is available and supported
signer rotation is configured with a certificate lineage, but this workflow does not
currently use that migration mechanism. Subsequent releases signed by the same
permanent key will update normally.

In practical terms, users of old versions signed with a temporary key may need to
uninstall TunnelGuard once and then install the first permanently signed stable
version. No safe in-place migration is possible without that old signing key.
After that one-time reinstall, later stable releases should update normally.

## Configure GitHub Actions signing secrets

The five values above must be available to the `build-release` job as repository
Actions secrets or through an explicitly assigned GitHub Environment. GitHub
deliberately does not expose secret values after they are saved, but a repository
administrator can confirm the secret **names** on an iPad:

1. Sign in to GitHub in Safari and open the TunnelGuard repository.
2. Tap **Settings** (use the repository's tab bar, not account settings).
3. In the Settings sidebar, tap **Secrets and variables**, then **Actions**.
4. Check **Repository secrets** for all five names listed above.
5. Also inspect **Environments** in the repository Settings sidebar. Open each
   existing environment and inspect its **Environment secrets** names. If the five
   names exist only there, the release job must be assigned to that exact existing
   environment with `environment: <existing-name>`; do not create or guess a name.

Keep all five secrets together as one matching set in exactly one intended scope.
If repository secrets are used, remove conflicting secrets with the same names from
environments that could be assigned to this job. If an existing environment is
used, update all five values together in that environment, assign its exact name to
`build-release`, and remove the five duplicate repository secrets and conflicting
copies from other environments. Never combine a keystore from one scope with an
alias, password, or expected digest from another scope.

If the names are absent from both locations, add them as repository secrets from
**Settings → Secrets and variables → Actions → New repository secret**. Add each
name separately, preserving the exact spelling. Use only the original permanent
release keystore and its real passwords and alias. `KEYSTORE_BASE64` is the base64
encoding of the keystore file, not a file path. Never upload the keystore to the
repository or paste any of these values into an issue, pull request, workflow log,
or artifact.

Before saving `EXPECTED_SIGNER_SHA256`, validate it on a trusted computer that has
the permanent keystore and Android SDK. The following commands avoid printing the
keystore or passwords; `keytool` prompts securely for the keystore password:

```bash
keytool -list -v -keystore /secure/path/to/permanent-release.keystore \
  -alias 'THE_PERMANENT_ALIAS'
base64 < /secure/path/to/permanent-release.keystore > keystore.base64.txt
```

Copy the SHA-256 fingerprint specifically from `Certificate[1]`, the leaf signing
certificate shown by `keytool -list -v`, into `EXPECTED_SIGNER_SHA256`. Do not use
the fingerprint of an intermediate or root certificate. Confirm that this value is
the same digest reported as `Signer #1 certificate SHA-256 digest` by
`apksigner verify --verbose --print-certs` for an APK signed with this keystore and
alias. Colons, spaces, and letter case are accepted because the workflow removes
whitespace and colons and converts both the expected and actual digests to lowercase
before comparing them. Securely delete `keystore.base64.txt` after its contents have
been saved as `KEYSTORE_BASE64`.

## Release checklist

The release workflow only builds and publishes the official, stable application.
Every run executes the release unit tests, assembles the `release` build type,
verifies that the APK has the production package ID and app label, signs it with the
permanent release key, verifies the pinned signer, and compares its package ID,
certificate, and increasing `versionCode` with the latest previous stable Release
before creating the GitHub Release. It does
not offer an unsigned or alpha mode. Use the separate **Build Alpha APK** workflow
for alpha builds.

Stable releases can also be started by pushing a stable semantic-version tag such
as `v1.1.7`. A matching tag automatically selects `1.1.7` as the Android version,
uses the release build type, and publishes the signed GitHub Release. Pre-release
suffixes such as `-alpha` are deliberately rejected by this workflow; alpha builds
remain isolated in the separate **Build Alpha APK** workflow.

Before enabling tag-triggered releases, create an active repository tag ruleset in
**Settings → Rules → Rulesets** targeting tags that match `v*.*.*`. Enable both
creation and update restrictions, and grant the bypass permission only to the
people or release team authorized to publish stable versions. The repository
owner, `DisabledAbel`, is authorized by default. To authorize a different user or
multiple publishers, define the Actions repository variable
`TRUSTED_RELEASE_ACTORS` as a comma-separated list of all authorized GitHub
usernames (including `DisabledAbel` if the owner should remain authorized). When
the variable is present it replaces the default allowlist. The workflow fails
closed when the initiating actor is not listed, while the ruleset prevents
unauthorized users from creating or moving a matching release tag.

Stable versions support major values from 0 through 209, minor values from 0
through 999, and patch values from 0 through 9999; `0.0.0` is not valid. These
bounds provide a collision-free Android `versionCode` and keep it below Google
Play's `2,100,000,000` limit.

Before retrying a version, check the repository's **Releases** page and **Tags**
page. Do not dispatch `1.1.7` if either `v1.1.7` already exists as a release or a
tag. Otherwise, open **Actions → TunnelGuard Release → Run workflow**, enter
`1.1.7`, and run it from the intended branch. Because every run publishes an
official release, do not dispatch the workflow merely to validate a build.

The run is successful only if the signing step reports that signing and
verification completed, and the final release-creation step succeeds. In the
signing log, confirm that the preflight found no missing secrets, `keytool`
validated the permanent alias, `zipalign` completed, `apksigner verify --verbose
--print-certs` succeeded, and the signer matched the protected digest. The GitHub
Release must contain only the signed APK, its SHA-256 checksum, and `metadata.txt`;
it must not contain `release.keystore`, the aligned intermediate APK, passwords,
aliases, certificates, or other signing material.
