# Code quality bar

The bar every change to YourTube clears before it is committed. Adapted from Cursor's
"thermo-nuclear code quality review" skill to this Kotlin + Compose + WebView codebase.

A change is done when every rule below holds for its diff, checked rule by rule, not when it works.

## Rules

1. **Code judo first.** Before polishing a design, look for the restructuring that makes whole
   branches, flags, modes, or layers disappear while behavior stays the same. Prefer the version that
   reads as inevitable in hindsight. Moving complexity around is not simplifying it; deleting it is.
2. **Files stay at or under 650 lines.** When a change would push a file past that, decompose first:
   extract a composable, a pure function, a module. Compose screens split into one file per screen
   plus its private components.
3. **Special cases live behind a model.** A new behavior gets its own owner (a state machine, a
   policy object, a sealed type with an explicit `when`) instead of extra `if`s inside an unrelated
   flow. The D4 playback rules are one state machine, not checks scattered across `onPause`,
   `onUserLeaveHint`, and the service.
4. **Direct, boring code.** Plain functions and data classes over generic mechanisms. Every
   abstraction earns its keep by making callers simpler; a wrapper that only forwards calls gets
   inlined.
5. **Explicit types at boundaries.** Model data with sealed classes and non-null fields that state
   the real invariant. The JS bridge decodes into typed models once, at the edge. A silent fallback
   (`getOrDefault`, `?: ""`) is allowed only where the fallback is the intended behavior and a
   comment says why; otherwise the invariant is made explicit.
6. **Canonical layer.** Each concept has one home: identity derivation in `PageIdentity`,
   persistence in `Store`, the WebView and everything bound to it in `WebViewHost`, playback control
   in `PlaybackHub` and the media3 player adapter. UI reads state and sends intents; it holds no
   business logic. Reuse the existing helper or the platform utility (`AtomicFile`, `update {}`)
   rather than a near-duplicate.
7. **Atomic, parallel where independent.** Related state changes apply in one update so no reader
   sees half of them. Independent work (feed fetches, avatar backfill) runs concurrently with an
   explicit bound.
8. **Tests follow the logic.** Pure logic is written so a JVM test can reach it, and it ships with
   that test.

## Review

Each implementation stage ends with a review against these rules by a reviewer who did not write the
code. Findings are fixed before the stage's commit. Prefer a few high-conviction structural findings
over a long list of cosmetic ones.
