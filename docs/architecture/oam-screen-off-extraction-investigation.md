# Screen-off bundle extraction investigation — 2026-10-25

## Latest watch validation and closure scope

The user's latest capture, `glancemap_diagnostics_20261025_174808_oneplus_opwwe251_s1.txt`,
records a successful Bayern installation on release 1.3.1/1041, Git `d39d0b7838ba`,
OnePlus OPWWE251 (Android 14). The user requested that this investigation stop here and the
changes be committed. The final success validates installation after resume; uninterrupted
completion with the display continuously off remains a known firmware limitation.

| Attempt | Observed outcome |
|---|---|
| 17:25:30 | Map extraction stopped by the system at 17:29:59 with reason 4 (`STOP_REASON_DEVICE_STATE`), followed by `bundle_failed`. Elapsed and uptime both 269,004 ms; sampled screen-off time 239,481 ms. |
| 17:36:14 | User pause at 17:40:25 cancels the current extraction. A resume request follows at 17:40:28. |
| 17:40:28 | Full map and POI output promoted atomically; `bundle_complete` at 17:46:37, 368,899 ms (6m08.899s). |

Final map output is 1,048,099,230 bytes, with 282,297 ms elapsed and uptime, zero measured
suspend, and sampled screen durations of 51,350 ms on / 230,947 ms off. POI output is
320,462,848 bytes, with 47,987 ms elapsed / 47,986 ms uptime and sampled durations of
22,997 ms on / 24,990 ms off. These sampled intervals show screen-on time during the successful
attempt; they do not establish a continuously screen-off success or removal of the device-state
restriction. The first attempt still reproduces that restriction in this same capture.

Both retained archives were reused (map ZIP 802,259,167 bytes; POI ZIP 112,942,272 bytes), so
this is a resumed install rather than a fresh archive-download test. DEM completed with all
28 required tiles ready (24 downloaded, four reused, zero unavailable). Completed extraction
jobs report `job_finished`; their subsequent reason-1 scheduler cleanup is app cancellation
after completion, distinct from the reason-4 failure before completion in the first attempt.

Evidence anchors in that capture: lines 849–911 (first attempt and failure), 1237–1260
(user pause, resume and archive reuse), 1328–1334 (map commit and job completion),
1372–1376 (POI commit and job completion), and 1508–1511 (DEM, bundle completion and lock release).
The preceding Manitoba capture also validated an entirely screen-off map extraction of 93,517 ms.
Neither result establishes a battery improvement. No display hold or permanent power exemption
has been added.

### Pause and sequencing

Pause cancels and joins the actual extraction writer. Resume opens the retained ZIP again and
starts extraction of the current file at byte zero; the uncompressed `.part` file does not contain
the decompressor state needed for direct continuation. Previously completed installed components
can be reused. This behavior predates the system-job change.

The pipeline already starts map extraction asynchronously while obtaining the next archive, and
overlaps POI extraction with later bundle work. A single extraction permit serializes the two
local ZIP writers. Downloading every component first would postpone the start of local processing;
multiple extractors could contend for watch CPU, memory and storage. No measured benefit justifies
changing that sequence in this fix. Extraction checkpoints are separate follow-up work.

The historical sections below describe the initial investigation and host validation. Their
pending-watch statements reflect that earlier stage; the results above supersede them.

## Initial verdict before live system evidence (superseded)

The capture confirms device suspend during local extraction. It does **not** identify which system
power policy suppressed the app's CPU-awake request. Initial candidates were Low Power Standby and
OnePlus/Wear power policy. A later connected-watch baseline reports standard Low Power Standby
unsupported/disabled, so an OEM/other system suspend policy or process-state change is now a stronger
candidate on this firmware. Plain Android Doze remains unproven; foreground-service process state
must be captured during the failure. See the live update below.
No functional changes were made before this initial report. Bounded diagnostics and a controlled
repeat were the first step; the live evidence below now justifies the phase-only job experiment.

## Confirmed root cause from the connected watch

Read-only system logcat during the repeat identifies the responsible policy:

- 01:14:08.167, BmManager/BmPowerManager: `[BP_ISSUE]forbin com.glancemap.glancemapwearos acquire partial wakelock when balance and screen off!`
- 01:14:08.170, PowerManagerService: `[BP_ISSUE]force release partial wakelock when screen off`,
  naming the app UID/process and tag `GlanceMap:OamDownload`.
