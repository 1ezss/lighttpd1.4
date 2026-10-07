# RunTracker Architecture

Document version: 0.1.3-draft
Application package: com.runtracker.app
Default language: English
Current Android prototype line: v0.1.x

## Purpose
RunTracker is an Android running-tracker application that records a run in real time, stores run history locally, and presents a post-run summary including route, pace, steps, calories, weather context, elevation and pace graphs, ratings, notes and optional photos.

## Versioning policy
RunTracker uses an incrementing semantic-style version number. Patch releases in the 0.1.x line are corrective prototype releases. The application version is displayed on the main screen. Every APK must increment versionCode and versionName.

## Android platform
- Application ID: com.runtracker.app
- Minimum SDK: 26
- Target SDK: 35
- Compile SDK: 35
- Java compatibility: 17
- UI in v0.1.x: native Android Views created programmatically
- Google Play services: Maps SDK for Android and Fused Location Provider

## Current v0.1.x architecture
Most prototype behavior is currently implemented in app/src/main/java/com/runtracker/app/MainActivity.java. This class owns UI creation, settings, run state, GPS processing, step counting, weather retrieval, calorie computation, history persistence, map display, graph rendering and debug logging. This compact structure is acceptable for prototype validation but should be refactored for the v0.2.x milestone.

## UI screens
### Main screen
Displays application version, elapsed time, distance, average pace or speed, current pace or speed, steps, calories, START / PAUSE / FINISH, Settings access and Run History.

### Settings
Automatically opens on first launch and contains GPS enable/disable, language, pace/speed mode, kilometer/mile units, profile photo, avatar, weight, height, target weight, Debug / Logs and Save Settings.

### Run History
Lists saved runs and allows open or delete.

### Run Summary
Contains date/time, Google Maps route, distance, duration, average pace, steps, calories, weather, optional image, difficulty, route beauty, notes, elevation graph when reliable, and pace-by-distance graph.

## Run lifecycle
IDLE -> RUNNING -> PAUSED -> RUNNING -> FINISHED. Paused time is excluded from active duration.

## Location and route tracking
RunTracker uses FusedLocationProviderClient with high-accuracy requests. Each accepted route point stores latitude, longitude, timestamp, optional altitude, native speed, horizontal accuracy, vertical accuracy when available and speed accuracy when available. Basic filtering rejects unusable positions and impossible jumps.

GPS is not reliable enough for meaningful short-distance indoor pace or elevation. A future indoor/treadmill mode should use cadence, steps and calibrated stride length instead of GPS.

## Pace
Average pace is total accepted distance divided by active run duration. Current pace uses a rolling GPS window and may combine recent route displacement with Android native speed when that estimate is reliable. If GPS quality is insufficient, the UI should show a weak-GPS state rather than a misleading pace.

## Steps
The Android TYPE_STEP_COUNTER sensor is used when available. The absolute counter is converted into run-relative steps by subtracting the start value.

## Elevation
Altitude is accepted only when GPS quality is sufficient. The application rejects or suppresses elevation data when altitude is unavailable, horizontal or vertical accuracy is poor, values are physically implausible, or large altitude excursions occur over very short routes. This prevents fictitious climbing profiles indoors.

## Weather
At run start, a weather snapshot is retrieved from Open-Meteo using the first accepted route position. Temperature, relative humidity, wind speed and wind direction are stored. Weather failure must not block a run.

## Calories
Calories are computed segment by segment from runner weight, GPS distance, speed, gradient when reliable, running-energy equations for flat/uphill movement, a downhill running-cost model and a wind-drag correction.

## Google Maps integration
RunTracker uses Maps SDK for Android only for route presentation. GPS tracking itself is independent of Google Maps.

Required Google Cloud configuration:
- Application restriction: Android apps
- Package name: com.runtracker.app
- Release signing certificate SHA-1
- API restriction: Maps SDK for Android only

The Google Maps API key must not be committed in clear text. Starting with v0.1.3, the release build must inject the real key at build time. Debug logs must never print the full key.

## Signing
All releases from v0.1.2 onward are intended to use one permanent RunTracker release keystore.
Current release certificate SHA-1:
22:8E:4E:A5:7B:95:0D:40:AD:E8:11:FD:FC:79:84:5E:F2:AF:83:5A

The keystore must be backed up securely. Changing it prevents normal Android updates and requires Google Maps restriction changes.

## Persistence
v0.1.x uses SharedPreferences and JSON for settings, profile-photo URI, avatar, run history and debug logs. Planned v0.2.x migration: Room for run history and DataStore/Preferences for settings.

## Localization
English is default. The current prototype uses a runtime translation helper for English, French and Hebrew. Production should migrate strings to Android resource folders for proper localization and RTL support.

## Debugging and logs
The in-app DEBUG / LOGS screen records settings changes, run lifecycle events, weather status, Maps initialization, map-ready callbacks and selected authorization diagnostics. Sensitive values must be redacted.

## Charts
v0.1.x uses a custom RunChartView for elevation-by-distance and pace-by-distance, with axes, units, grid lines and numeric graduations. Future work should extract calculations from the View so they are unit-testable.

## Recommended v0.2.x modular architecture
UI layer: screens/fragments or Compose plus ViewModels.
Domain layer: RunSessionManager, PaceCalculator, DistanceCalculator, CalorieCalculator, ElevationFilter, RunSummaryBuilder.
Data/service layer: LocationTracker, StepCounterService, WeatherRepository, RunRepository, SettingsRepository, MapsConfiguration, Room/DataStore.

## Background tracking
A production running app should use an Android foreground service with an ongoing notification and lifecycle-safe run state so recording continues with screen off/background. The v0.1.x prototype remains foreground-testing software until this is implemented and validated.

## QA release gates
1. Release build succeeds.
2. APK signature verified.
3. Version displayed matches intended release.
4. First-launch Settings screen verified on emulator/device.
5. All Settings fields have visible labels.
6. START / PAUSE / FINISH tested.
7. Outdoor GPS validates distance, average pace and current pace.
8. Weak indoor GPS does not create misleading elevation.
9. Google Maps displays actual tiles and route polyline.
10. Maps logs report successful loading.
11. History save/open/delete works.
12. Summary graphs have readable axes and units.
13. No API key or signing secret is exposed.

## v0.1.3 status
Required fixes:
- inject the real Maps API key securely at build time;
- verify Maps SDK end-to-end before delivery;
- retain the permanent signing certificate;
- keep Settings labels visible;
- retain elevation filtering;
- validate current pace with realistic outdoor GPS;
- execute emulator UI QA before delivery.
