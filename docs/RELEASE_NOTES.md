# RunTracker v0.1.4 Release Notes

Version: 0.1.4  
versionCode: 5  
Package: com.runtracker.app  
Required release SHA-1: DB:02:53:02:33:50:38:80:26:CA:51:BD:F1:BC:2F:0C:8B:38:83:50

## Background tracking
- Added ActiveRunService as a location foreground service.
- Active runs continue with the screen off/locked.
- Active runs continue while Settings, another Activity, or another application is in the foreground.
- The service checkpoints run state every second and requests START_STICKY restart.
- Android Force stop remains terminal by platform design.

## Settings round-trip
- Fixed the bug where returning from Settings during an active run incorrectly re-enabled START.
- Controls are reconstructed from the authoritative run state.
- START stays disabled; PAUSE/RESUME and FINISH remain available.

## Pace without reliable GPS
- Added current-pace fallback from step cadence and a height-derived step-length estimate.
- Step-derived distance and calories continue when GPS is weak or unavailable.
- The UI identifies step-derived pace.

## Elevation
- Prefer barometric pressure for relative elevation when a pressure sensor is available.
- Keep GPS altitude as fallback/anchor with strict accuracy checks.
- Added median smoothing for isolated altitude spikes.
- Added elevation-gain hysteresis.
- Added deterministic tests for a small climb, GPS spike rejection and flat noisy traces.

## Profile photo
- Profile photo can be added or replaced.
- Tapping the profile photo opens the picker.

## Google Maps configuration
The previous authorization failure was resolved by explicitly enabling Maps SDK for Android in the selected Google Cloud project. The release checklist now verifies API enablement in addition to key restrictions.

## Automated QA
- Elevation tests run before release APK assembly.
- v0.1.4 uses versionCode 5.
- UI QA workflow remains part of branch validation.

## Signing
v0.1.4 must be signed with exactly the same private key as v0.1.3. A new key is not acceptable.

Expected SHA-1:
DB:02:53:02:33:50:38:80:26:CA:51:BD:F1:BC:2F:0C:8B:38:83:50

## Physical-device validation still required
- Lock/unlock continuity.
- Several minutes with another app in foreground.
- Small real stair climb for pressure-sensor validation.
- Indoor/no-GPS pace against known cadence/distance.
- Profile-photo replacement.
