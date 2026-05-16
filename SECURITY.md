# Security Policy

This is a Bitcoin wallet library. Bugs in this code can cause loss of funds. We take
security reports seriously and appreciate the time researchers spend investigating.

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Use one of these private channels instead:

1. **Preferred: GitHub Private Vulnerability Reporting.** Open
   <https://github.com/Sour-Labs/btc-wallet-kmp/security/advisories/new> and submit a
   report. This keeps the disclosure encrypted and gives us a private space to
   coordinate a fix with you.
2. **Email:** `security@sourlabs.io`. Please mention "btc-wallet-kmp security" in
   the subject. Email is not end-to-end encrypted, so prefer the GitHub channel for
   sensitive details.

We aim to acknowledge new reports within **3 business days** and to provide an
initial assessment within **7 business days**.

## What to Include

A good report usually contains:

- A description of the issue and the impact you believe it has (e.g., key leak,
  fund-loss path, signing bypass, address-validation bypass).
- The affected version(s) and platforms.
- A minimal reproduction — code snippet, test, or step-by-step instructions.
- Any mitigations or workarounds you've identified.

## Scope

In scope:

- Cryptographic correctness (key derivation, signing, address generation,
  script construction).
- UTXO selection or fee logic that could lead to fund loss or unintended spends.
- Memory or storage handling of secret material (mnemonics, seeds, private keys).
- Network-facing parsers (block explorer responses, transaction decoding) that
  can be exploited via a malicious server response.
- Dependency vulnerabilities that are reachable through this library's public API.

Out of scope:

- Issues in upstream dependencies that are not reachable through this library —
  please report those upstream (e.g., to `bitcoin-kmp`, `secp256k1-kmp`, Ktor).
- Misuse of the API by an application (e.g., logging the mnemonic).
- Theoretical issues without a demonstrable impact on confidentiality, integrity,
  or availability of wallet funds.

## Supported Versions

This library is pre-1.0 and under active development. Security fixes are only
backported to the **latest released minor version**. Users on older versions
should upgrade.

## Disclosure

We follow coordinated disclosure: once a fix is available and users have had a
reasonable window to upgrade, we will publish a GitHub Security Advisory
crediting the reporter (unless they prefer to remain anonymous).
