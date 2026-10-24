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

The step counter produces a session total relative to its cumulative baseline;
the detector contributes only to a 30-second cadence window. Counter deltas
also derive cadence, sharing the cadence field with detector updates. These
derivations currently use callback arrival times, not source measurement times.

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
callback (the detector has no value filter), not that the measurement is fresh
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

Platform contracts: [on-change reporting](https://source.android.com/docs/core/interaction/sensors/report-modes),
[sensor types](https://source.android.com/docs/core/interaction/sensors/sensor-types),
[non-wakeup sensors](https://source.android.com/docs/core/interaction/sensors/suspend-mode),
[health foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#health).
