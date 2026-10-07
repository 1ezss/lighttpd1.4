package com.runtracker.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.location.Location;
import android.os.*;
import com.google.android.gms.location.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Foreground run engine for RunTracker v0.1.4.
 *
 * This service owns the authoritative run state so tracking continues with the screen locked,
 * while Settings is open, while another app is in the foreground, and across Activity recreation.
 * It checkpoints state frequently in SharedPreferences and requests START_STICKY restart.
 */
public class ActiveRunService extends Service implements SensorEventListener {
    public static final String ACTION_START = "com.runtracker.app.START_ACTIVE_RUN";
    public static final String ACTION_PAUSE = "com.runtracker.app.PAUSE_ACTIVE_RUN";
    public static final String ACTION_RESUME = "com.runtracker.app.RESUME_ACTIVE_RUN";
    public static final String ACTION_FINISH = "com.runtracker.app.FINISH_ACTIVE_RUN";
    public static final String ACTION_SETTINGS_CHANGED = "com.runtracker.app.ACTIVE_RUN_SETTINGS_CHANGED";

    public static final String STATE_PREFS = "runtracker_active_run_state";
    private static final String APP_PREFS = "runtracker_prefs";
    private static final String CHANNEL_ID = "runtracker_active_run";
    private static final int NOTIFICATION_ID = 4101;

