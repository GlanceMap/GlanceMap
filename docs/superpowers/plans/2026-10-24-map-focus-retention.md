# Map Focus Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep map focus mode active when returning from the menu and after temporary full-screen recording or guidance dashboards close.

**Architecture:** Store the map-focus boolean in the Navigate destination's `NavigateViewModel`, which remains alive while that destination stays on the navigation back stack. Keep the hint and exit-highlight state local to Compose. Dashboards can take visual precedence without clearing the underlying map-focus mode.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Android instrumentation and JVM tests.

**Spec:** User-approved implementation proposal in the conversation on 2026-10-24.

**Interfaces:** `NavigateViewModel.mapFocusMode: MutableStateFlow<Boolean>` retains the mode within the Navigate route; `NavigateMapFocusState(initialMapFocusMode: Boolean = false)` restores the composition-local presentation state from that value. `MapFocusModeEffects(state)` handles only transient hint/highlight timing.

## Global Constraints

- Do not persist this temporary mode as a user preference.
- Preserve explicit fullscreen exit, Back behavior, and dashboard visual precedence.
- Do not change recording, GPS, or guidance behavior beyond preserving map-focus state.

## Review Focus

- Navigate route recreation must restore the state held by its ViewModel; cover by restoring a new UI state from the retained ViewModel value.
- Opening and closing a fullscreen dashboard must leave map focus active; update the existing Compose UI test.
- Explicit fullscreen exit must still clear the retained mode; cover in the state test.

---

### Task 1: Preserve map focus across route and dashboard transitions

**Files:**
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/viewmodel/NavigateViewModel.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/NavigateMapFocus.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/screen/NavigateScreen.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/screen/NavigateContent.kt`
- Test: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/navigate/NavigateMapFocusStateTest.kt`
- Test: `app/src/androidTest/java/com/glancemap/glancemapwearos/presentation/features/navigate/MapFocusModeUiTest.kt`
- Modify: `docs/architecture/wear-navigation.md`

- [x] Update the dashboard UI test to expect map focus to remain enabled while the dashboard is open and after it closes; retain the assertion that dashboard controls take visual precedence.
- [x] Add state coverage showing a newly created map-focus UI state restores the retained mode and explicit exit clears it.
- [x] Add a route-scoped mutable flow to `NavigateViewModel`; initialize `NavigateMapFocusState` from its retained value, and update the ViewModel on enter and explicit exit.
- [x] Remove the dashboard-triggered call to `onExitMapFocus`; keep the hint and exit-highlight effects.
- [x] Document that map focus survives temporary navigation and dashboard coverage, but ends on explicit exit.
- [x] Run focused JVM tests, compile the targeted instrumentation test (no device was attached to run it), then run `scripts/quality-gate.sh` and `git diff --check`.
