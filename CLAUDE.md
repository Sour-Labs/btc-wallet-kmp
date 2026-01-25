# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bitcoin Wallet KMP Library - a Kotlin Multiplatform library for managing Bitcoin wallets. Uses the ACINQ bitcoin-kmp library as the core Bitcoin implementation.

## Build Commands

```bash
# Build all targets
./gradlew build

# Run all tests
./gradlew allTests

# Run tests for specific platforms
./gradlew jvmTest
./gradlew iosSimulatorArm64Test
./gradlew linuxX64Test

# Run a single test class (JVM example)
./gradlew jvmTest --tests "io.github.kotlin.fibonacci.FibiTest"

# Check/compile without running tests
./gradlew assemble

# Publish to Maven Local (for local testing)
./gradlew publishToMavenLocal
```

## Architecture

**Target Platforms:** JVM, Android (minSdk 24), iOS (x64, arm64, simulator), Linux (x64, arm64)

**Source Set Structure:**
- `commonMain` - Shared code across all platforms, uses `expect` declarations
- `commonTest` - Shared tests
- Platform-specific source sets (`androidMain`, `iosMain`, `jvmMain`, `linuxX64Main`) provide `actual` implementations

**Dependencies:**
- `fr.acinq.bitcoin:bitcoin-kmp` - Core Bitcoin functionality

**Publishing:** Configured for Maven Central via vanniktech-maven-publish plugin. Group: `io.sourlabs.btc`, artifact: `library`.
