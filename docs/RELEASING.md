# Releasing TunnelGuard

## Stable Android signing identity

Android permits an APK to update an installed application only when both APKs use
the same application ID and signing certificate. The release workflow therefore
requires these GitHub Actions secrets:

- `KEYSTORE_BASE64`: the permanent release keystore, encoded with `base64`;
- `KEYSTORE_PASSWORD`: the keystore password;
- `KEY_ALIAS`: the permanent release-key alias; and
- `KEY_PASSWORD`: the release-key password; and
- `EXPECTED_SIGNER_SHA256`: the protected SHA-256 certificate digest printed by
  `apksigner verify --print-certs` for the permanent release key.

Back up the keystore securely. Do not replace it or generate a key during a release.
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
