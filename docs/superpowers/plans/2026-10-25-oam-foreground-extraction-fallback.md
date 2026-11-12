# OAM Foreground Extraction Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Wear OS installation screen awake, at restrained brightness, only while a local OAM extraction is actually running.

**Architecture:** The pipeline reports real local-extraction lifecycle changes independently of its progress arbiter. The foreground service owns the transient state and notification intent; the Downloads UI turns that state into a scoped window effect.

**Tech Stack:** Kotlin, coroutines, Android `JobService`, AndroidX Compose, `NotificationCompat`.

**Spec:** `docs/superpowers/specs/2026-10-25-oam-foreground-extraction-design.md`

## Global Constraints

- Retain the existing expedited job as the supported background path; never assume it defeats OEM device-state stops.
- Do not introduce exemptions, app wake locks, automatic extraction retries, forced background Activity starts, or I/O retuning.
- Preserve persisted-plan ownership, archive reuse, network handoff, and atomic writes.
- Apply the display hold only to an active local extraction and release it on all exits.

## Review Focus

- A map extraction runs while POI download progress is displayed: the screen still stays awake.
- A paused, cancelled, failed, or destroyed service cannot leave a stale extraction flag.
- A user-tapped notification opens Downloads but no background code launches an Activity.
- An existing screen brightness and another keep-screen-on user are restored correctly on disposal.
- Small files remain eligible to finish in the background if the user does not return to the Downloads screen.

### Task 1: Propagate local extraction lifecycle through the owned operation

**Files:**
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundlePipeline.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundleDownloader.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamDownloadServiceClient.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamDownloadForegroundService.kt`
- Test: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundlePipelineTest.kt`

**Interfaces:**
- Produces `isExtracting: Boolean` on `OamOwnedDownloadState` and an extraction lifecycle callback from `OamBundleDownloader.downloadBundle`.

- [ ] **Step 1: Write lifecycle tests**

Assert that extraction begin/end events wrap the active writer, appear even when network progress remains the visible phase, and end on cancellation/failure.

- [ ] **Step 2: Run focused test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*OamBundlePipelineTest'`
Expected: FAIL because no lifecycle signal exists.

- [ ] **Step 3: Add the smallest lifecycle callback and owned-state propagation**

Keep the callback scoped to `runExtraction`'s `try/finally`. The foreground service publishes `isExtracting=true` without replacing its current progress phase, publishes false in the callback and all terminal cleanup paths, and uses the same state for an extraction-specific notification.

- [ ] **Step 4: Run focused test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*OamBundlePipelineTest' --tests '*OamDownloadForegroundServiceTimeoutTest'`
Expected: PASS.

### Task 2: Scope the display effect and return notification to installation

**Files:**
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/ui/KeepScreenOnEffect.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/DownloadViewModel.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/DownloadScreen.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/MainActivity.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamDownloadForegroundService.kt`
- Modify: `app/src/main/res/values*/strings.xml`
- Test: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundlePipelineTest.kt`

**Interfaces:**
- Consumes `OamOwnedDownloadState.isExtracting`.
- Produces a user-tapped extraction notification that routes to `WatchRoutes.DOWNLOAD`.

- [ ] **Step 1: Write helper tests**

Assert the UI decision depends on `isExtracting`, not phase, and notification routing selects Downloads only for active extraction.

- [ ] **Step 2: Run focused test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*OamBundlePipelineTest'`
Expected: FAIL because the decision helpers do not exist.

- [ ] **Step 3: Add the scoped UI effect and user-initiated return flow**

Save/restore brightness and keep-awake state in `DisposableEffect`. Make the notification pending intent request Downloads and have `MainActivity` consume that request. Do not create an activity launch from the service.

- [ ] **Step 4: Run focused tests and static checks**

Run: `./gradlew :app:testDebugUnitTest --tests '*OamBundlePipelineTest' --tests '*OamExtractionJobCoordinatorTest' --tests '*OamDownloadForegroundServiceTimeoutTest' :app:detekt`
Expected: PASS.

### Task 3: Document limits and complete repository verification

**Files:**
- Modify: `docs/architecture/oam-screen-off-extraction-investigation.md`

- [ ] **Step 1: Update the architecture record**

Record that the job path remains supported but not firmware-reliable, and describe the user-initiated bounded foreground fallback and required Bayern validation.

- [ ] **Step 2: Run repository verification**

Run: `scripts/quality-gate.sh && git diff --check`
Expected: PASS.
