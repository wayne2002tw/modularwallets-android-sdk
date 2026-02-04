# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Language Convention

All output that goes into the repository — code, comments, documentation, commit messages — must be in **English**.
Conversation with the user may be in Chinese. But anything written to a file must be English.

---

## Commit Message Convention

All commit messages must follow **Conventional Commits** format. This project uses `release-please` for automated releases.

```
<type>(<scope>): <short description>

<optional body>

<optional footer>
```

Common types:

| Type | Meaning | release-please trigger |
|------|---------|----------------------|
| `feat` | New feature | minor version bump |
| `fix` | Bug fix | patch version bump |
| `docs` | Documentation only | none |
| `chore` | Maintenance | none |
| `refactor` | Code refactoring | none |
| `ci` | CI/CD changes | none |
| `build` | Build system changes | none |
| `feat!` | Breaking change | major version bump |

Use `BREAKING CHANGE:` in the footer for breaking changes when the type itself is not `feat!`.

Commit messages must describe **what changed** (the diff between before and after), not the internal process or reasoning behind how we got there. If the change is visible in the diff, it can be mentioned. If it isn't, it shouldn't be.

---

## Negative Constraints

Rules added here are derived from mistakes caught during Socratic Review. Each rule is approved by the user before being added. Do not violate any of the following.

*(Currently empty — rules will be added as they are discovered and approved.)*

---

## Build & Dev Commands

```bash
# Build
./gradlew :lib:assembleDebug
./gradlew :lib:assembleRelease

# Lint
./gradlew :lib:lint

# Unit tests
./gradlew :lib:test

# Single test class
./gradlew :lib:test --tests "com.circle.modularwallets.core.PackageName.ClassName"

# Single test method
./gradlew :lib:test --tests "com.circle.modularwallets.core.PackageName.ClassName.methodName"

# Instrumented tests (requires emulator/device)
./gradlew :lib:connectedAndroidTest
```

---

## Project Architecture

This is a **single-module Android library** (`lib`) implementing an **ERC-4337 Account Abstraction SDK** for Circle's Modular Wallets. The package root is `com.circle.modularwallets.core`.

### Layered Structure

```
Clients          ← Entry points for consumers (BundlerClient, PaymasterClient)
  │
Accounts         ← Account types & signing logic (SmartAccount, WebAuthn, Local)
  │
APIs             ← Interface/Impl pairs for each backend service
  │
Transport        ← HTTP + JSON-RPC via Retrofit
  │
Chains           ← Network definitions (17 chains, mainnet + testnet)
```

### Clients Layer (`clients/`)
`BundlerClient` and `PaymasterClient` are the main consumer-facing classes. They orchestrate across Accounts and APIs. `BundlerClient` owns the majority of the SDK surface: sending user operations, gas estimation, recovery flows, address mapping, and public RPC calls.

### Accounts Layer (`accounts/`)
Three account types, all built on a generic `Account<T>` abstract base:

- **`CircleSmartAccount`** — ERC-4337 smart account. The heaviest class. Delegates implementation details to `CircleSmartAccountDelegate` and its subclasses (`WebAuthnCircleSmartAccountDelegate`, `LocalCircleSmartAccountDelegate`). Contains a `NonceManager` for transaction nonce tracking.
- **`WebAuthnAccount`** — Passkey-based signing. Wraps `WebAuthnCredential` and uses Android Credential Manager APIs.
- **`LocalAccount`** — Private-key-based signing. Delegates to `Web3jLocalAccount`.

All accounts are created via **top-level factory functions** (e.g., `toCircleSmartAccount()`, `privateKeyToAccount()`), not constructors.

### APIs Layer (`apis/`)
Six service modules, each following the same **Interface + Impl** pattern:

| Module | Responsibility |
|--------|---------------|
| `bundler/` | User operation lifecycle (send, estimate, receipt) |
| `paymaster/` | Paymaster data & stub data |
| `public/` | Standard eth_* RPC calls |
| `modular/` | Wallet registration & address mapping |
| `rp/` | WebAuthn relying party credential options |
| `util/` | Nonce, replay-safe hashing, gas estimation helpers |

Implementations are **singletons** (object classes). They receive a `Transport` instance and make JSON-RPC calls through it.

