# Screen-off bundle extraction investigation — 2026-10-25

## Verdict before implementation

The capture confirms device suspend during local extraction. It does **not** identify which system
power policy suppressed the app's CPU-awake request. Initial candidates were Low Power Standby and
OnePlus/Wear power policy. A later connected-watch baseline reports standard Low Power Standby
unsupported/disabled, so an OEM/other system suspend policy or process-state change is now a stronger
candidate on this firmware. Plain Android Doze remains unproven; foreground-service process state
must be captured during the failure. See the live update below.
No functional changes were made before the initial report; the confirmed live result below now justifies evaluating the phase-only job. A production JobScheduler migration or display
hold is not yet justified. The smallest justified next change is bounded diagnostic instrumentation,
followed by a controlled repeat on the watch.

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
| Ordinary Doze | Possible but unconfirmed. Generic Doze documentation says locks can be ignored; the API-34 process-state exception above prevents diagnosing this from screen-off alone. [Doze guidance](https://developer.android.com/training/monitoring-device-state/doze-standby) |
| Low Power Standby / OnePlus policy | Leading category; matches held-lock/non-interactive suspension. No current enablement, exemption or active-state evidence, so no OEM-specific claim is established. |
| App/process demotion or background restriction | Not ruled out: we lack system process/FGS snapshots during sleep. Record own foreground state and process importance, then compare with dumpsys. |
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

## Smallest next step, candidate fix and tradeoffs

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

A subsequent execution change would additionally need start/completion/failure/pause/cancel/restart,
no-duplicate-install, job rejection/rescheduling, final-file atomicity and resource-release coverage.
No such execution change is present in this evidence phase.

At the initial report boundary no watch was attached (`adb devices -l` was empty); the user
subsequently connected the watch for the baseline below. Repeat the same Bayern
map with the screen off, same ZIP and capture mode, matched power/network conditions. Compare at
least baseline and diagnostic build, then each single-variable isolation run. Acceptance for any
future fix remains wall time near awake time (target roughly 5–7 min), dramatically reduced suspend
time, no multi-minute progress gaps, correct final file, working pause/cancel, and unchanged Wi-Fi
handoff/throughput. Measure battery on comparable runs; host unit tests cannot establish this.

See the accompanying ADB procedure for controls and restoration steps. Until that capture exists,
the cause is **power-policy suppression likely; exact Doze/LPS/OEM cause unproven**.

## Live ADB baseline after the initial report

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

The next requested action is a natural screen-off Bayern repeat on the installed build, with Full
diagnostics enabled before starting, so system-side power and FGS state can be captured under load.

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