- The system history shows acquisition at 01:13:41 and release at 01:14:08. The client subsequently
  continues reporting held=true, generation 1, six-hour timeout.
- During extraction the server reports app UID active in process state 4, dataSync FGS=true/type1,
  but app wake-lock count 0. A stall snapshot has no wake locks and no wake-lock suspend blocker.
  Deep idle is active, while standard LPS is disabled/unsupported.
- The repeat reaches 266,371 ms wall versus 119,872 ms uptime at 327,167,875 bytes: suspension is
  reproduced on the same watch while the service is still foreground.

The exact immediate mechanism is **OnePlus Balance Mode forcibly removing the application-owned
partial wake lock on screen-off**. Doze is observed concurrently, but ordinary AOSP Doze alone is
not a sufficient explanation; the vendor log explicitly names its policy. No idle allowlist or
other power setting has been changed. The user paused the test after this evidence was collected.

This supersedes the initial unproven-LPS lead above. A functional phase-only system-owned JobService
is now justified for evaluation; its performance and quota/lifecycle behavior still require testing
on the watch. The application must not reacquire its own ignored lock or request a permanent exemption.

## Phase-only job implementation and remaining acceptance

API 31+ now executes the existing local ZIP extraction body inside an expedited JobService. The
foreground service still owns the persisted bundle plan, notification, downloads and pause/cancel.
API 28–30 retains inline extraction because expedited jobs are unavailable. No network constraint,
WorkManager dependency, display hold, exemption or buffer/writer change is introduced.