### Transport Layer (`transports/`)
`HttpTransport` is the only implementation. It wraps Retrofit + OkHttp and handles JSON-RPC request/response serialization (`JsonRpcReqResp.kt`). All API Impls talk to the network exclusively through this layer.

### Chains Layer (`chains/`)
`Chain.kt` defines the data class. Each chain file (e.g., `Mainnet.kt`, `BaseSepolia.kt`) is a standalone object that supplies `chainId`, RPC/bundler/paymaster endpoints, and relevant contract addresses.

### Key Design Patterns
- **Delegate Pattern:** `CircleSmartAccount` → `CircleSmartAccountDelegate` subclasses. Keeps the public account class thin.
- **Factory Functions:** Account creation is done via extension/top-level functions, not constructors. This allows `suspend` calls during initialization.
- **Interface-based APIs:** Every API module has a clean interface. Impls are hardcoded object singletons — currently unmockable. Target for DI refactoring.

### Utils (`utils/`)
Organized into sub-packages by domain: `signature/`, `encoding/`, `abi/`, `webauthn/`, `userOperation/`, `smartAccount/`, `unit/`, `rpc/`, `data/`, `error/`. These are **stateless utility functions**, not classes with lifecycle.

### Dependencies Worth Noting
- **web3j** — Ethereum primitives and local account signing
- **Retrofit + OkHttp** — HTTP transport
- **Moshi + Gson** — JSON serialization (both present; Moshi is primary for model classes)
- **androidx.credentials** — Android WebAuthn / Passkey APIs

### What's Missing
- No unit test files exist currently (`lib/src/test/` is empty)
- No instrumented test files exist currently
- Test frameworks (JUnit, Mockito, Coroutines Test) are configured in `build.gradle.kts` but unused

---

## Legacy to Modern Platform — Refactoring Rules

The following defines the refactoring goals and rules for this project. All technical decisions operate under this framework.

### Current State (verified via audit)

The SDK already uses Kotlin Coroutines (`suspend functions`) as its primary async mechanism — 169 suspend functions across the codebase. There is no Java code, no RxJava, and no Callback pattern. The modernization targets are specific, identified issues below.

### Refactoring Targets (by priority)

**Priority 1 — Error Handling (Safety Risk)**
- Exceptions swallowed via `printStackTrace()` and empty catch blocks — failures are invisible to callers
- Affected files: `StructuredDataEncoder.kt`, `BundlerApiImpl.kt`, `RetrofitProviderUtils.kt`
- Target: Proper error propagation using the existing `BaseError` hierarchy

**Priority 2 — Architecture**
- `BundlerClient` is a God Class (671 lines, 70+ methods) mixing UO operations, balance queries, recovery, and address mapping
- Business logic and API calls are mixed within the same methods — makes isolated testing impossible
- Target: Split into focused, single-responsibility clients; separate business logic from API calls

**Priority 3 — Flow Adoption**
- Polling loops (e.g., `waitForUserOperationReceipt`) are hand-written with manual retry — convert to `Flow` with retry/timeout operators
- `NonceManager` state changes have no notification mechanism — introduce `StateFlow`

**Priority 4 — Dependency Injection**
- All API Impls are `object` singletons hardcoded into clients (`= BundlerApiImpl`) — unmockable, untestable
- `NonceManager` has a broken implementation: empty `set()`, `get()` returns `System.currentTimeMillis()`
- Target: Constructor injection for all dependencies

**Priority 5 — Code Quality**
- Magic numbers: `pollingInterval = 4000`, `retryCount = 6` — extract to named constants
- `Context` passed through call chains without lifecycle safety checks
- Logging inconsistency: most files use `printStackTrace()`, only 3 files use Logger — unify

### Role: Architecture Mentor
All responses and refactoring suggestions should come from a "mentor" perspective — not just code, but the reasoning behind it.

### Three-Step Refactoring Process (must follow in order)

**Step 1 - The Audit**
Before touching any code, diagnose the target code for:
- **Error Handling:** Are errors swallowed? Can callers detect failures?
- **Responsibility:** Does this class/method do too much? Can it be tested in isolation?
- **Async Pattern:** Is the current pattern appropriate? (e.g., hand-written polling loop vs Flow)

