# Watch route sync (phone side)

The watch sends finished GPX recordings and routes to the phone over a Wear
channel whose path carries a transfer id, the file's SHA-256 and its sanitized
name. `WatchGpxExportListenerService` writes the stream to a temporary file in
the GlanceMap storage folder (`watch-gpx-exports`, which follows the internal or
SD card setting), and rejects it when the checksum does not match.

A verified file is imported straight into the route library with
`RouteLibraryRepository.importWatchRoute`. The route's export id is
`watch:<sha256>`, so a file the watch sends again is recognised and not stored
twice. GlanceMap recording metadata marks the route as a recording; any other
GPX becomes a planned route. Every route with an export id is labelled
"From watch", including routes imported by hand before automatic import.

After a successful import the received copy is deleted, so the route library
holds the only copy. Files are no longer copied to Downloads; the notification's
Save copy action exports one on request. If import fails, the received file is
kept and appears in the Library for a manual import.

One notification covers every route added while it stays open, counting them in
its extras. `RouteLibraryUpdates` tells open screens to reload when a background
receive changes the library.

Sending from the watch is still started by the user. The automatic watch-side
queue and `mycreation` POI sync are not part of this flow yet.
