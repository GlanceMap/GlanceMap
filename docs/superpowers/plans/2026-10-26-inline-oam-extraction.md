# Inline OAM Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore slow-continuation local OAM extraction by removing the expedited JobScheduler bridge while retaining the bounded screen-on assist.

**Architecture:** The download foreground service again owns the local ZIP writer directly. The existing `runOamExtractionLifecycle` callback continues to drive UI and notification extraction state; only JobScheduler ownership is removed.

**Tech Stack:** Kotlin, coroutines, Android foreground services, Compose.

**Spec:** `docs/superpowers/specs/2026-10-26-inline-oam-extraction-design.md`

## Global Constraints

- Do not commit or push.
- Keep retained archives, atomic writes, pause/cancel, network handoff and the bounded screen-on assist.
- Do not add an exemption, wake-lock retry, forced Activity launch or automatic extraction retry.
- Do not implement the user-facing guidance text until its exact wording is approved.

## Review Focus

- Screen-off direct extraction must no longer schedule a JobService that can receive `STOP_REASON_DEVICE_STATE`.
- `isExtracting` must remain true only during the actual writer and clear on completion, cancellation and failure.
- No manifest declaration or test may retain the removed job bridge.
- The foreground-service downloader remains the sole owner of the persisted plan and archive cleanup.

---

### Task 1: Restore inline extraction

**Files:**
- Modify: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundleDownloader.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamExtractionJobService.kt`
- Delete: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/download/OamExtractionJobCoordinator.kt`
- Delete: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/download/OamExtractionJobCoordinatorTest.kt`
- Modify: `docs/architecture/oam-screen-off-extraction-investigation.md`
- Modify: `docs/architecture/wear-transfer-service.md`
- Test: `app/src/test/java/com/glancemap/glancemapwearos/presentation/features/download/OamBundlePipelineTest.kt`

**Interfaces:**
- Consumes: `runOamExtractionLifecycle(onExtractionRunningChanged, block)`.
- Produces: foreground-service-owned map/POI ZIP writers that execute inline.

- [ ] Run the existing lifecycle tests as the regression baseline.
- [ ] Remove the JobScheduler wrapper and invoke the lifecycle wrapper around the existing inline ZIP writer.
- [ ] Remove the JobService, coordinator, manifest declaration and job-specific tests.
- [ ] Update architecture documentation to describe slow continuation and the retained screen-on aid.
- [ ] Run focused lifecycle tests, Kotlin compilation, manifest/lint checks, then the repository-required quality gate.
- [ ] Leave changes uncommitted and unpushed.