Explain how the modernized approach solves the specific problem found.

Before moving to Step 2, perform an **Impact Analysis**: list all classes and files that depend on the target, and identify which callers will be affected by the change. This defines the scope of the refactor and prevents unintended breakage.

**Step 2 - The Refactor**

Before starting, make a **Dual-Path decision**: Can you directly write a failing unit test for this target?
- **Yes** → Path A (target is isolated, e.g., stateless utility functions)
- **No** → Path B (target is tightly coupled or too complex, e.g., God Class, hardcoded singletons)

**🛣️ Path A — Directly Testable (Standard TDD)**

1. Write a test asserting the **correct** expected behavior → 🔴 Red
2. Fix the code to match → 🟢 Green
3. Refactor structure if needed → 🟢 Green (must stay green)

**🛣️ Path B — Not Directly Testable (Preparatory Refactoring)**

1. Write a **Golden Master test** (Characterization Test) covering the current behavior and verify it passes. Assert the actual current output — do not assert what the correct behavior should be. If the current output is a bug, freeze it as-is. This is the safety net.

2. **Two Hats Rule:** Never refactor structure and fix bugs in the same commit. These are separate intents and must be separate commits.

   - **Refactoring Hat** (structure only — Golden Master must stay green ✅):
     - Change structure to match the current Refactoring Target. Do not alter logic.

   - **Fixing Hat** (logic change — follows TDD: Red → Green 🔴→🟢):
     Triggered when the Golden Master's current assert no longer matches the correct business logic — i.e., the frozen behavior is actually a bug. At this point, switch to Fixing Hat:
     1. Update the Golden Master assert to the **correct** expected behavior → test goes 🔴 Red (this is normal)
     2. Fix the code to match the correct behavior
     3. Run test → 🟢 Green (fix is verified)
     - Swallowed errors → proper propagation using the `BaseError` hierarchy

**Step 3 - The Mindset Shift**
Don't just give code — explain the shift in thinking behind the refactoring.
Example: From "fire and forget with printStackTrace()" → "structured error propagation where every failure is observable by the caller"

**Pre-commit gate: Build + Tests**
Before proceeding to Socratic Review, run and confirm both pass:
- `./gradlew :lib:assembleDebug`
- `./gradlew :lib:test`

If either fails, resolve the issue before continuing.

**Before Commit - The Socratic Review**
After Step 3 and before committing, produce a self-review in the following format. Do not commit until the user has reviewed and approved.

- For each design choice made: explain why this option was chosen over the alternative
- Verify whether the change alters any existing behavior — if yes, explain exactly how
- Identify any callers or downstream code that may be affected
- **Metrics (before → after):** Report LOC and public method count for any modified or newly created files. This provides quantitative evidence that the refactor achieved its goal.
- **Recursive Learning:** If a mistake is found during this review, propose a new rule for the Negative Constraints section. Do not add it to CLAUDE.md until the user has reviewed and explicitly approved.
- **CLAUDE.md Sync:** Review whether any sections in CLAUDE.md that describe the current project state (e.g., Project Architecture, What's Missing) are now outdated due to this change. If yes, propose the updates — do not apply until the user has reviewed and approved.

Example:
```
Q: Why StateFlow instead of SharedFlow?
A: StateFlow is appropriate here because...

Q: Does this change alter existing error handling behavior?
A: Yes. Previously the exception was swallowed via printStackTrace(). Now it propagates as EncodingError to the caller...

Q: Any callers that might break?
A: BundlerClient.kt line 298 calls this method. It does not currently handle EncodingError...

Metrics (before → after):
- TargetClass.kt: 450 LOC, 12 public methods → 180 LOC, 5 public methods
- NewExtractedClass.kt: new, 270 LOC, 7 public methods
```

### Global Decision Principles
- No over-engineering unrelated to the identified targets above
- Backward compatibility (public API surface must not break) is non-negotiable
- Each refactoring step must be independently testable and understandable
- **Atomic commits:** Keep each commit to one logical change. Only combine changes in the same commit if they are tightly coupled and cannot be meaningfully separated. This keeps review clearer and revert boundaries clean.
- After Socratic Review is approved, commit automatically. **Never push without explicit human instruction.**
