# Wear Transfer Service Architecture

This document describes the watch-side file transfer stack in `:app`.

## Scope

Main entrypoint:

- `app/src/main/java/com/glancemap/glancemapwearos/core/service/DataLayerListenerService.kt`

The stack is responsible for:

- receiving transfer commands from the phone via Data Layer messages,
- receiving file payloads over LAN HTTP or Wear channels,
- persisting files safely on watch storage,
- reporting transfer state (`status` / `ack`) back to the phone,
- keeping transfers resilient with wake/Wi-Fi locks and resume-aware behavior.

## Package Layout

Main package:

- `app/src/main/java/com/glancemap/glancemapwearos/core/service/transfer`
- `app/src/main/java/com/glancemap/glancemapwearos/core/service/transfer/session`
- `transfercontract/src/main/kotlin/com/glancemap/shared/transfer`

Key components:

- `DataLayerListenerService.kt`
  - Android service boundary. Wires dependencies and forwards events only.

- `transfer/session/DataLayerHandlers.kt`
  - Thin dispatcher between message events and channel-open events.

- `transfer/session/DataLayerMessageRequestHandler.kt`
  - Handles message-path dispatch and lightweight requests (`cancel`, `check exists`, prewarm).

- `transfer/session/DataLayerWifiTransferRequestHandler.kt`
  - Handles `start wifi` request validation + transfer kickoff.

- `transfer/session/DataLayerSmallFileRequestHandler.kt`
  - Handles small-payload file saves from Data Layer messages.

- `transfer/session/DataLayerChannelOpenedHandler.kt`
  - Handles channel-open file transfers.

- `transfer/session/TransferRunner.kt`
  - Orchestrates HTTP transfer lifecycle (start, verify, finalize).

- `transfer/session/HttpTransferProgressCallbacks.kt`
  - Owns transfer progress/status callback behavior and UI throttling.

- `transfer/session/HttpTransferResultNotifier.kt`
  - Owns success/cancel/error finalization notifications and status/ack emission.

- `transfer/HttpTransferStrategy.kt`
  - HTTP orchestration boundary.

- `transfer/HttpTransferNetworkSession.kt`
  - Wi-Fi acquisition, network binding, reconnect checks, and cleanup.

- `transfer/HttpTransferConnectionLoop.kt`
  - HTTP probe + retry/resume download loop.

- `transfercontract/TransferDataLayerContract.kt`
  - Shared Data Layer paths/capability contract used by watch + companion modules.

- `transfer/session/WatchFileOps.kt`
  - Storage writes, file existence checks, file hashing, sanitization.

- `transfer/session/TransferSessionState.kt`
  - Active transfer id/job state.

- `transfer/session/TransferLockManager.kt`
  - Wake/Wi-Fi lock acquire/release policy.

## Runtime Flow

1. Phone sends Data Layer message to watch.
2. `DataLayerListenerService` forwards to `DataLayerHandlers`.
3. `DataLayerMessageRequestHandler` validates payload and decides transfer mode.
4. For HTTP mode, `TransferRunner` runs the session:
   - acquires locks,
   - starts foreground transfer notification,
   - delegates transport to `HttpTransferStrategy`,
   - updates progress through `HttpTransferProgressCallbacks`,
   - verifies checksum (if provided),
   - finalizes via `HttpTransferResultNotifier`.
5. For channel mode, `DataLayerChannelOpenedHandler` receives stream and finalizes ack.
6. Service always releases locks and clears active transfer state in `finally` paths.

Channel file integrity is checked at both ends. The phone hashes the source and copies it to the
channel with exact byte-count checks whenever source size is known. Its prewarm message carries the
declared size and SHA-256; the watch consumes both with the matching channel handoff. The receiver
passes them to `AtomicStreamWriter`, which validates the staged byte count and checksum before
promoting the `.part` file. A missing required checksum, short/long payload, unavailable digest, or
mismatch fails the transfer, removes the staged file, and leaves any existing destination intact.
Only after the validated write has been promoted does the channel handler claim `DONE` and send the
terminal acknowledgement. Channel map transfers also reject absent or non-positive size metadata.

## Standalone DEM and screen-off downloads

Standalone map DEM downloads are owned by `StandaloneDemDownloadForegroundService`
(`dataSync`), rather than the UI ViewModel scope. The ViewModel still publishes the existing
progress/readiness state and owns the unchanged tile-selection, validation, and partial-file resume
logic. The service retains the active map path for a system restart; explicit cancellation and
foreground-service timeout clear that request. A timeout leaves partial files available for retry.

`DownloadNetworkLease` retains a preferred Wi-Fi request for the operation, including when Wi-Fi
was already connected, and observes the app default network for fallback. Both standalone and bundle
metadata/file HTTP connections use the selected Network without binding the entire process.
Only validated internet networks are eligible; captive Wi-Fi cannot displace working Bluetooth or
cellular internet. An active default VPN retains routing priority. Closing the lease releases all
callbacks; late callbacks from an earlier generation cannot repopulate a restarted lease.

Low-latency Wi-Fi locks are not screen-off protection: Android only activates that mode while the
screen is on. The standalone DEM path uses its foreground owner, bounded CPU wake lock, and retained
network requests instead. Bluetooth/cellular internet remains usable when Wi-Fi is unavailable.

## OAM network handoff

