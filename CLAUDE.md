# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bitcoin Wallet KMP Library - a Kotlin Multiplatform library for managing Bitcoin wallets. Uses the 
ACINQ bitcoin-kmp library as the core Bitcoin implementation.

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

**Technologies and patterns:**
- Kotlin Multiplatform
- Flows and coroutines
- Ktor for networking

**Dependencies:**
- `fr.acinq.bitcoin:bitcoin-kmp` - Core Bitcoin functionality

**Publishing:** Configured for Maven Central via vanniktech-maven-publish plugin. Group: `io.sourlabs.btc`, artifact: `library`.

## General instructions

Never speculate about code you have not opened. If the user references a specific file, you MUST
read the file before answering. Make sure to investigate and read relevant files BEFORE answering
questions about the codebase. Never make any claims about code before investigating unless you are 
certain of the correct answer - give grounded and hallucination-free answers.

Please write a high-quality, general-purpose solution using the standard tools available. Do not 
create helper scripts or workarounds to accomplish the task more efficiently. Implement a solution 
that works correctly for all valid inputs, not just the test cases. Do not hard-code values or 
create solutions that only work for specific test inputs. Instead, implement the actual logic that 
solves the problem generally.

Focus on understanding the problem requirements and implementing the correct algorithm. Tests are 
there to verify correctness, not to define the solution. Provide a principled implementation that 
follows best practices and software design principles.

If the task is unreasonable or infeasible, or if any of the tests are incorrect, please inform me 
rather than working around them. The solution should be robust, maintainable, and extendable.
## Local Testing (Agent Workflow)
When verifying features as part of a pipeline:
1. This is a library — no UI to test in a browser/emulator
2. Run full test suite: `./gradlew allTests`
3. For platform-specific: `./gradlew jvmTest` (fastest), `./gradlew iosSimulatorArm64Test`
4. Verify it publishes cleanly: `./gradlew publishToMavenLocal`
5. If changes affect Bad Wallet Client, test integration by updating the dependency there and building
