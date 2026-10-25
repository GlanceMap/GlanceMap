# Recording sensor freshness

Android recording sensors currently register from the activity-root
`RecordingSensorBridge` composition, above navigation destinations. Ordinary
Activity pause and display state do not gate this effect. Composition disposal,
manual/automatic recording pause, save completion, source/configuration changes
and permission changes can dispose/recreate it. This is not service ownership:
Activity destruction during an active recording remains a lifecycle limitation.

The shared listener uses `RecordingSensorThread`; metrics post to the main
Handler and go directly to the application-container recording ViewModel.
Pressure also feeds the synchronized hybrid elevation engine on the sensor
thread using measurement time. Neither path needs a visible recording dashboard.

Heart rate is on-change. A first valid BPM after a published no-reading must
bypass ordinary coalescing: waiting for another event can lose the only valid
transition. Normal publications stay at 1 Hz, or 250 ms for changes of at least
5 BPM. Invalid readings do not refresh the last-valid timestamp. The recording
snapshot expires optional metrics after 15 seconds; cached live fields may
still retain their last value. Silence alone cannot distinguish unchanged HR,
invalid readings, delayed delivery or sensor loss.

The step counter produces a session total relative to its cumulative baseline.
Counter-derived cadence takes priority for the existing 15-second freshness
window. Detector cadence is a fallback and never renews the counter timestamp;
a delayed detector measurement older than the counter cannot overwrite it.
Only a detector payload of exactly one contributes to the 30-second window.
Counter/detector derivation and freshness use monotonic source measurement time,
converted to wall time for recording snapshots. Missing/future timestamps and
invalid counter values cannot refresh a reading. Duplicate/out-of-order source
times cannot advance cadence state.

Counter cadence accumulates cumulative-step changes over at least five seconds of source
measurement time. Shorter updates retain the window's step baseline instead of dividing a
burst by a tiny adjacent-event interval. Estimates above 300 spm are withheld while the
window continues accumulating real elapsed time; the session's cumulative steps remain
available. A five-second-or-longer observation with unchanged steps rebases the window
without publishing fresh cadence, and a counter decrease rebases cadence independently
of session step accounting. Sparse updates still use their actual measurement interval.
The newest source timestamp is tracked separately from the window anchor, so delayed or
duplicate measurements cannot replace the accumulated window. Only a newly accepted rate
updates counter cadence priority/freshness; no timer manufactures readings during silence.

These bounds address the OnePlus OPWWE251 evening capture on 2026-10-25, which exported
2,667/3,333 spm despite publishing no detector cadence. Five seconds reduces short-update
timing sensitivity and may delay the initial estimate or a cadence change by that window;
300 spm is a conservative rejection ceiling, not a clipped substitute reading. The raw
measurement pairs at those spikes were not logged, so an identical physical walk is still
needed to establish device behavior. This does not claim to fix screen-off callback loss.

Legacy `TraceRecording` counters have different meanings:

| Counter | Meaning |
| --- | --- |
| `heartRateSensorEvents` | Raw watch HR callbacks, including invalid/coalesced, observed at the next HR publication; Bluetooth counts timestamp advances |
| `stepSensorEvents` | Published step timestamp advances, not number of steps or detector callbacks |
| `cadenceSensorEvents` | Published cadence timestamp advances from either derivation |
| `pressureSensorEvents` | Valid pressure callbacks observed at the next pressure publication |

`TraceRecordingSensors event=pipeline` adds bounded, per-registration, per-source
diagnostics. `callbacks`, `usable`, `invalid`, `coalesced` and `published` count
different stages. `usable` means the existing processing path accepted the
callback (including the detector's unit-value filter), not that the measurement is fresh
or the hardware accuracy is reliable. Raw values
and accuracy are diagnostic context, not a new validation policy.
Source/callback ages, publication age and callback-to-publication delay separate
delayed delivery from delayed main-thread publication. Maximum source delivery
age and publication delay are retained per registration so a later timely event
cannot erase earlier delay evidence. Elapsed time minus uptime growth measures suspend
time between callbacks; it does not prove why a particular sensor was silent.
Snapshots use the existing 60-second status loop and registration/disposal
boundaries. Diagnostic capture disabled means no per-callback diagnostic state
updates. No per-event logging, new polling loop or sensor re-registration is added.

SmartTrack separately represents step/cadence availability and motion.
Unavailable steps do not mean unchanged steps and cannot establish
`STEP_STILLNESS`. Credible GPS outside the existing deadband can release a
point without sensors. Uncertain GPS with unknown sensors still needs coherent
slow-progress confirmation. For example, 1.77 m/s with 2.50 m/s speed uncertainty
fails the existing `max(1.5, speed × 1.25)` credibility test; a held point in that
case is not proof that unknown sensors were treated as stationary.

The OnePlus OPWWE251 capture from 2026-10-24 shows screen-off step/cadence
publication silence while pressure and GPS continue, and sparse HR callbacks.
It does not contain raw HR values or source times, so it cannot establish an
OEM limitation, complete process suspension, or a recent regression. The latent
HR coalescing issue dates to `f052e4e6` (2026-08-12). Physical validation must
compare pipeline snapshots across screen off/on and distinguish raw silence,
invalid values, coalescing, delayed source delivery and main-thread queue delay
before changing lifecycle, foreground-service, wake-lock or freshness policy.
The 2026-10-25 capture narrows this to at least 118 seconds without either raw
step callback while pressure continues. It also contains cumulative-looking
detector payloads, which cannot be counted as one callback per step. Pressure
clock growth does not support sustained AP suspension as the full explanation.

An opt-in `-PrecordingWakeLockExperiment=true` build holds an application-owned
partial lock only while recording is active, unpaused and not saving. It releases
on pause/end and has a 30-minute diagnostic ceiling per active period, without
automatic renewal after expiry. Normal builds default to false. Existing pipeline
snapshots report the build flag and actual `wakeLockHeld` state. This experiment
does not establish battery or hardware improvements without a comparable A/B walk.

The separate [Health Services experiment](recording-health-services.md) is also
off by default. Its successfully owned metrics suppress the corresponding raw
sensor path; unsupported, denied or failed metrics retain raw fallback. GPS,
pressure and hybrid elevation keep their existing owners and algorithms.

Platform contracts: [on-change reporting](https://source.android.com/docs/core/interaction/sensors/report-modes),
[sensor types](https://source.android.com/docs/core/interaction/sensors/sensor-types),
[non-wakeup sensors](https://source.android.com/docs/core/interaction/sensors/suspend-mode),
[health foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#health).
