# Phone map visual motion

The phone's 2D Mapsforge and MapLibre maps use the watch's shared visual trajectory and cadence model for display only. A fresh, sufficiently accurate fix with reliable speed and bearing can move the marker between updates; the trajectory eases to a stop after the expected fix interval and has a 12-second maximum horizon. Successive nearby fixes blend into the displayed trajectory. Missing, old or uncertain motion data does not produce predicted motion. The trajectory resets when the map becomes inactive, GPS is disabled or location ownership changes.

The map camera and marker consume the same displayed position. Recording, turn-by-turn guidance and live tracking retain their original location data and request cadence. The 3D terrain renderer continues to use the actual fix. Motion frames run only while prediction or correction is active; follow mode updates at 100 ms and free mode at 250 ms.

Device testing is required to assess visual smoothness, GPS behavior in motion and power use; no battery saving is assumed.
