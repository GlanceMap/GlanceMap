# Missing map prompt

Navigate offers map downloads only after a current accepted location is available
and the installed map library has completed its scan. Coverage is checked against
every installed map, including inactive maps. Bounds loading, unreadable bounds,
and a location on an installed map boundary suppress the prompt. Map header bounds
are rectangular, so this deliberately prefers suppressing a prompt when detailed
coverage is uncertain.

The library publishes a single nullable coverage snapshot: null means scanning,
an empty list means no installed maps, and a null entry means unknown coverage.
Refreshes clear this snapshot immediately; only the current scan generation may
publish new bounds. This avoids interpreting initial/temporary empty lists as a
missing map or using a previous map revision during replacement.

The prompt waits while Navigate is inactive, ambient, offline, recording, guiding,
or busy with another workflow/dialog. It does not change GPS requests, map
selection, recording, or routing. A later eligible location update can show it.

Download bundle opens the existing download area picker and seeds nearby bundle
suggestions from the accepted location; it does not start a download. Ordinary
menu entry continues to open the download overview. Not now, Back, and opening
downloads dismiss the prompt for the current app session through MapViewModel.
Never show again also persists the disabled choice in settings DataStore; the
prompt stays suppressed until that preference is loaded.

Validation on a real watch should cover no maps, maps for other regions, a
covering inactive map, cold start with slow metadata, map installation/replacement,
returning from downloads, small-screen action scrolling, and persistent opt-out
after restarting the app.