Android 14's JobServiceContext acquires the execution lock in system_server with a WorkSource for
the app. This ownership differs from the app-owned lock that OnePlus forcibly released. Android
14's QuotaController gives foreground-service UIDs expedited quota availability, but runtime is
still bounded; current Android 16 documentation removes the FGS quota exemption. This is a reason
to test the actual firmware, not a universal guarantee of uninterrupted execution.
[Android 14 job lock source](https://android.googlesource.com/platform/frameworks/base/+/android14-release/apex/jobscheduler/service/java/com/android/server/job/JobServiceContext.java),
[Android 14 quota source](https://android.googlesource.com/platform/frameworks/base/+/android14-release/apex/jobscheduler/service/java/com/android/server/job/controllers/QuotaController.java),
[expedited API](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean)),
[Android 16 quota change](https://developer.android.com/about/versions/16/behavior-changes-all#job-runtime-quota).

The in-process coordinator admits one extraction. Its random request token rejects stale starts
and stops; an orphan scheduled job after process death does no work. The persisted foreground plan
remains the sole restart authority. A resume reuses the downloaded ZIP but starts extraction from
the beginning; a `.part` output is not a resumable ZIP offset. Atomic promotion is unchanged.

Admission failure, loss of expedited status, system stop or no start within 60 seconds produces the
existing localized retry error; there is no silent regular-job downgrade or automatic restart loop.
The caller waits for the actual worker to cancel and join before closing the request and canceling
its scheduled job. A new request cannot replace a writer that is still cleaning up. Job callbacks
stay nonblocking on the main thread, and completed/stopped runs cannot finish a newer job.

The original application lock may still report disabled during a successful job; system-side job
and power captures, clocks, correct output and lifecycle cleanup are the acceptance evidence.
Screen-off Bayern timing and pause/cancel validation on the connected watch are still pending.
No performance or battery improvement is claimed before that measurement.

## Host validation and independent review

- New lifecycle tests: nine cases covering matching/duplicate/orphan starts, quota rejection,
  start timeout, pause before start, pause waiting for writer cleanup, system stop with no automatic
  retry, writer failure, canceled service scope before dispatch, and stale callbacks after resume.
- Focused command: `./gradlew :app:testDebugUnitTest --tests '*OamExtractionJobCoordinatorTest'
  --tests '*OamExtractionPowerDiagnosticsTest' --tests '*OamBundlePipelineTest'
  --tests '*AtomicStreamWriterTest' --tests '*OamDownloadForegroundServiceTimeoutTest'`:
  38 tests passed. Final combined focused test/detekt/debug APK build passed in 1m 12s.
- `scripts/quality-gate.sh`: passed in 4m 5s, 108 tasks; 2,058 tests (Wear 1,792, phone 266),
  zero failures/errors/skips. Both lint reports have zero Fatal/Error issues; `git diff --check`
  passed. Existing lint parser prolog messages and Gradle deprecation warnings remain in the log.
- No dependency or lint suppression was added. Unrelated translation/lint configuration and other
  chats' commits were preserved and excluded from the scoped extraction commit.
- Independent review found that `onStopJob` receives a separately parceled JobParameters object;
  matching by reference could finish a stopped run. It now matches the unique request token.
  Stale starts no longer overwrite the active parameters, and destroyed-before-dispatch workers
  fail the owner promptly with a retryable error. Final independent recheck found no remaining
  code bug; historical attribution statements in this report were updated after its doc review.

Real-device acceptance remains open. Command-line `:app:assembleRelease` is refused by the existing
release-signing guard because private signing settings are unavailable. The debug APK's public
signing certificate differs from the installed app, so no replacement/uninstall was attempted.
The user explicitly deferred real-watch testing and asked to finish the code first. A signed Wear
`app` release APK through Android Studio will be needed for that later test. The watch remains
paused; no allowlist, power setting, forced idle or screen hold has been changed.

## Evidence from the supplied capture

File: `glancemap_diagnostics_20261024_224105_oneplus_opwwe251_s1.txt`; Wear 1.3.1/1041,
release Git `747d674dc8e0`, OnePlus OPWWE251, Android 14/API 34, target 36.

| Observation | Evidence |
|---|---|
| Map extraction completed | Lines 1427–1428: 1,048,099,230 bytes, 1,583,794 ms elapsed, 318,825 ms uptime, 1,264,969 ms suspend difference |
| Largest sampled gap | Line 1410: 646,404 ms elapsed, 3,035 ms uptime, 643,369 ms suspend difference; screen sampled OFF on return |
| Screen-on recovery association | Lines 1231 and 1422: progress resumes with screen sampled ON after 177,130 and 243,524 ms suspend respectively |
| Wake lock did not expire | Line 937 acquired generation 1, timeout 21,600,000 ms; all sampled progress reports held=true, generation 1; release only at line 1651 |
| Local output completed safely | Line 1426: writer duration 318,797 ms, fsync 48 ms, commit 3 ms, full output size; it uses a monotonic clock excluding sleep |
| POI control | Lines 1515–1516: 320,462,848 bytes in 56,552 ms elapsed / 56,536 ms uptime, 16 ms suspend, 5.40 MiB/s wall rate |
| Wi-Fi handoff works | Lines 961–965: planned retry from byte 1,244,801, Wi-Fi connection; completed map ZIP before extraction starts at line 1195 |
| No policy attribution | Capture has no device-idle, power allowlist, Low Power Standby, process-importance or system wake-lock-enabled state |

About 79.9% of the map elapsed interval is suspend. The reported 3.14 MiB/s uses uptime, not thread CPU
time; uptime also includes scheduling, disk waits and other awake work. Process CPU is aggregate across
threads (516,667 ms here), so it must not be equated to the 318,825 ms awake interval. The POI interval
includes sampled screen-off time as well: it is a useful low-suspend control, not an all-screen-on run.
Screen-duration counters use the last sampled interactive state, so they estimate transitions across
long unsampled gaps. Likewise, `maxWallNoProgressMs` is sampled rather than a continuous exact timestamp.

The wall-clock `extract_complete.durationMs` differs from the monotonic summary by about 0.9 s; that
does not explain the 21 minutes of sleep. Conclusions above use elapsedRealtime/uptime differences.

## Why a held lock can be ineffective

`WakeLock.isHeld()` reports the Java acquisition state. Android separately enables/disables the lock
in system_server. Therefore held=true is not evidence that a suspend blocker remained effective.
[WakeLock API](https://developer.android.com/reference/android/os/PowerManager.WakeLock),
[framework implementation](https://android.googlesource.com/platform/frameworks/base/+/ddcd7479aab5/core/java/android/os/PowerManager.java).

In the Android 14 PowerManagerService source, ordinary device-idle suppression checks allowlists and
process state, exempting foreground/bound-foreground service states from that particular branch.
Low Power Standby has a separate allowlist and a stricter process-state boundary; an ordinary FGS
can be affected. These AOSP details are evidence for distinct hypotheses, not proof of the OnePlus
firmware implementation. [Android 14 source, setWakeLockDisabledStateLocked](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/power/PowerManagerService.java#4201).

The public PowerManager API explicitly documents that enabled Low Power Standby can ignore locks
and network access for non-interactive apps, including foreground services. Enabled is configuration,
not proof that restrictions were active at the instant of a stall. Power allowlisting and Low Power
Standby exemption are separate queries. The existing capture answers neither.
[PowerManager reference](https://developer.android.com/reference/android/os/PowerManager).

## Alternatives and current confidence

| Candidate | Assessment |
|---|---|
| Device suspend | Confirmed by the two monotonic clocks. Android documents that elapsedRealtime includes deep sleep and uptimeMillis excludes it. [SystemClock](https://developer.android.com/reference/android/os/SystemClock) |
| Ordinary Doze | Observed concurrently in the repeat, but the vendor log explicitly attributes forced release to Balance Mode. Ordinary AOSP Doze alone is not the established immediate cause. [Doze guidance](https://developer.android.com/training/monitoring-device-state/doze-standby) |
| Low Power Standby / OnePlus policy | Standard LPS is unsupported/disabled on the connected firmware. OnePlus Balance Mode is confirmed by its forced-release log naming the app lock. |
| App/process demotion or background restriction | The repeat captures active UID/process state 4 and dataSync FGS=true during suspend, with no effective app lock. A missing foreground service is not the immediate cause. |
| dataSync six-hour timeout | Does not explain this API-34 run or 26-minute pause/resume pattern. The Android 15 timeout machinery is not retroactively installed by targetSdk 36. [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| Job/coroutine pause alone | Cannot explain the device-wide elapsed/uptime divergence. No user pause/cancel or lock-release event is present during extraction. A concurrently delayed coroutine is possible as a consequence of sleep. |
| Slow disk, ZIP buffers, fsync | Can affect awake throughput but cannot generate the measured sleep time. fsync/commit are tiny, bytes advance at ordinary speed while awake, and POI is a useful control. No retuning justified. |
| Thermal throttling / battery saver | Sampled thermal status is 0 and energy telemetry saver=false with high battery; unsampled changes remain possible. Frequency scaling does not itself stop uptime. |

## Current paths and history

- `OamDownloadForegroundService.startPersistedOperation` enters dataSync foreground state and owns
  the IO coroutine; progress updates call startOrUpdateForeground. `acquireLocks` uses an app-owned
  non-reference-counted partial lock, tag `GlanceMap:OamDownload`, six-hour timeout. `currentKeepAliveState`
  reads Java isHeld, age and generation. Pause/cancel/timeout cancel the operation; terminal paths
  and onDestroy release locks. No independent extraction-pause state was found.
- `OamBundleDownloader.downloadBundle` overlaps the next payload with one serialized extraction lane.
  `extractFirstEntry` feeds ZipInputStream to the existing atomic repository writer. Its reporter is
  debug-only; a delayed heartbeat cannot run while the application processor is suspended.
- `OamExtractionTelemetryReporter` receives elapsedRealtime and uptimeMillis correctly. Its summary
  is emitted in finally, so successful completion is established by the following extract_complete
  and bundle_complete events as well.
- `AtomicStreamWriter` uses the existing two-buffer pipeline, cancellation checks, temporary file
  and final promotion. No new copying implementation is needed.
- Manifest declares WAKE_LOCK and dataSync FGS. Existing `TransferLockManager` uses the same kind
  of app-owned lock, not a distinct execution guarantee. Navigation attaches Wear OngoingActivity
  to a pinned notification. OAM already has an ongoing progress notification, without that attachment.
- `e896fe5f` (July 31) already contains the six-hour partial-lock policy. `07692908` (August 20)
  added extraction accounting; `378f394e` (August 21) added lock age/generation diagnostics;
  `98e60a3c` (August 22) added FGS timeout handling. `40bd7397` and `8b60896c` (October 24)
  changed retained networking/handoff, not that lock policy. Writer changes in `1a5ad045`
  (September 15) concern progress/metrics. This is not evidence that the recent network fix created
  the suspend policy.

## Initial recommendation before live evidence (superseded)

Add diagnostics only: power-policy and process snapshots at the existing bounded extraction log
cadence, foreground/operation state, lock identity/tag, and the public API-33 wake-lock state listener.
That listener reports system enabled/disabled transitions independently of isHeld. Cap transition
logs per lock generation, ignore stale callbacks, detach at release, and do not reacquire locks.
Callbacks are observed when the app can run; their delivery timestamp is not the original suspend
timestamp. Keep ADB system-side evidence for attribution.

If that confirms app-owned-lock suppression, the next candidate experiment is a local extraction-only
JobService, leaving the current ZIP download service/network path intact. Wear documentation says
JobScheduler owns a wake lock while running a job. However expedited execution is quota-limited,
may be refused/downgraded, and does not guarantee an uninterrupted 5–7-minute run. Android 16 applies
job runtime quotas even alongside an FGS. A safe implementation must persist ownership, handle stop
reasons and quota rejection, prevent duplicates and avoid repeatedly restarting a ZIP that cannot
finish within its available budget. [Wear power guidance](https://developer.android.com/training/wearables/apps/power),
[expedited API](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean)),
[Android 16 quotas](https://developer.android.com/about/versions/16/behavior-changes-all#job-runtime-quota).

API-34 user-initiated data-transfer jobs require a network constraint; that API is not interchangeable
with expedited local computation. No production job migration will be made without the isolation
evidence. A display hold remains a last-resort experiment after system-owned execution is shown
insufficient, because keeping Wear interactive costs battery and changes UX. No battery improvement
can be claimed from CPU time alone. OngoingActivity can improve task return/visibility but is not a
CPU-suspend contract. [Wear ongoing/ambient guidance](https://developer.android.com/training/wearables/always-on).

## Proposed verification and watch acceptance

Diagnostic tests: distinguish held from system-enabled=false; stale-generation callbacks and
release cleanup; cap transition logs; unsupported API fields remain unknown, never falsely disabled;
throttle expensive reads; retain wall/uptime accounting and bounded heartbeat behavior. Run existing
pipeline, atomic-file and operation lifecycle tests plus the canonical quality gate. Get an independent
review of diagnostics and the attribution/ADB plan before claiming that phase complete.

The execution phase adds start/completion/failure/pause/cancel/restart, duplicate/stale admission,
quota rejection/start timeout, service destruction and worker cleanup coverage. Existing pipeline,
foreground-timeout and atomic-writer tests remain required alongside real-device acceptance.

At the initial report boundary no watch was attached (`adb devices -l` was empty); the user
subsequently connected the watch for the baseline below. Repeat the same Bayern
map with the screen off, same ZIP and capture mode, matched power/network conditions. Compare at
least baseline and diagnostic build, then each single-variable isolation run. Acceptance for any
future fix remains wall time near awake time (target roughly 5–7 min), dramatically reduced suspend
time, no multi-minute progress gaps, correct final file, working pause/cancel, and unchanged Wi-Fi
handoff/throughput. Measure battery on comparable runs; host unit tests cannot establish this.

See the accompanying ADB procedure for controls and restoration steps. Attribution is now confirmed
by the live vendor log above. The remaining capture must validate the job fix on the same watch.

## Historical idle baseline before the live reproduction

The user connected the actual OPWWE251 over wireless ADB. Read-only package/power/idle/service
snapshots confirm installed 1.3.1/1041, target 36, last update October 24. No bundle service was running
during this baseline. It therefore cannot identify the failing extraction state.

- Standard Low Power Standby controller reports `mSupportedConfig=false`, `mIsEnabled=false`,
  `mIsActive=false`; PowerManager also reports `mLowPowerStandbyActive=false`. This weakens the
  standard-LPS candidate significantly for the connected firmware; it was not recorded in the old trace.
- The initial power snapshot has `mDeviceIdleMode=false`, `mLightDeviceIdleMode=false`,
  `mWakeLockSummary=0x0` and no app wake lock. The idle controller is INACTIVE and not forced.
  A later WearPowerService snapshot reports device idle=true. These snapshots are taken at different
  times without an active extraction and must not be treated as a contradiction or a stall reproduction.
- The app's ID is not in the captured device-idle allowlist. No exemption was added or removed.
- The system power history contains a `BmSwitchToMcu` lock event. This is a reason to inspect the
  OnePlus MCU/Android transition during the next failure, not proof that the event caused that failure.
- No package reinstall, forced idle, screen override, job or power-setting mutation was performed.
  The full dumps remain local and are not committed; only these relevant findings are recorded.

The subsequent natural screen-off Bayern repeat supplied the vendor forced-release evidence at
the top of this report. The watch was paused afterward; no allowlist or power setting was changed.
The next device step is a correctly signed candidate update and a screen-off extraction measurement.

## ADB isolation procedure — controls not yet executed

Enable Full diagnostics **before** starting the installation: the system wake-lock listener attaches
only on acquisition while capture is enabled. Enabling capture mid-download can leave
`wakeLockSystemEnabled=na` until the next lock generation; this means unobserved, not enabled.
Use the same Bayern archive and watch capture mode for each run. Prefer wireless ADB with no charger;
USB power/charging changes idle eligibility. Reading dumps can briefly wake the CPU, so do not poll
the device continuously; include an uninstrumented natural screen-off control. Keep captures local.
Substitute the actual already-connected watch serial for `WATCH_SERIAL` in these commands.

Before changing any state, record its original values:

```sh
adb -s WATCH_SERIAL shell dumpsys power > baseline-power.txt
adb -s WATCH_SERIAL shell dumpsys deviceidle > baseline-deviceidle.txt
adb -s WATCH_SERIAL shell dumpsys deviceidle whitelist > baseline-allowlist.txt
adb -s WATCH_SERIAL shell am get-standby-bucket com.glancemap.glancemapwearos
adb -s WATCH_SERIAL shell cmd appops get com.glancemap.glancemapwearos RUN_ANY_IN_BACKGROUND
adb -s WATCH_SERIAL shell cmd power help > power-shell-help.txt
adb -s WATCH_SERIAL shell dumpsys activity services com.glancemap.glancemapwearos > baseline-services.txt
adb -s WATCH_SERIAL shell dumpsys activity processes > baseline-processes.txt
adb -s WATCH_SERIAL shell dumpsys batterystats com.glancemap.glancemapwearos > baseline-batterystats.txt
```

Start capture and Bayern installation using the existing watch UI. Turn the screen off naturally.
At extraction start and once during a sustained stall, capture `dumpsys power`, `dumpsys deviceidle`,
`dumpsys activity services com.glancemap.glancemapwearos`, `dumpsys activity processes`,
`dumpsys batterystats com.glancemap.glancemapwearos`, and `logcat -d -v threadtime` into separate
local files labelled start/stall/resumed. Do not turn on the display before the stall snapshot.
Export the app diagnostics after completion/failure. Logcat/process dumps can contain unrelated
application information; trim them before sharing, without discarding power-state evidence.

In `dumpsys power`, locate `GlanceMap:OamDownload`, its owner UID and any DISABLED state, plus
`mDeviceIdleMode`, `mLowPowerStandbyActive`, low-power allowlist, wakefulness and suspend blockers.
Compare the UID process state with service `isForeground`/`foregroundServiceType`; foreground=true
in app telemetry is only the app's last successful start/stop observation, and the type is the value
requested by that path. A system-disabled callback is stronger lock evidence but does not identify
the disabling policy or the original transition time if callback delivery was delayed by suspend.
Use actual API-34/OEM dump fields; absence of a field is not false.

Run these controls separately, restoring the baseline between runs:

1. **Temporary idle allowlist.** If absent from the original allowlist, add only for one repeat:

   ```sh
   adb -s WATCH_SERIAL shell dumpsys deviceidle whitelist +com.glancemap.glancemapwearos
   # Repeat screen-off Bayern extraction and capture start/stall/end states.
   adb -s WATCH_SERIAL shell dumpsys deviceidle whitelist -com.glancemap.glancemapwearos
   ```

   Never remove a pre-existing user/system exemption. Verify the list is restored. A positive
   result implicates an allowlist-sensitive policy but is not by itself proof of plain Doze;
   inspect Low Power Standby state as well. A negative result does not establish a kernel bug.

2. **Controlled Doze, distinct from natural screen-off.** This is a diagnostic state override.
   If testing via a powered cable, `dumpsys battery unplug` simulates unplugging; it does not actually
   remove charging or cable effects. Prefer a truly unplugged repeat. After extraction starts and
   the display is off, use:

   ```sh
   adb -s WATCH_SERIAL shell dumpsys deviceidle force-idle
   adb -s WATCH_SERIAL shell dumpsys deviceidle
   adb -s WATCH_SERIAL shell dumpsys power
   # Observe/capture, then always remove the override:
   adb -s WATCH_SERIAL shell dumpsys deviceidle unforce
   adb -s WATCH_SERIAL shell dumpsys battery reset
   ```

   Only reset battery simulation if you enabled it for this experiment; preserve any pre-existing
   test setup. Record whether the device really entered deep idle and whether the app lock became
   disabled with an FGS process. Do not infer Doze from the display state or command success alone.

3. **Low Power Standby isolation.** Inspect the captured active/enabled/allowlist state and the
   device's `cmd power help`. If that firmware exposes a supported standby test control, repeat
   with *only* LPS disabled and restore its exact original value afterwards. Record the actual
   device-provided command and before/after dumps. Do not guess privileged setting keys, alter
   permanent optimization exemptions, or apply OEM hacks. If no control exists, retain the dumps
   and enabled/disabled callback sequence as evidence; do not label an unperformed control successful.

4. **System-owned execution experiment.** Only after attribution, test an extraction-only job on
   the same watch, retaining the current download path. Record actual expedited status, admission
   result, stop reason, quota and completion; collect `dumpsys jobscheduler` and system lock state.
   A job rejected for quota is not a successful fallback. Verify partial output/ZIP restart behavior
   before adopting any job retry policy. A display hold is considered only after this path is shown
   insufficient or unsupported; it is not part of the diagnostic patch.

If dumps are inconclusive, capture a bounded Perfetto trace with `linux.ftrace` events
`power/suspend_resume`, `sched/sched_switch`, `sched/sched_waking`, `power/cpu_idle` and
`power/cpu_frequency`, plus `linux.process_stats` for thread-name resolution. Use an 8–16 MiB ring
buffer. Record the available data sources/events first; unsupported watch builds may omit some.
Use `adb -s WATCH_SERIAL shell perfetto --query` to inspect data-source support.
For a targeted observation, place the following config in `extraction.pbtxt` and use:

```text
buffers { size_kb: 16384 fill_policy: RING_BUFFER }
duration_ms: 60000
data_sources { config {
  name: "linux.ftrace"
  ftrace_config {
    ftrace_events: "power/suspend_resume"
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_waking"
    ftrace_events: "power/cpu_idle"
    ftrace_events: "power/cpu_frequency"
  }
} }
data_sources { config {
  name: "linux.process_stats"
  process_stats_config { scan_all_processes_on_start: true }
} }
```

```sh
adb -s WATCH_SERIAL push extraction.pbtxt /data/local/tmp/glancemap-extraction.pbtxt
adb -s WATCH_SERIAL shell perfetto --background --txt -c /data/local/tmp/glancemap-extraction.pbtxt \
  -o /data/misc/perfetto-traces/glancemap-extraction.perfetto-trace
# The background command prints its PID. If still running after the wall-time bound:
adb -s WATCH_SERIAL shell kill -INT TRACE_PID
adb -s WATCH_SERIAL pull /data/misc/perfetto-traces/glancemap-extraction.perfetto-trace
```

Perfetto `duration_ms` on older versions counts awake time, so a 60-second trace can span a much
longer wall interval during this bug. Keep a separate wall-time bound and stop/collect the trace if
it persists past the planned observation; do not use tracing as a device-awake workaround. Inspect
kernel suspend boundaries versus reader/writer thread scheduling. A process that is merely blocked
while uptime advances differs from the demonstrated system-suspend gaps.
[Perfetto recording](https://perfetto.dev/docs/quickstart/android-tracing),
[duration semantics](https://perfetto.dev/docs/reference/trace-config-proto),
[power trace events](https://github.com/google/perfetto/blob/main/docs/data-sources/cpu-freq.md).

After each test, confirm no forced idle/battery simulation or newly added allowlist entry remains.
Record start/end battery, charging, thermal state, same ZIP size/identity, wall/uptime/suspend totals,
longest gap, final-file integrity and pause/cancel behavior. Compare Wi-Fi download metrics separately
from local install metrics. Do not claim the 5–7-minute acceptance target or battery gains until a
repeat on the real watch establishes them.
