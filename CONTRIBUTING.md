# Contributing

Thanks for your interest in contributing to `btc-wallet-kmp`. This document covers
how to get set up, how PRs are reviewed, and the conventions we follow.

For security issues, **do not** open a public issue — see [SECURITY.md](SECURITY.md).

## Getting Started

### Prerequisites

- **JDK 21** (Zulu or any Temurin-compatible distribution). Match what CI uses.
- **Gradle** — use the wrapper (`./gradlew`); don't install Gradle separately.
- For iOS targets: macOS with Xcode and the iOS simulator installed.

### Build and Test

```bash
# Build everything
./gradlew build

# Run all tests (slow — runs every platform)
./gradlew allTests

# Faster, platform-specific
./gradlew jvmTest                  # quickest feedback loop
./gradlew testAndroidHostTest
./gradlew iosSimulatorArm64Test    # requires macOS
./gradlew linuxX64Test             # requires Linux or a Linux toolchain

# Verify the artifact publishes cleanly
./gradlew publishToMavenLocal
```

CI runs `jvmTest`, `testAndroidHostTest`, `iosSimulatorArm64Test`, and
`linuxX64Test` on every PR. All four must pass before a PR can merge.

## Pull Request Workflow

1. **Open an issue first** for non-trivial changes (new features, API changes,
   refactors). For small fixes and obvious bugs, jump straight to a PR.
2. **Fork the repo** and create a branch off `main`. Name it descriptively
   (e.g., `fix/utxo-selection-overflow`, `feat/bip85-derivation`).
3. **Make your changes**, keeping them focused. One PR = one concern.
4. **Add or update tests** for the behavior you change. Tests live alongside the
   relevant source set (`commonTest`, `jvmTest`, etc.).
5. **Run `./gradlew allTests`** locally before pushing if you can. Otherwise,
   at minimum run `./gradlew jvmTest`.
6. **Open a PR** against `main`. Fill in the description: what changed, why,
   and how it was tested.
7. CI will run automatically. Fix any failures, then request review.

### Merge Rules

- `main` is protected. All changes go through a PR.
- One approval is required; CI must be green; conversations must be resolved.
- We **squash-merge** every PR. Write your PR title as you want it to appear
  in the commit history — short, imperative, and descriptive
  (e.g., `fix: handle empty UTXO set in privacy strategy`).
- Branches are deleted automatically after merge.

## Code Style

- **Match existing style.** We don't enforce a formatter, but new code should
  look like the code around it.
- Idiomatic Kotlin: prefer immutable data, named arguments for clarity, and
  expression bodies when they read well.
- Public API additions need KDoc — see existing classes in `commonMain` for the
  level of detail expected.
- No new dependencies without discussion. This library aims to stay lean;
  if you need a new dependency, justify it in the PR or a preceding issue.

## Multiplatform Conventions

- New shared code goes in `commonMain`. Use `expect`/`actual` only when a
  platform genuinely differs (e.g., crypto primitives, filesystem access).
- Tests for shared behavior go in `commonTest`. Add platform tests only when
  testing platform-specific `actual` implementations.
- Don't introduce JVM-only types (e.g., `java.*`) into `commonMain`.

## Cryptographic and Bitcoin-Specific Code

Bitcoin code has sharp edges. When touching anything that handles keys, signs
transactions, builds scripts, or constructs addresses:

- Add tests with **known test vectors** (BIP-39, BIP-32, BIP-44/49/84/86)
  where they exist.
- Test against **mainnet, testnet, signet, and regtest** if the change affects
  network-dependent code.
- Be explicit about secret material: never log it, never serialize it
  unintentionally, never store it in places callers don't expect.
- If you're not sure whether a change is safe, say so in the PR. We'd rather
  discuss than merge something subtly wrong.

## Reporting Bugs

Open a [GitHub issue](https://github.com/Sour-Labs/btc-wallet-kmp/issues/new)
with:

- The library version (`io.sourlabs.btc:library:X.Y.Z`).
- The platform you're running on (JVM/Android/iOS/Linux + version).
- A minimal reproduction — code, configuration, and the network if relevant.
- The actual vs. expected behavior, including any stack traces.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License, Version 2.0](LICENSE), the same license as the rest of the
project.