    private SharedPreferences state;
    private SharedPreferences appPrefs;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (active) {
                checkpoint();
                updateNotification();
                heartbeatHandler.postDelayed(this, 1000L);
            }
        }
    };
    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private Sensor pressureSensor;

    private boolean active;
    private boolean paused;
    private long startEpochMs;
    private long pauseStartedMs;
    private long totalPausedMs;
    private long initialStepCounter = -1L;
    private long currentSteps;
    private double distanceMeters;
    private double calories;
    private double gpsSpeedMps;
    private double stepSpeedMps;
    private long lastGpsMs;
    private float lastGpsAccuracy = Float.NaN;
    private Location previousLocation;

    private final ArrayList<RoutePoint> route = new ArrayList<>();
    private final ArrayDeque<StepSample> stepSamples = new ArrayDeque<>();

    private double pressureBaselineHpa = Double.NaN;
    private double pressureRelativeAltitudeM = 0.0;
    private double pressureAnchorAltitudeM = Double.NaN;

    private Weather weather = new Weather();

    @Override public void onCreate() {
        super.onCreate();
        state = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        createNotificationChannel();
        createLocationCallback();
        restoreCheckpoint();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_START.equals(action)) {
            beginRun();
        } else if (ACTION_PAUSE.equals(action)) {
            pauseRun();
        } else if (ACTION_RESUME.equals(action)) {
            resumeRun();
        } else if (ACTION_FINISH.equals(action)) {
            finishRun();
            return START_NOT_STICKY;
        } else if (ACTION_SETTINGS_CHANGED.equals(action)) {
            if (active) applyTrackingSources();
        } else if (intent == null && active) {
            // Android restarted the process after reclaiming it.
            startForeground(NOTIFICATION_ID, buildNotification());
            registerSensors();
            applyTrackingSources();
            checkpoint();
            heartbeatHandler.removeCallbacks(heartbeat);
            heartbeatHandler.post(heartbeat);
        }

        return active ? START_STICKY : START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeat);
        if (active) checkpoint();
        stopLocationUpdates();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void beginRun() {
        active = true;
        paused = false;
        startEpochMs = System.currentTimeMillis();
        pauseStartedMs = 0L;
        totalPausedMs = 0L;
        initialStepCounter = -1L;
        currentSteps = 0L;
        distanceMeters = 0.0;
        calories = 0.0;
        gpsSpeedMps = 0.0;
        stepSpeedMps = 0.0;
        lastGpsMs = 0L;
        lastGpsAccuracy = Float.NaN;
        previousLocation = null;
        route.clear();
        stepSamples.clear();
        pressureBaselineHpa = Double.NaN;
        pressureRelativeAltitudeM = 0.0;
        pressureAnchorAltitudeM = Double.NaN;
        weather = new Weather();

        startForeground(NOTIFICATION_ID, buildNotification());
        registerSensors();
        applyTrackingSources();
        checkpoint();
        heartbeatHandler.removeCallbacks(heartbeat);
        heartbeatHandler.post(heartbeat);
    }

    private void pauseRun() {
        if (!active || paused) return;
        paused = true;
        pauseStartedMs = System.currentTimeMillis();
        previousLocation = null;
        stopLocationUpdates();
        checkpoint();
        updateNotification();
    }

    private void resumeRun() {
        if (!active || !paused) return;
        totalPausedMs += System.currentTimeMillis() - pauseStartedMs;
        pauseStartedMs = 0L;
        paused = false;
        previousLocation = null;
        stepSamples.clear();
        applyTrackingSources();
        checkpoint();
        updateNotification();
    }

    private void finishRun() {
        if (!active) {
            stopSelf();
            return;
        }
        if (paused && pauseStartedMs > 0L) {
            totalPausedMs += System.currentTimeMillis() - pauseStartedMs;
            pauseStartedMs = 0L;
        }
        active = false;
        paused = false;
        heartbeatHandler.removeCallbacks(heartbeat);
        checkpoint();
        stopLocationUpdates();
        sensorManager.unregisterListener(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void registerSensors() {
        sensorManager.unregisterListener(this);
        if (stepSensor != null &&
            (Build.VERSION.SDK_INT < 29 ||
             checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void applyTrackingSources() {
        stopLocationUpdates();
        if (!active || paused) return;
        if (appPrefs.getBoolean("gps", true) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(1.5f)
                .build();
            locationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        }
    }

    private void createLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) {
                if (!active || paused) return;
                for (Location l : result.getLocations()) processLocation(l);
            }
        };
    }

    private void stopLocationUpdates() {
        if (locationClient != null && locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void processLocation(Location loc) {
        if (loc == null || loc.getAccuracy() > 40f) return;

        long now = System.currentTimeMillis();
        lastGpsMs = now;
        lastGpsAccuracy = loc.getAccuracy();

        if (pressureSensor != null && !Double.isNaN(pressureBaselineHpa) && Double.isNaN(pressureAnchorAltitudeM)) {
            pressureAnchorAltitudeM = loc.hasAltitude() ? loc.getAltitude() : 0.0;
        }

        double altitude = reliableAltitude(loc);
        float speedAcc = (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy())
            ? loc.getSpeedAccuracyMetersPerSecond() : Float.NaN;
        float verticalAcc = (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
            ? loc.getVerticalAccuracyMeters() : Float.NaN;

        RoutePoint current = new RoutePoint(
            loc.getLatitude(), loc.getLongitude(), altitude, now,
            loc.hasSpeed() ? loc.getSpeed() : 0f, speedAcc, verticalAcc, loc.getAccuracy());
        route.add(current);

        if (route.size() == 1) fetchWeatherAsync(current.lat, current.lon);

        if (previousLocation != null) {
            float d = previousLocation.distanceTo(loc);
            RoutePoint prevPoint = route.get(route.size()-2);
            long dtMs = Math.max(1L, current.timeMs - prevPoint.timeMs);

            if (d >= 0.8f && d < 200f) {
                distanceMeters += d;
                double speed = d / (dtMs / 1000.0);
                double grade = 0.0;
                if (!Double.isNaN(prevPoint.alt) && !Double.isNaN(current.alt) && d > 5.0) {
                    grade = (current.alt - prevPoint.alt) / d;
                    if (Math.abs(grade) > 0.35) grade = 0.0;
                }
                calories += Math.max(0.0, calculateCalories(d, speed, grade, dtMs / 60000.0));
            }
        }

        previousLocation = loc;
        gpsSpeedMps = calculateSmoothedGpsSpeed();
        checkpoint();
        updateNotification();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!active || paused) return;
        int type = event.sensor.getType();

        if (type == Sensor.TYPE_STEP_COUNTER) {
            long absolute = (long)event.values[0];
            if (initialStepCounter < 0L) initialStepCounter = absolute;
            long oldSteps = currentSteps;
            currentSteps = Math.max(0L, absolute - initialStepCounter);

            long now = System.currentTimeMillis();
            stepSamples.addLast(new StepSample(now, currentSteps));
            while (!stepSamples.isEmpty() && now - stepSamples.peekFirst().timeMs > 12_000L) {
                stepSamples.removeFirst();
            }
            stepSpeedMps = calculateStepSpeed();

            // In a building or when GPS is weak, keep distance/average pace/calories moving from steps.
            if (!gpsUsableNow() && currentSteps > oldSteps) {
                long deltaSteps = currentSteps - oldSteps;
                double addedDistance = deltaSteps * estimatedStepLengthMeters();
                distanceMeters += addedDistance;
                if (stepSpeedMps > 0.12) {
                    double minutes = Math.max(0.001, addedDistance / stepSpeedMps / 60.0);
                    calories += Math.max(0.0, calculateCalories(addedDistance, stepSpeedMps, 0.0, minutes));
                }
            }

            checkpoint();
            updateNotification();
        } else if (type == Sensor.TYPE_PRESSURE) {
            double pressure = event.values[0];
            if (Double.isNaN(pressureBaselineHpa)) {
                pressureBaselineHpa = pressure;
                pressureRelativeAltitudeM = 0.0;
            } else {
                pressureRelativeAltitudeM = ElevationMath.relativeAltitudeMeters(pressureBaselineHpa, pressure);
            }
            checkpoint();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private double estimatedStepLengthMeters() {
        double heightM = appPrefs.getFloat("height", 175f) / 100.0;
        return Math.max(0.45, Math.min(1.15, heightM * 0.414));
    }

    private double calculateStepSpeed() {
        if (stepSamples.size() < 2) return 0.0;
        StepSample first = stepSamples.peekFirst();
        StepSample last = stepSamples.peekLast();
        long dt = last.timeMs - first.timeMs;
        long ds = last.steps - first.steps;
        if (dt < 2500L || ds < 2L) return 0.0;
        double speed = (ds * estimatedStepLengthMeters()) / (dt / 1000.0);
        return speed >= 0.12 && speed <= 8.0 ? speed : 0.0;
    }

    private boolean gpsUsableNow() {
        long now = System.currentTimeMillis();
        return appPrefs.getBoolean("gps", true)
            && lastGpsMs > 0L
            && now - lastGpsMs <= 8000L
            && !Float.isNaN(lastGpsAccuracy)
            && lastGpsAccuracy <= 25f
            && gpsSpeedMps > 0.12;
    }

    private double calculateSmoothedGpsSpeed() {
        if (route.size() < 2) return 0.0;
        int last = route.size()-1;
        RoutePoint newest = route.get(last);
        int first = last-1;
        while (first > 0 && newest.timeMs - route.get(first).timeMs < 7000L) first--;

        long dt = newest.timeMs - route.get(first).timeMs;
        double pathSpeed = 0.0;
        if (dt >= 2500L) {
            double dist = 0.0;
            float[] out = new float[1];
            for (int i=first+1;i<=last;i++) {
                RoutePoint a = route.get(i-1), b = route.get(i);
                Location.distanceBetween(a.lat,a.lon,b.lat,b.lon,out);
                if (out[0] >= 0.35f && out[0] < 100f) dist += out[0];
            }
            if (dist >= 0.8) pathSpeed = dist / (dt / 1000.0);
        }

        ArrayList<Double> nativeSpeeds = new ArrayList<>();
        for (int i=first;i<=last;i++) {
            RoutePoint p = route.get(i);
            boolean goodAcc = Float.isNaN(p.speedAccuracyMps) || p.speedAccuracyMps <= 1.8f;
            if (goodAcc && p.speedMps >= 0.10f && p.speedMps <= 12f) nativeSpeeds.add((double)p.speedMps);
        }

        double nativeMedian = 0.0;
        if (!nativeSpeeds.isEmpty()) {
            Collections.sort(nativeSpeeds);
            nativeMedian = nativeSpeeds.get(nativeSpeeds.size()/2);
        }

        double speed = (pathSpeed >= 0.12 && nativeMedian >= 0.12)
            ? 0.70*pathSpeed + 0.30*nativeMedian
            : Math.max(pathSpeed, nativeMedian);
        return speed >= 0.12 ? Math.min(speed,12.0) : 0.0;
    }

    private double reliableAltitude(Location loc) {
        if (pressureSensor != null && !Double.isNaN(pressureBaselineHpa) && !Double.isNaN(pressureAnchorAltitudeM)) {
            return pressureAnchorAltitudeM + pressureRelativeAltitudeM;
        }
        if (loc == null || !loc.hasAltitude() || loc.getAccuracy() > 20f) return Double.NaN;
        if (Build.VERSION.SDK_INT >= 26 &&
            (!loc.hasVerticalAccuracy() || loc.getVerticalAccuracyMeters() > 10f)) return Double.NaN;
        double a = loc.getAltitude();
        return (a >= -500 && a <= 7000) ? a : Double.NaN;
    }

    private double calculateCalories(double distanceM, double speedMps, double grade, double minutes) {
        double kg = appPrefs.getFloat("weight", 70f);
        grade = Math.max(-0.45, Math.min(0.45, grade));
        if (speedMps <= 0.05 || distanceM <= 0.0) return 0.0;

        if (grade >= 0.0) {
            double vMMin = speedMps * 60.0;
            double activeVo2 = 0.2*vMMin + 0.9*vMMin*grade;
            return (activeVo2 * kg / 1000.0) * 5.0 * minutes;
        }

        double i = grade;
        double costJkgm = 155.4*Math.pow(i,5)-30.4*Math.pow(i,4)-43.3*Math.pow(i,3)
            +46.3*Math.pow(i,2)+19.5*i+3.6;
        return Math.max(0.0, costJkgm) * kg * distanceM / 4184.0;
    }

    private void fetchWeatherAsync(double lat, double lon) {
        new Thread(() -> {
            try {
                String u = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms",
                    lat,lon);
                HttpURLConnection c = (HttpURLConnection)new URL(u).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                weather.temperatureC = cur.optDouble("temperature_2m", Double.NaN);
                weather.humidity = cur.optDouble("relative_humidity_2m", Double.NaN);
                weather.windSpeedMs = cur.optDouble("wind_speed_10m", 0.0);
                weather.windDirectionDeg = cur.optDouble("wind_direction_10m", 0.0);
                weather.valid = true;
                checkpoint();
            } catch (Exception ignored) {}
        }).start();
    }

    private long activeElapsedMs() {
        if (startEpochMs <= 0L) return 0L;
        long now = System.currentTimeMillis();
        long ongoingPause = paused && pauseStartedMs > 0L ? now - pauseStartedMs : 0L;
        return Math.max(0L, now - startEpochMs - totalPausedMs - ongoingPause);
    }

    private void checkpoint() {
        try {
            JSONArray a = new JSONArray();
            for (RoutePoint p : route) a.put(p.toJson());

            boolean gps = gpsUsableNow();
            double liveSpeed = gps ? gpsSpeedMps : stepSpeedMps;
            String source = gps ? "gps" : (stepSpeedMps > 0.12 ? "steps" : "none");

            state.edit()
                .putBoolean("active",active)
                .putBoolean("paused",paused)
                .putLong("start",startEpochMs)
                .putLong("pause_started",pauseStartedMs)
                .putLong("total_paused",totalPausedMs)
                .putLong("elapsed_ms",activeElapsedMs())
                .putLong("initial_step_counter",initialStepCounter)
                .putLong("steps",currentSteps)
                .putFloat("distance_m",(float)distanceMeters)
                .putFloat("calories",(float)calories)
                .putFloat("gps_speed_mps",(float)gpsSpeedMps)
                .putFloat("step_speed_mps",(float)stepSpeedMps)
                .putFloat("live_speed_mps",(float)liveSpeed)
                .putString("pace_source",source)
                .putLong("last_gps_ms",lastGpsMs)
                .putFloat("last_gps_accuracy",lastGpsAccuracy)
                .putFloat("pressure_baseline",(float)pressureBaselineHpa)
                .putFloat("pressure_relative_alt",(float)pressureRelativeAltitudeM)
                .putFloat("pressure_anchor_alt",(float)pressureAnchorAltitudeM)
                .putString("route",a.toString())
                .putBoolean("weather_valid",weather.valid)
                .putFloat("temperature_c",(float)weather.temperatureC)
                .putFloat("humidity",(float)weather.humidity)
                .putFloat("wind_ms",(float)weather.windSpeedMs)
                .putFloat("wind_dir",(float)weather.windDirectionDeg)
                .apply();
        } catch (Exception ignored) {}
    }

    private void restoreCheckpoint() {
        active = state.getBoolean("active",false);
        paused = state.getBoolean("paused",false);
        startEpochMs = state.getLong("start",0L);
        pauseStartedMs = state.getLong("pause_started",0L);
        totalPausedMs = state.getLong("total_paused",0L);
        initialStepCounter = state.getLong("initial_step_counter",-1L);
        currentSteps = state.getLong("steps",0L);
        distanceMeters = state.getFloat("distance_m",0f);
        calories = state.getFloat("calories",0f);
        gpsSpeedMps = state.getFloat("gps_speed_mps",0f);
        stepSpeedMps = state.getFloat("step_speed_mps",0f);
        lastGpsMs = state.getLong("last_gps_ms",0L);
        lastGpsAccuracy = state.getFloat("last_gps_accuracy",Float.NaN);

        pressureBaselineHpa = state.getFloat("pressure_baseline",Float.NaN);
        pressureRelativeAltitudeM = state.getFloat("pressure_relative_alt",0f);
        pressureAnchorAltitudeM = state.getFloat("pressure_anchor_alt",Float.NaN);

        weather.valid = state.getBoolean("weather_valid",false);
        weather.temperatureC = state.getFloat("temperature_c",Float.NaN);
        weather.humidity = state.getFloat("humidity",Float.NaN);
        weather.windSpeedMs = state.getFloat("wind_ms",0f);
        weather.windDirectionDeg = state.getFloat("wind_dir",0f);

        route.clear();
        try {
            JSONArray a = new JSONArray(state.getString("route","[]"));
            for (int i=0;i<a.length();i++) route.add(RoutePoint.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}

        if (!route.isEmpty()) previousLocation = route.get(route.size()-1).toLocation();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Active run", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps RunTracker recording while the screen is off or another app is open.");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        long sec = activeElapsedMs()/1000L;
        String text = String.format(Locale.US,"%s · %02d:%02d · %.2f km",
            paused ? "Paused" : "Run in progress",
            (sec%3600)/60, sec%60, distanceMeters/1000.0);

        Intent openApp = new Intent(this,MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            this,0,openApp,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this,CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("RunTracker")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build();
    }

    private void updateNotification() {
        if (!active) return;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, buildNotification());
    }

    static class StepSample {
        final long timeMs, steps;
        StepSample(long timeMs,long steps){this.timeMs=timeMs;this.steps=steps;}
    }

    static class RoutePoint {
        final double lat,lon,alt;
        final long timeMs;
        final float speedMps,speedAccuracyMps,verticalAccuracyM,horizontalAccuracyM;

        RoutePoint(double lat,double lon,double alt,long timeMs,float speedMps,
                   float speedAccuracyMps,float verticalAccuracyM,float horizontalAccuracyM) {
            this.lat=lat;this.lon=lon;this.alt=alt;this.timeMs=timeMs;this.speedMps=speedMps;
            this.speedAccuracyMps=speedAccuracyMps;this.verticalAccuracyM=verticalAccuracyM;
            this.horizontalAccuracyM=horizontalAccuracyM;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o=new JSONObject();
            o.put("lat",lat);o.put("lon",lon);
            if(!Double.isNaN(alt)&&!Double.isInfinite(alt))o.put("alt",alt);else o.put("alt",JSONObject.NULL);
            o.put("t",timeMs);o.put("speed",speedMps);
            if(!Float.isNaN(speedAccuracyMps))o.put("speed_acc",speedAccuracyMps);
            if(!Float.isNaN(verticalAccuracyM))o.put("vertical_acc",verticalAccuracyM);
            o.put("horizontal_acc",horizontalAccuracyM);
            return o;
        }

        static RoutePoint fromJson(JSONObject o) {
            return new RoutePoint(
                o.optDouble("lat",0),o.optDouble("lon",0),
                o.isNull("alt")?Double.NaN:o.optDouble("alt",Double.NaN),
                o.optLong("t",0),(float)o.optDouble("speed",0),
                (float)o.optDouble("speed_acc",Float.NaN),
                (float)o.optDouble("vertical_acc",Float.NaN),
                (float)o.optDouble("horizontal_acc",Float.NaN));
        }

        Location toLocation() {
            Location l=new Location("RunTrackerRestore");
            l.setLatitude(lat);l.setLongitude(lon);l.setTime(timeMs);
            return l;
        }
    }

    static class Weather {
        boolean valid=false;
        double temperatureC=Double.NaN,humidity=Double.NaN,windSpeedMs=0.0,windDirectionDeg=0.0;
    }
}
