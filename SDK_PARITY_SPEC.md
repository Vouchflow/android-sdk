# Vouchflow SDK Parity Spec

The Android (`android-sdk`) and iOS (`ios-sdk`) SDKs are two implementations of **one
product contract**. They are mirrored by hand across separate repositories. This document is
the source of truth for that contract: it defines what MUST stay identical, what is allowed
to differ, and the process that keeps the two from drifting.

> Status: v1 — created 2026-05-18 alongside android-sdk/ios-sdk v2.1.1.
> Canonical location: `android-sdk` repo root. `ios-sdk` carries a pointer to it.

---

## 1. Why this exists

iOS fell a release behind Android (2.1.0 vs 2.1.1) because a hardening change shipped to
Android with no mechanism to surface that iOS needed the counterpart. Parity was "a developer
remembers." This spec plus the process in §6 replaces memory with a checklist and automation.

## 2. Versioning & release parity

- **Shared version line.** A given `X.Y.Z` means the same behavior contract on both
  platforms. The SDKs release under matching tags (`vX.Y.Z`).
- A behavior or public-API change ships to **both** SDKs before — or in lockstep with — the
  version that introduces it. If only one platform can ship immediately, the other gets a
  tracking issue (see §6) and the version numbers are allowed to diverge **only** until that
  issue closes.
- Platform-only fixes (a build-system tweak, a CI flake) may bump one platform's patch
  number alone. Anything a developer can observe through the public API may not.

## 3. Public API surface — MUST match

The following must be semantically identical across platforms (naming may follow each
platform's casing conventions — see §5):

- **Entry point:** `Vouchflow.configure(config)`; `Vouchflow.shared`; `cachedDeviceToken`;
  `reset()`.
- **Operations:** `verify`, `requestFallback`, `submitFallbackOtp` / `submitFallbackOTP`,
  `signPayload`.
- **`VouchflowConfig` fields:** `apiKey`, `environment`, `leafCertificatePin`,
  `intermediateCertificatePin`, and the OS-storage opt-out
  (`accountManagerStorage` on Android, `keychainStorage` on iOS — see §5).
- **Enumerations:** `Confidence`, `VerificationContext`, `FallbackReason` — identical cases.
- **Error taxonomy:** every error case exposed on one platform has a counterpart on the
  other (`VouchflowError`).
- **Result types:** `VouchflowResult`, `FallbackResult`, `SignedBundle`, and their fields,
  including signal names.

Any addition to this surface on one platform is a parity-relevant change (§6).

## 4. Storage contract — MUST match (the issue #1 lesson)

Token persistence is an **optional enhancement**. Its failure must never be fatal to the
host app.

- The OS-level store (Android `AccountManager`, iOS Keychain) is fronted by a storage
  interface (`TokenStore` / `KeychainBackend`) so failures are containable and testable.
- `configure()` must not crash or do blocking OS storage I/O on the app's main/launch path.
- When the OS store is unavailable, the SDK silently falls back to an alternate store and
  continues — it never propagates a storage failure as a host-app crash.
- An opt-out flag disables OS-level storage entirely.
- `configure()` may still throw **deterministic developer errors** (invalid API key,
  missing application context). It may never throw a **device-dependent** error.

## 5. Accepted divergences

These differences are intentional and parity-lint / review should NOT flag them:

| Area | Android | iOS | Reason |
|---|---|---|---|
| Biometric | `BiometricPrompt` (needs an `activity` arg on `verify`/`signPayload`) | `LAContext` (no activity arg) | Platform frameworks |
| Key store | Keystore (TEE/StrongBox) | Secure Enclave | Platform-native |
| Attestation | Keystore Attestation (once at enrollment) | App Attest (per-signature for `.high`) | Platform-native |
| OS storage opt-out | `accountManagerStorage` | `keychainStorage` | Named for the platform store |
| Keychain access group | n/a | `keychainAccessGroup` | iOS-only concept |
| Casing | `submitFallbackOtp`, `Confidence.HIGH` | `submitFallbackOTP`, `Confidence.high` | Platform conventions |

Adding a row here is itself a parity-relevant change and must be done in the PR that
introduces the divergence.

## 6. The parity process

1. **Label.** Any PR that changes the items in §3 or §4 gets the `parity:needed` label.
2. **PR checklist.** The pull-request template asks whether the change is parity-relevant
   and, if so, links the sibling-repo issue.
3. **Mirror issue.** When a `parity:needed` PR merges, a GitHub Action opens a tracking
   issue in the sibling repo with the diff link and the relevant §3/§4 reference.
4. **Spec first.** A behavior change updates this document in the same PR. Reviewers reject
   parity-relevant PRs that leave the spec stale.
5. **Close the loop.** The sibling-repo issue stays open until its SDK ships the counterpart;
   only then may the version numbers re-converge.

## 7. Current parity state (2026-05-26)

Both SDKs at **2.1.3**. Known open divergences: none beyond §5. Last reconciliation:
fallback email normalization and `signPayload` session-error propagation shipped to both;
Android also carries the platform-only AGP/Gradle update.
