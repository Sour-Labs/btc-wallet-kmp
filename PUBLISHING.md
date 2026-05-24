# Publishing

This library publishes to [Maven Central](https://central.sonatype.com/) via the
[vanniktech-maven-publish](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin, using the Sonatype Central Portal.

The publish pipeline is wired in [`library/build.gradle.kts`](library/build.gradle.kts)
and runs from the [`Release`](.github/workflows/release.yml) workflow when a
`v*` tag is pushed. Most of this document describes the one-time setup the
maintainer needs to do before the first release.

## One-Time Setup

### 1. Claim the namespace

1. Sign in to https://central.sonatype.com/ with the maintainer account.
2. Go to **Namespaces** → **Add Namespace** and register `io.sourlabs.btc`.
3. Verify ownership by adding the DNS TXT record Sonatype shows you to the
   `sourlabs.io` zone. Verification usually completes within minutes once the
   record propagates.

If you'd rather avoid DNS verification, register `io.github.sour-labs` instead
(auto-verified from the GitHub org). The library coordinates would then become
`io.github.sour-labs:library`, which means updating `group` in
`library/build.gradle.kts` and the README install snippet.

### 2. Generate a GPG signing key

Maven Central requires every artifact to be GPG-signed.

```bash
# Generate a new key (RSA, 4096 bits, no expiry — or set one if you prefer)
gpg --full-generate-key

# Note the long key ID
gpg --list-secret-keys --keyid-format LONG

# Publish the public key to the keyservers Maven Central checks
KEY_ID=ABCDEF0123456789                       # the long key ID from above
gpg --keyserver keyserver.ubuntu.com --send-keys "$KEY_ID"
gpg --keyserver keys.openpgp.org    --send-keys "$KEY_ID"

# Export the secret key in ASCII-armored form — this is what the
# vanniktech plugin reads from ORG_GRADLE_PROJECT_signingInMemoryKey.
gpg --armor --export-secret-keys "$KEY_ID" > signing-key.asc
```

Keep `signing-key.asc` and the key's passphrase somewhere safe (a password
manager). They are the credentials that prove a release came from this project.

### 3. Generate a Central Portal user token

1. In https://central.sonatype.com/, click your account → **Generate User Token**.
2. Save the generated `username` and `password` — these are the
   `mavenCentralUsername` / `mavenCentralPassword` values the plugin expects.
   They are *not* your portal login.

### 4. Add GitHub Actions secrets

In the repo settings → **Secrets and variables** → **Actions**, add:

| Secret | Value |
|--------|-------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `SIGNING_KEY` | Full contents of `signing-key.asc` (including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` lines). `\n`-escaped content also works. |
| `SIGNING_KEY_ID` | The last 8 hex chars of the key ID (or the full long ID) |
| `SIGNING_KEY_PASSWORD` | The passphrase you set when generating the key |

That's everything the release workflow needs.

## Cutting a Release

1. **Bump the version** in `library/build.gradle.kts`:
   ```kotlin
   version = "0.5.0"
   ```
2. **Update the README** install snippets to the new version.
3. **Promote the `[Unreleased]` section** in [`CHANGELOG.md`](CHANGELOG.md) to
   `[0.5.0] - YYYY-MM-DD`, add a fresh empty `[Unreleased]` block above it,
   and update the compare links at the bottom of the file.
4. **Commit and merge** through a normal PR. Wait for CI to be green on `main`.
5. **Tag the merge commit** and push the tag:
   ```bash
   git checkout main && git pull
   git tag v0.5.0
   git push origin v0.5.0
   ```
6. The `Release` workflow runs automatically. It checks that the tag matches
   the version in `build.gradle.kts`, then runs
   `./gradlew publishAndReleaseToMavenCentral`, which uploads the artifacts,
   closes the staging repository, and releases it.
7. New versions usually appear on https://central.sonatype.com/ within a few
   minutes, and on `repo.maven.apache.org` (the public mirror most builds
   resolve from) within ~30 minutes.

### Versioning

The library follows semantic versioning. Until the API stabilizes (1.0.0),
treat any `0.x → 0.y` bump as potentially breaking and call it out in the
release notes.

## Manual / Local Publish

You don't need any of the Maven Central credentials to test packaging — the
signing block in `library/build.gradle.kts` is gated on the env vars being
present.

```bash
# Publishes to ~/.m2/repository, unsigned. Useful for trying the artifact
# in a downstream project before cutting a real release.
./gradlew publishToMavenLocal
```

To do a real Central publish from your machine (e.g., if Actions is down),
export the same env vars the workflow uses and run the same task:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername=...
export ORG_GRADLE_PROJECT_mavenCentralPassword=...
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat signing-key.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId=ABCDEF01
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...

./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

A macOS host is required for a full release — the iOS klibs in the
publication can only be built on macOS.

## Troubleshooting

- **"401 Unauthorized" during upload.** The `MAVEN_CENTRAL_*` secrets are the
  generated user token, not your Sonatype login. Regenerate the token in the
  Central Portal and update the GitHub secret.
- **"PGP signature verification failed".** The public key hasn't propagated
  to the keyserver Central checks yet. Re-send to `keyserver.ubuntu.com` and
  `keys.openpgp.org`, wait ~10 minutes, and re-run the workflow.
- **"Could not read PGP secret key".** The `SIGNING_KEY` secret is malformed.
  Re-export the ASCII-armored private key (`gpg --armor --export-secret-keys
  ...`) and store either the raw multi-line key or a `\n`-escaped equivalent.
- **"Namespace not allowed".** The Sonatype namespace claim either hasn't
  been verified or doesn't match the `group` in `library/build.gradle.kts`.
- **iOS targets missing from the publication.** The release job ran on a
  Linux runner. The workflow pins `macos-latest` for this reason — don't
  change it without also dropping the iOS targets.
