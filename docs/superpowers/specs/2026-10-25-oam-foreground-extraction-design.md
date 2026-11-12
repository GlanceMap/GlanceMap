# OAM Foreground Extraction Fallback Design

## Intent

Keep long local OAM archive extraction reliable on the observed OnePlus Wear OS firmware without changing downloads, archive reuse, writer atomicity, or retry authority. The expedited `JobService` remains the best supported background path, but observed `STOP_REASON_DEVICE_STATE` stops show it is not a completion guarantee for a multi-minute extraction.

## Goals

- Hold the installation screen awake only while a local ZIP entry is actively extracted.
- Apply a restrained window brightness while that hold is active.
- Use the extraction lifecycle, not visible progress: a concurrent archive download may still be the displayed phase.
- When the screen is not showing Downloads, let the user return there from the ongoing notification; never start an Activity or wake the display automatically.
- Clear the signal and window effects after success, failure, pause/cancel, service cleanup, or leaving the screen.

## Non-goals

- No battery-optimization exemption, wake-lock reacquisition, automatic retry loop, ZIP/I/O tuning, or average-speed telemetry change.
- No change to the foreground service's persisted-plan ownership, network handoff, completed-archive reuse, or atomic writers.

## Approaches

1. Rely on the expedited `JobService`. Rejected: the current capture contains two `STOP_REASON_DEVICE_STATE` terminations while it runs.
2. Keep the whole download UI awake. Rejected: downloads remain valid background work and the extra display power would be unnecessary.
3. Publish actual extraction begin/end from the bundle pipeline, reflect it in the foreground-owned runtime state, and condition the Downloads window effect and notification destination on that state. Selected: it bounds the display cost to the vulnerable local writer and works even while download progress remains visible.

## Design

`OamBundleProgressArbiter.runExtraction` will report entering and leaving its serialized extraction lane. The foreground service publishes this as transient `isExtracting` state alongside its normal, possibly `DOWNLOADING`, progress. It resets that state on every terminal/cancellation/service-cleanup path.

`DownloadViewModel` copies that state into `DownloadUiState`. `DownloadScreen` installs the existing keep-screen-on effect only while that state is true and the screen is composed. The effect saves and restores both the view/window keep-awake flags and the prior window brightness, using a restrained brightness override.

When extraction is active, the foreground notification says installation is in progress and uses a user-tapped pending intent that navigates to the Downloads screen. A normal download notification keeps its existing generic app return path. The user must tap this notification; no code tries to wake or launch an Activity in the background.

## Validation

Unit coverage will prove lifecycle begin/end is emitted even when displayed progress is downloading, end fires on cancellation/failure, runtime state cleanup clears the flag, and notification navigation intent selects Downloads only for active extraction. Existing focused pipeline/coordinator/service tests plus the repository quality gate validate integration. A signed real-watch Bayern run remains required.
