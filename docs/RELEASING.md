# Releasing TunnelGuard

## Stable Android signing identity

Android permits an APK to update an installed application only when both APKs use
the same application ID and signing certificate. The release workflow therefore
requires these GitHub Actions secrets:

- `KEYSTORE_BASE64`: the permanent release keystore, encoded with `base64`;
- `KEYSTORE_PASSWORD`: the keystore password;
- `KEY_ALIAS`: the permanent release-key alias; and
- `KEY_PASSWORD`: the release-key password.

Back up the keystore securely. Do not replace it or generate a key during a release.
Changing the key makes an in-place update impossible and Android may display a
package-conflict error. The workflow intentionally fails when any signing secret is
missing so it can never publish an APK with a one-off certificate.

If an APK signed with an older, temporary certificate is already installed, Android
cannot migrate it to the permanent certificate. That installation must be removed
once before installing a permanently signed release. Subsequent releases signed by
the same permanent key will update normally.