A default-network Wi-Fi event is telemetry, not evidence that an existing HTTP connection moved.
The foreground bundle service requests a targeted handoff only after its retained-network selector
reaches validated Wi-Fi. The ViewModel-owned refresh path requests the same handoff after default
Wi-Fi recovery. Initial Wi-Fi and repeated validated callbacks do not request reconnects; a real
loss/recovery rearms the transition. Continue-without-Wi-Fi permits a fallback start, not a permanent
Bluetooth route. VPN routing priority remains intact.

`OamActiveConnectionRegistry` marks/disconnects resumable payloads only; metadata, Refuges.info and
extraction are excluded. A generation covers connections being opened during the handoff. Checked
reads turn a deliberate disconnect/EOF into a planned IOException. The existing atomic writer
settles its pipeline and retains partial bytes before the retry reads the actual partial length.
User cancellation takes precedence. Planned handoffs consume no ordinary no-progress retry and
skip failure backoff; genuine I/O failures retain their bounded retry policy. Available strong
ETag/Last-Modified validators are sent with If-Range within the transfer. HTTP 206 appends, while
HTTP 200/416 on a resumed request retains the existing safe full-restart path. A server without a
validator retains the pre-existing cross-request entity-consistency limitation.

Telemetry separates `http_attempt` (connection identity, attempt, offset, cause), `http_retry`
(planned Wi-Fi handoff versus I/O failure and budget), and `http_network_selected` (the service
factory-selected Network handle, transport and validation). Existing monitor events describe the
logical default network. Refresh connections use the system default; their precise socket Network
is not established by a monitor snapshot. No URLs, credentials or network names are added.

## Bundle progress presentation

The shared progress arbiter keeps map/POI extraction progress cached while the pipeline continues
downloading other components, including gaps between individual transfers. Both the download UI
and foreground notification show network progress during this overlap. At the existing map/POI
await points, remaining extraction becomes visible immediately at its latest byte count and then
continues updating. An extraction that already finished is not replayed. Progress selection and
publication share a short lock so background callbacks cannot publish a stale selection out of order.
Download/extraction scheduling, cancellation and archive reuse are unchanged.

## Bundle deletion feedback

Bundle deletion retains the shared destructive confirmation dialog. Confirmation immediately hides
the bundle through a pending area ID; it adds no progress or success message. The filesystem cleanup
still runs on IO, preserves shared routing/DEM assets, and verifies the intended file candidates are
gone before removing the bundle record. A failure reloads the remaining files/health and restores
the bundle with an error. Downloads, refreshes and further deletes are disabled during cleanup.
Library refreshes discard snapshots from before a completed deletion, so stale data cannot restore
the deleted row. Successful deletion removes just that bundle's cached health without rescanning
every remaining bundle.

## Files exported from watch to phone

Received GPX and diagnostics files share `WatchFileExportNotifier`: keep an app-owned source,
try saving to Downloads/GlanceMap, and post Save copy, Open and Share actions with the file's MIME
type. Diagnostics receipt no longer launches a share/email chooser automatically. Its Share action
preserves the support address, subject and truncation note. Both file types reuse the existing
generated-file Save copy activity entry point.

## Screen-off extraction diagnostics

The connected OnePlus Watch 3 system log confirms Balance Mode forcibly removes GlanceMap's
application partial wake lock on screen-off despite dataSync FGS=true. Standard LPS is disabled;
Doze is concurrent but does not explain the vendor's explicit forced-release action.

On API 31+, local map/POI ZIP extraction now runs inside an expedited OamExtractionJobService,
whose execution wake lock is owned by JobScheduler. Downloads, network handoff, the foreground
notification/plan and AtomicStreamWriter remain in their existing paths. API 28–30 stays inline.
The coordinator permits one extraction, rejects orphan/stale request tokens, and joins the writer
before owner cleanup. Rejected/downgraded/stopped jobs return the localized retry error without
automatic rescheduling; foreground plan recovery remains the only restart authority. See
[investigation, quota tradeoffs and ADB controls](oam-screen-off-extraction-investigation.md).

Debug capture adds API-guarded power/process samples (at most once per five seconds) and API-33
system wake-lock callbacks capped at 16 transition logs per generation. Stale/released callbacks
cannot repopulate state. Unsupported or failed queries use `na`; app-owned foreground/type fields
are observations, not proof of system state. No screen hold or permanent exemption is requested.

## Manual partial-file cleanup

Settings → Clear partial files counts and deletes hidden `.part` files at the existing transfer,
map/POI/GPX, routing, DEM and theme staging roots, plus completed `.map.zip`/`.poi.zip` archives
directly inside private `oam_downloads`. These archives are retained after interrupted bundle installs;
clearing them forces the next attempt to download again. Installed files and arbitrary ZIPs outside
that staging directory are excluded. Automatic seven-day cleanup remains hidden-`.part` only.

Bundle operations, standalone DEM jobs and registered phone transfer jobs protect cleanup until
their worker jobs, including cancellation cleanup/children, complete. Cleanup skips in-use files
with a user message. Registration and each deletion share a short lock to prevent a new operation
starting between the in-use check and unlink; directory traversal happens outside that lock.

## Contributor Rules

- Keep `DataLayerListenerService.kt` orchestration-only. No protocol logic in service callbacks.
- Add new message-path behavior in `DataLayerMessageRequestHandler.kt` (or dedicated handler files) instead of bloating `DataLayerHandlers.kt`.
- Keep protocol/network concerns in strategy classes (`HttpTransferStrategy.kt`, channel strategy).
- Keep file-system behavior in `WatchFileOps.kt`.
- Keep transfer result/reporting semantics centralized in notifier-style helpers to avoid status/ack drift.
