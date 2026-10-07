# RunTracker Architecture

Document version: 0.1.4
Application package: com.runtracker.app
Android version: versionCode 5 / versionName 0.1.4
Required release signing SHA-1: DB:02:53:02:33:50:38:80:26:CA:51:BD:F1:BC:2F:0C:8B:38:83:50

## Core rule
An active run must not depend on MainActivity being visible. RunTracker v0.1.4 uses a location foreground service as the authoritative run engine so tracking can continue with the screen locked, Settings open, another Activity open, or another application in the foreground.

## MainActivity
MainActivity renders Home, Settings, History, Run Summary and Debug/Logs. During an active run it reads the foreground-service checkpoint and refreshes the UI. START, PAUSE, RESUME and FINISH are commands to the run engine rather than independent Activity state.

## ActiveRunService
ActiveRunService owns:
- run lifecycle and timing;
- FusedLocationProviderClient updates;
- TYPE_STEP_COUNTER;
- pressure sensor / barometric elevation;
- total distance;
- current pace source;
- calories;
- route points;
- weather snapshot;
- one-second and event-driven state checkpoints.

The service runs as a foreground location service with a persistent low-priority notification and requests START_STICKY restart. Android Force stop remains terminal by platform design.

## Pace
Preferred current-pace source is rolling GPS displacement plus reliable Android native speed. When GPS is stale, unavailable or inaccurate, current pace falls back to step cadence multiplied by an estimated step length derived from configured height. The UI identifies step-derived pace.

Average pace is total active distance divided by active elapsed time. During GPS loss, step-derived distance can continue the distance and calorie estimates instead of freezing.

## Elevation
When a pressure sensor exists, barometric relative altitude is preferred for elevation change. GPS altitude remains a fallback/anchor and is accepted only with adequate horizontal and vertical accuracy.

ElevationMath provides testable helpers for:
- barometric pressure to relative altitude;
- median smoothing of isolated spikes;
- positive-gain hysteresis.

Deterministic tests cover a roughly 4 m climb, GPS spike rejection, flat noisy data and preservation of a real small climb.

## Settings and profile
Settings include GPS, language, pace/speed mode, distance units, weight, height, target weight, avatar and profile photo. Profile photo can be added or replaced, including by tapping the displayed photo.

Saving Settings during an active run must preserve the same run. START stays disabled and PAUSE/RESUME plus FINISH reflect the authoritative service state.

## Google Maps
Run summary maps use Maps SDK for Android. Tracking itself does not depend on map tiles.

Required Google Cloud checklist:
- Billing attached to the correct project.
- Maps SDK for Android explicitly enabled under APIs & Services.
- API key belongs to the same project.
- Application restriction: Android apps.
- Package: com.runtracker.app.
- Release SHA-1: DB:02:53:02:33:50:38:80:26:CA:51:BD:F1:BC:2F:0C:8B:38:83:50.
- API restriction: Maps SDK for Android only.

## Persistence
v0.1.4 uses SharedPreferences/JSON for settings, active-run checkpoints, history, profile-photo URI, avatar and debug logs. The active run is checkpointed every second and after sensor/location events.

## Signing
v0.1.4 must use exactly the same private signing key as the installed v0.1.3. A new key is not acceptable because it would prevent an in-place Android update and change the Google Maps Android-app restriction.

The release keystore must be backed up securely outside the public repository. API keys and signing private keys must not be committed.

## QA gates
- Unit tests pass before APK assembly.
- Release build compiles as 0.1.4 / versionCode 5.
- APK signing certificate SHA-1 exactly matches v0.1.3.
- START / PAUSE / RESUME / FINISH survives a Settings round-trip.
- Active run continues through lock/unlock and while another app is foreground.
- Activity recreation restores state.
- Weak/no GPS uses step-derived pace and distance.
- Barometric elevation is checked on a real small stair climb.
- Profile-photo replacement works.
- Google Maps loads tiles and route.
- History save/open/delete remains functional.
