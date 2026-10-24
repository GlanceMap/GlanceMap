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

## Contributor Rules

- Keep `DataLayerListenerService.kt` orchestration-only. No protocol logic in service callbacks.
- Add new message-path behavior in `DataLayerMessageRequestHandler.kt` (or dedicated handler files) instead of bloating `DataLayerHandlers.kt`.
- Keep protocol/network concerns in strategy classes (`HttpTransferStrategy.kt`, channel strategy).
- Keep file-system behavior in `WatchFileOps.kt`.
- Keep transfer result/reporting semantics centralized in notifier-style helpers to avoid status/ack drift.
