# Normalize Bundle Refresh Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Present locally damaged bundle components as a normal available refresh while retaining targeted redownload behavior.

**Architecture:** Collapse the public repair status into `UPDATE_AVAILABLE`, but retain the internal
repair-file list as input to the existing per-component refresh forces. Remove only the repair
presentation from installed rows, dialogs and summaries; do not alter integrity or known-missing
tile detection.

**Tech Stack:** Kotlin, coroutines, Compose, JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-26-normalize-bundle-refresh-status-design.md`

## Global Constraints

- Preserve known-missing DEM marker exclusion and targeted file refreshes.
- Do not change integrity checks, network metadata behavior, download sequencing or retries.
- Do not commit or push.

## Review Focus

- A locally damaged map/POI/routing/DEM file must remain selected for refresh after repair wording disappears.
- A known-missing DEM tile must remain absent from both integrity failure and remote refresh work.
- A remote update and a local damaged file in one bundle must produce one normal update state and refresh both files.
- Installed rows must not show warning styling merely because cached local health has file names.
- Existing up-to-date and unknown responses must retain their current dialogs.

---

### Task 1: Normalize the update result and refresh force semantics

**Files:**
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamDownloadModels.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundleDownloader.kt`
- Modify: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundleRefreshSummaryTest.kt`
- Modify: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/download/OamRemoteFileRequestsTest.kt`

**Interfaces:**
- Produces: `resolveOamBundleUpdateStatus(...)` returning `OamBundleUpdateStatus.UPDATE_AVAILABLE` for either local affected files or changed remote metadata.
- Preserves: `OamBundleUpdateCheck.refreshForces(area)` combining changed and local affected names.

- [ ] **Step 1: Write failing model tests**

Add a test asserting a local affected-file result resolves to `UPDATE_AVAILABLE`, and update the
targeted-force test to use that normal status. Retain the known-missing-tile request test.

- [ ] **Step 2: Run the focused tests to verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamBundleRefreshSummaryTest' --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamRemoteFileRequestsTest'`

Expected: fail because the new status-resolution function does not exist.

- [ ] **Step 3: Implement the smallest model/downloader change**

Add `resolveOamBundleUpdateStatus`, use it in `checkBundleUpdates`, and remove the obsolete
`REPAIR_NEEDED` enum branch while retaining `repairFileNames` for `refreshForces`.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run the Step 2 command.

Expected: PASS.

### Task 2: Remove repair presentation

**Files:**
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/DownloadScreen.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/DownloadDialogs.kt`
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/DownloadViewModel.kt`
- Modify: relevant download feature tests that reference `REPAIR_NEEDED`

**Interfaces:**
- Consumes: Task 1's status enum without `REPAIR_NEEDED`.
- Produces: normal update labels and dialog/summary behavior for locally affected files.

- [ ] **Step 1: Update tests for normal-update presentation**

Replace repair-status assertions with normal update assertions while preserving the forced file
list. Add a focused assertion that the row's warning input is no longer derived from local health.

- [ ] **Step 2: Run focused tests to verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamBundleRefreshSummaryTest' --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamBundleDeletionTest'`

Expected: compile/test failure until production repair branches are removed.

- [ ] **Step 3: Remove repair-specific UI and ViewModel branches**

Always render normal installed-bundle subtitles and warning=false; remove repair-specific dialog,
summary and status-message branches. Keep the existing normal update confirmation and refresh request.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run the Step 2 command.

Expected: PASS.

### Task 3: Verification and documentation

**Files:**
- Modify: `docs/architecture/wear-transfer-service.md` only if its bundle-health wording describes visible repair status.

- [ ] **Step 1: Run download regression tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamBundleRefreshSummaryTest' --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamRemoteFileRequestsTest' --tests 'com.glancemap.glancemapwearos.presentation.features.download.OamBundleDeletionTest'`

Expected: PASS.

- [ ] **Step 2: Run repository verification and whitespace check**

Run: `scripts/quality-gate.sh` and `git diff --check`.

Expected: PASS; no whitespace errors.
