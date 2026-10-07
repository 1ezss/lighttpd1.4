package com.runtracker.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.*;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import com.google.android.gms.location.*;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import org.json.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * RunTracker v0.1.1
 * Single-activity first working build. The code is intentionally explicit and heavily named
 * so it can later be split into ViewModel / repository / persistence modules without ambiguity.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_PROFILE_PHOTO = 101;
    private static final int REQ_RUN_PHOTO = 102;
    private static final String PREFS = "runtracker_prefs";
    private static final String HISTORY_KEY = "run_history_json";

    private SharedPreferences prefs;
    private LinearLayout root;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private SensorManager sensorManager;
    private Sensor stepSensor;

    private boolean running = false;
    private boolean paused = false;
    private long startEpochMs = 0L;
    private long pauseStartedMs = 0L;
    private long totalPausedMs = 0L;
    private long initialStepCounter = -1;
    private long currentSteps = 0;
    private double totalDistanceMeters = 0.0;
    private double totalCalories = 0.0;
    private Location previousLocation;
    private final ArrayList<RoutePoint> route = new ArrayList<>();
    private TextView timeValue, distanceValue, avgPaceValue, currentPaceValue, stepsValue, caloriesValue;
    private Button startButton, pauseButton, finishButton;
    private WeatherSnapshot weather = new WeatherSnapshot();
    private String pendingRunPhotoUri = "";
    private JSONObject currentSummaryRun;
    private double currentSmoothedSpeedMps = 0.0;

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (running) {
                updateLiveMetrics();
                timerHandler.postDelayed(this, 500);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        createLocationCallback();
        showHome();
        if (!prefs.getBoolean("settings_saved_once", false)) {
            new Handler(Looper.getMainLooper()).postDelayed(this::showSettings, 250);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (stepSensor != null && hasActivityPermission()) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    private void resetRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(18));
        root.setBackgroundColor(Color.WHITE);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(root);
        setContentView(scroller);
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(28);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setTextColor(Color.rgb(20,20,20));
        t.setPadding(0, dp(6), 0, dp(14));
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Color.DKGRAY);
        t.setPadding(0, dp(8), 0, dp(3));
        return t;
    }

    private Button sportyButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        b.setAllCaps(true);
        b.setMinHeight(dp(52));
        return b;
    }

    private void showHome() {
        resetRoot();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = title("RUNTRACKER");
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button settings = sportyButton("⚙");
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(64), dp(52)));
        root.addView(header);

        TextView tagline = label(tr("Track. Improve. Repeat.", "Suivre. Progresser. Recommencer.", "עקוב. השתפר. חזור."));
        tagline.setTextSize(17);
        root.addView(tagline);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        timeValue = addMetric(grid, tr("TIME", "TEMPS", "זמן"), "00:00:00");
        distanceValue = addMetric(grid, tr("DISTANCE", "DISTANCE", "מרחק"), "0.00 km");
        avgPaceValue = addMetric(grid, tr("AVERAGE PACE", "ALLURE MOYENNE", "קצב ממוצע"), "--:-- /km");
        currentPaceValue = addMetric(grid, tr("CURRENT PACE", "ALLURE ACTUELLE", "קצב נוכחי"), "--:-- /km");
        stepsValue = addMetric(grid, tr("STEPS", "PAS", "צעדים"), "0");
        caloriesValue = addMetric(grid, tr("CALORIES", "CALORIES", "קלוריות"), "0 kcal");
        root.addView(grid);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(14), 0, dp(8));
        startButton = sportyButton("START");
        pauseButton = sportyButton("PAUSE");
        finishButton = sportyButton("FINISH");
        controls.addView(startButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        controls.addView(pauseButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        controls.addView(finishButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        root.addView(controls);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(false);
        startButton.setOnClickListener(v -> startRun());
        pauseButton.setOnClickListener(v -> togglePause());
        finishButton.setOnClickListener(v -> finishRun());

        Button history = sportyButton(tr("RUN HISTORY", "HISTORIQUE", "היסטוריית ריצות"));
        history.setOnClickListener(v -> showHistory());
        root.addView(history);

        TextView version = label("RunTracker v" + getVersionName());
        version.setGravity(Gravity.CENTER);
        version.setTextSize(12);
        version.setTextColor(Color.GRAY);
        version.setPadding(0, dp(24), 0, dp(2));
        root.addView(version);
    }

    private TextView addMetric(LinearLayout parent, String name, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        TextView n = label(name);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(24);
        v.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        v.setGravity(Gravity.END);
        row.addView(n, new LinearLayout.LayoutParams(0, dp(56), 1f));
        row.addView(v, new LinearLayout.LayoutParams(0, dp(56), 1f));
        parent.addView(row);
        return v;
    }

    private void showSettings() {
        resetRoot();
        root.addView(title(tr("SETTINGS", "PARAMÈTRES", "הגדרות")));

        Switch gps = new Switch(this);
        gps.setText(tr("Use GPS", "Utiliser le GPS", "השתמש ב-GPS"));
        gps.setChecked(prefs.getBoolean("gps", true));
        root.addView(gps);

        root.addView(label(tr("Language", "Langue", "שפה")));
        Spinner language = spinner(new String[]{"English", "Français", "עברית"});
        language.setSelection(prefs.getInt("language", 0));
        root.addView(language);

        root.addView(label(tr("Running pace display", "Affichage de l'allure", "תצוגת קצב")));
        Spinner pace = spinner(new String[]{"min/km (pace)", "km/h (speed)"});
        pace.setSelection(prefs.getInt("pace_mode", 0));
        root.addView(pace);

        root.addView(label(tr("Distance units", "Unités de distance", "יחידות מרחק")));
        Spinner units = spinner(new String[]{"Kilometers", "Miles"});
        units.setSelection(prefs.getInt("distance_unit", 0));
        root.addView(units);

        root.addView(label(tr("Profile picture", "Photo de profil", "תמונת פרופיל")));
        ImageView profile = new ImageView(this);
        profile.setAdjustViewBounds(true);
        profile.setMinimumHeight(dp(110));
        String savedPhoto = prefs.getString("profile_photo", "");
        if (!savedPhoto.isEmpty()) try { profile.setImageURI(Uri.parse(savedPhoto)); } catch (Exception ignored) {}
        root.addView(profile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));
        Button choosePhoto = sportyButton(tr("CHOOSE PHOTO", "CHOISIR PHOTO", "בחר תמונה"));
        choosePhoto.setOnClickListener(v -> chooseImage(REQ_PROFILE_PHOTO));
        root.addView(choosePhoto);

        root.addView(label(tr("Or choose an avatar", "Ou choisir un avatar", "או בחר אווטאר")));
        LinearLayout avatars = new LinearLayout(this);
        final String[] selectedAvatar = {prefs.getString("avatar", "🏃")};
        for (String a : new String[]{"🏃","🏃‍♀️","⚡","🔥","🦅"}) {
            Button ab = new Button(this); ab.setText(a); ab.setTextSize(28);
            ab.setOnClickListener(v -> selectedAvatar[0] = ((Button)v).getText().toString());
            avatars.addView(ab, new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        root.addView(avatars);

        EditText weight = numberField(tr("Weight (kg)", "Poids (kg)", "משקל בקג"), prefs.getFloat("weight", 70f));
        EditText height = numberField(tr("Height (cm)", "Taille (cm)", "גובה בסמ"), prefs.getFloat("height", 175f));
        EditText target = numberField(tr("Target weight (kg)", "Poids cible (kg)", "משקל יעד בקג"), prefs.getFloat("target_weight", 70f));
        root.addView(weight); root.addView(height); root.addView(target);

        Button logs = sportyButton(tr("DEBUG / LOGS", "DEBUG / LOGS", "DEBUG / LOGS"));
        logs.setOnClickListener(v -> showLogs());
        root.addView(logs);

        Button save = sportyButton(tr("SAVE SETTINGS", "ENREGISTRER", "שמור הגדרות"));
        save.setOnClickListener(v -> {
            prefs.edit()
                .putBoolean("gps", gps.isChecked())
                .putInt("language", language.getSelectedItemPosition())
                .putInt("pace_mode", pace.getSelectedItemPosition())
                .putInt("distance_unit", units.getSelectedItemPosition())
                .putString("avatar", selectedAvatar[0])
                .putFloat("weight", parseFloat(weight, 70f))
                .putFloat("height", parseFloat(height, 175f))
                .putFloat("target_weight", parseFloat(target, 70f))
                .putBoolean("settings_saved_once", true)
                .apply();
            log("Settings saved");
            showHome();
        });
        root.addView(save);
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a); return s;
    }

    private EditText numberField(String hint, float value) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(String.valueOf(value));
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private float parseFloat(EditText e, float fallback) {
        try { return Float.parseFloat(e.getText().toString()); } catch (Exception x) { return fallback; }
    }

    private void startRun() {
        if (running) return;
        if (!ensurePermissions()) return;
        running = true; paused = false;
        startEpochMs = System.currentTimeMillis(); totalPausedMs = 0; pauseStartedMs = 0;
        initialStepCounter = -1; currentSteps = 0; totalDistanceMeters = 0; totalCalories = 0;
        previousLocation = null; route.clear(); weather = new WeatherSnapshot(); currentSmoothedSpeedMps = 0.0;
        startButton.setEnabled(false); pauseButton.setEnabled(true); finishButton.setEnabled(true);
        log("Run started at " + startEpochMs);
        if (prefs.getBoolean("gps", true)) startLocationUpdates();
        timerHandler.post(timerRunnable);
    }

    private void togglePause() {
        if (!running) return;
        if (!paused) {
            paused = true; pauseStartedMs = System.currentTimeMillis(); pauseButton.setText("RESUME"); log("Run paused");
        } else {
            paused = false; totalPausedMs += System.currentTimeMillis() - pauseStartedMs; pauseStartedMs = 0; pauseButton.setText("PAUSE"); previousLocation = null; log("Run resumed");
        }
    }

    private void finishRun() {
        if (!running) return;
        if (paused) totalPausedMs += System.currentTimeMillis() - pauseStartedMs;
        running = false; paused = false; timerHandler.removeCallbacks(timerRunnable); stopLocationUpdates();
        long end = System.currentTimeMillis();
        try {
            JSONObject run = new JSONObject();
            run.put("id", String.valueOf(startEpochMs));
            run.put("start", startEpochMs); run.put("end", end);
            run.put("duration_ms", activeElapsedMs());
            run.put("distance_m", totalDistanceMeters); run.put("steps", currentSteps); run.put("calories", totalCalories);
            run.put("difficulty", 3); run.put("beauty", 3); run.put("notes", ""); run.put("photo", "");
            run.put("temperature_c", weather.temperatureC); run.put("humidity", weather.humidity); run.put("wind_ms", weather.windSpeedMs); run.put("wind_dir", weather.windDirectionDeg);
            JSONArray points = new JSONArray();
            for (RoutePoint p : route) points.put(p.toJson());
            run.put("route", points);
            appendRun(run);
            log("Run finished: " + totalDistanceMeters + " m, " + totalCalories + " kcal");
            showRunSummary(run);
        } catch (JSONException e) { log("Finish error: " + e.getMessage()); showHome(); }
    }

    private void createLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) {
                if (!running || paused) return;
                for (Location location : result.getLocations()) processLocation(location);
            }
        };
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000).setMinUpdateDistanceMeters(2f).build();
        locationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
    }

    private void stopLocationUpdates() { locationClient.removeLocationUpdates(locationCallback); }

    private void processLocation(Location loc) {
        if (loc.getAccuracy() > 40f) return;
        long now = System.currentTimeMillis();
        RoutePoint current = new RoutePoint(loc.getLatitude(), loc.getLongitude(), loc.hasAltitude() ? loc.getAltitude() : Double.NaN, now, loc.hasSpeed() ? loc.getSpeed() : 0f);
        route.add(current);
        if (route.size() == 1) fetchWeatherAsync(current.lat, current.lon);
        if (previousLocation != null) {
            float d = previousLocation.distanceTo(loc);
            long dtMs = Math.max(1, now - route.get(route.size()-2).timeMs);
            if (d >= 1f && d < 250f) {
                totalDistanceMeters += d;
                double speedMps = d / (dtMs / 1000.0);
                double grade = 0.0;
                if (previousLocation.hasAltitude() && loc.hasAltitude() && d > 5) grade = (loc.getAltitude() - previousLocation.getAltitude()) / d;
                double segKcal = calculateSegmentCalories(d, speedMps, grade, dtMs / 60000.0, previousLocation, loc);
                totalCalories += Math.max(0, segKcal);
            }
        }
        previousLocation = loc;
        currentSmoothedSpeedMps = calculateSmoothedCurrentSpeed();
        updateLiveMetrics();
    }

    /**
     * Agreed calorie model:
     * uphill/flat = ACSM running equation, segment-by-segment; downhill = dedicated Minetti running-cost model.
     * Wind correction is physical drag based (not a fixed multiplier) when start-weather data is available.
     */
    private double calculateSegmentCalories(double distanceM, double speedMps, double grade, double minutes, Location a, Location b) {
        double kg = prefs.getFloat("weight", 70f);
        grade = Math.max(-0.45, Math.min(0.45, grade));
        double kcal;
        if (grade >= 0) {
            double vMMin = speedMps * 60.0;
            double activeVo2 = 0.2 * vMMin + 0.9 * vMMin * grade;
            kcal = (activeVo2 * kg / 1000.0) * 5.0 * minutes;
        } else {
            double i = grade;
            double costJkgm = 155.4*Math.pow(i,5) - 30.4*Math.pow(i,4) - 43.3*Math.pow(i,3) + 46.3*Math.pow(i,2) + 19.5*i + 3.6;
            kcal = Math.max(0, costJkgm) * kg * distanceM / 4184.0;
        }
        return kcal + windDragCalorieCorrection(distanceM, speedMps, a, b, kcal);
    }

    private double windDragCalorieCorrection(double distanceM, double runnerSpeed, Location a, Location b, double baseKcal) {
        if (!weather.valid || runnerSpeed < 0.5 || a == null || b == null) return 0;
        double bearing = a.bearingTo(b);
        double angle = Math.toRadians(normalize180(weather.windDirectionDeg - bearing));
        double headwind = weather.windSpeedMs * Math.cos(angle);
        double relative = Math.max(0, runnerSpeed + headwind);
        double rho = 1.225 * (273.15 / (273.15 + weather.temperatureC));
        double cdA = 0.50;
        double mechanicalJ = 0.5 * rho * cdA * (relative*relative - runnerSpeed*runnerSpeed) * distanceM;
        double metabolicKcal = mechanicalJ / 0.25 / 4184.0;
        double cap = Math.max(0.2, baseKcal * 0.20);
        return Math.max(-cap, Math.min(cap, metabolicKcal));
    }

    private double normalize180(double d) { while (d > 180) d -= 360; while (d < -180) d += 360; return d; }

    private void fetchWeatherAsync(double lat, double lon) {
        new Thread(() -> {
            try {
                String u = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms", lat, lon);
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(5000);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line);
                JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                weather.temperatureC = cur.optDouble("temperature_2m", 20);
                weather.humidity = cur.optDouble("relative_humidity_2m", 50);
                weather.windSpeedMs = cur.optDouble("wind_speed_10m", 0);
                weather.windDirectionDeg = cur.optDouble("wind_direction_10m", 0);
                weather.valid = true;
                log("Weather loaded: " + weather.temperatureC + "C, wind " + weather.windSpeedMs + "m/s");
            } catch (Exception e) { log("Weather unavailable: " + e.getMessage()); }
        }).start();
    }

    private void updateLiveMetrics() {
        if (timeValue == null) return;
        long elapsed = activeElapsedMs();
        timeValue.setText(formatDuration(elapsed));
        boolean miles = prefs.getInt("distance_unit", 0) == 1;
        double dist = miles ? totalDistanceMeters / 1609.344 : totalDistanceMeters / 1000.0;
        distanceValue.setText(String.format(Locale.US, "%.2f %s", dist, miles ? "mi" : "km"));
        double avgSpeedMps = elapsed > 0 ? totalDistanceMeters / (elapsed / 1000.0) : 0;
        avgPaceValue.setText(formatPaceOrSpeed(avgSpeedMps, miles));
        currentPaceValue.setText(formatPaceOrSpeed(currentSmoothedSpeedMps, miles));
        stepsValue.setText(String.valueOf(currentSteps));
        caloriesValue.setText(String.format(Locale.US, "%.0f kcal", totalCalories));
    }

    /**
     * Current pace is intentionally NOT based on the last raw GPS speed sample.
     * It is calculated from a rolling GPS window so temporary satellite jitter does not
     * produce impossible pace values while the runner is moving steadily.
     */
    private double calculateSmoothedCurrentSpeed() {
        if (route.size() < 2) return 0.0;
        int last = route.size() - 1;
        RoutePoint newest = route.get(last);
        long targetWindowMs = 10_000L;
        int first = last - 1;
        while (first > 0 && newest.timeMs - route.get(first).timeMs < targetWindowMs) first--;
        RoutePoint oldest = route.get(first);
        long dt = newest.timeMs - oldest.timeMs;
        if (dt < 3_000L) return 0.0;

        double distance = 0.0;
        float[] out = new float[1];
        for (int i = first + 1; i <= last; i++) {
            RoutePoint a = route.get(i - 1);
            RoutePoint b = route.get(i);
            Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out);
            if (out[0] > 0.5f && out[0] < 120f) distance += out[0];
        }
        double speed = distance / (dt / 1000.0);
        if (speed < 0.45) return 0.0;
        return Math.min(speed, 12.0);
    }

    private long activeElapsedMs() {
        if (startEpochMs == 0) return 0;
        long now = System.currentTimeMillis();
        long ongoingPause = paused ? now - pauseStartedMs : 0;
        return Math.max(0, now - startEpochMs - totalPausedMs - ongoingPause);
    }

    private String formatPaceOrSpeed(double speedMps, boolean miles) {
        if (speedMps < 0.2) return "--:--";
        if (prefs.getInt("pace_mode", 0) == 1) {
            double kph = speedMps * 3.6;
            if (miles) return String.format(Locale.US, "%.1f mph", kph / 1.609344);
            return String.format(Locale.US, "%.1f km/h", kph);
        }
        double unitMeters = miles ? 1609.344 : 1000.0;
        double sec = unitMeters / speedMps;
        int min = (int)(sec / 60); int s = (int)Math.round(sec % 60);
        return String.format(Locale.US, "%d:%02d /%s", min, s, miles ? "mi" : "km");
    }

    private String formatDuration(long ms) {
        long sec = ms / 1000; return String.format(Locale.US, "%02d:%02d:%02d", sec/3600, (sec%3600)/60, sec%60);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER && running && !paused) {
            long absolute = (long)event.values[0];
            if (initialStepCounter < 0) initialStepCounter = absolute;
            currentSteps = Math.max(0, absolute - initialStepCounter);
            if (stepsValue != null) stepsValue.setText(String.valueOf(currentSteps));
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private boolean ensurePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS); return false; }
        return true;
    }
    private boolean hasActivityPermission() { return Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED; }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && ensurePermissions()) startRun();
    }

    private void showHistory() {
        resetRoot(); root.addView(title(tr("RUN HISTORY", "HISTORIQUE", "היסטוריית ריצות")));
        JSONArray arr = historyArray();
        if (arr.length() == 0) root.addView(label(tr("No runs yet.", "Aucune course.", "אין עדיין ריצות.")));
        for (int i = arr.length()-1; i >= 0; i--) {
            try {
                JSONObject run = arr.getJSONObject(i);
                LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                Button open = sportyButton(formatDate(run.optLong("start")) + "  •  " + String.format(Locale.US, "%.2f km", run.optDouble("distance_m")/1000.0));
                open.setOnClickListener(v -> showRunSummary(run));
                Button del = sportyButton("🗑");
                String id = run.optString("id"); del.setOnClickListener(v -> { deleteRun(id); showHistory(); });
                row.addView(open, new LinearLayout.LayoutParams(0, dp(64), 1f)); row.addView(del, new LinearLayout.LayoutParams(dp(70), dp(64)));
                root.addView(row);
            } catch (JSONException ignored) {}
        }
        Button back = sportyButton(tr("BACK", "RETOUR", "חזרה")); back.setOnClickListener(v -> showHome()); root.addView(back);
    }

    private void showRunSummary(JSONObject run) {
        currentSummaryRun = run; pendingRunPhotoUri = run.optString("photo", "");
        resetRoot(); root.addView(title(tr("RUN SUMMARY", "RÉSUMÉ DE COURSE", "סיכום ריצה")));
        root.addView(label(formatDate(run.optLong("start"))));

        JSONArray pts = run.optJSONArray("route");
        if (pts != null && pts.length() > 0) {
            logGoogleMapsDiagnostic("summary-open");
            MapView map = new MapView(this); map.onCreate(null); map.onResume();
            root.addView(map, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
            try {
                MapsInitializer.initialize(getApplicationContext(), MapsInitializer.Renderer.LATEST,
                    renderer -> log("Google Maps renderer initialized: " + renderer));
            } catch (Exception e) {
                log("Google Maps initialization error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            map.getMapAsync(gm -> {
                log("Google Maps onMapReady received");
                gm.setOnMapLoadedCallback(() -> log("Google Maps tiles loaded successfully"));
                drawRoute(gm, pts);
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                    log("Google Maps diagnostic timeout check completed - if no 'tiles loaded successfully' line appears above, check API key restrictions / SHA-1 / Maps SDK for Android"),
                    8000);
            });
        }
        root.addView(summaryLine(tr("Distance", "Distance", "מרחק"), String.format(Locale.US, "%.2f km", run.optDouble("distance_m")/1000.0)));
        root.addView(summaryLine(tr("Time", "Temps", "זמן"), formatDuration(run.optLong("duration_ms"))));
        double sp = run.optLong("duration_ms") > 0 ? run.optDouble("distance_m") / (run.optLong("duration_ms")/1000.0) : 0;
        root.addView(summaryLine(tr("Average pace", "Allure moyenne", "קצב ממוצע"), formatPaceOrSpeed(sp, false)));
        root.addView(summaryLine(tr("Steps", "Pas", "צעדים"), String.valueOf(run.optLong("steps"))));
        root.addView(summaryLine(tr("Calories", "Calories", "קלוריות"), String.format(Locale.US, "%.0f kcal", run.optDouble("calories"))));
        if (!Double.isNaN(run.optDouble("temperature_c", Double.NaN))) root.addView(summaryLine(tr("Weather", "Météo", "מזג אוויר"), String.format(Locale.US, "%.0f°C • wind %.1f m/s", run.optDouble("temperature_c"), run.optDouble("wind_ms"))));

        Button photo = sportyButton(tr("ADD / CHANGE PHOTO", "AJOUTER / CHANGER PHOTO", "הוסף / החלף תמונה")); photo.setOnClickListener(v -> chooseImage(REQ_RUN_PHOTO)); root.addView(photo);
        if (!pendingRunPhotoUri.isEmpty()) {
            ImageView img = new ImageView(this); img.setAdjustViewBounds(true); try { img.setImageURI(Uri.parse(pendingRunPhotoUri)); } catch (Exception ignored) {}
            root.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        }

        root.addView(label(tr("Difficulty", "Difficulté", "קושי הריצה")));
        RatingBar difficulty = rating(run.optInt("difficulty", 3)); root.addView(difficulty);
        root.addView(label(tr("Route beauty", "Beauté du parcours", "יופי המסלול")));
        RatingBar beauty = rating(run.optInt("beauty", 3)); root.addView(beauty);
        EditText notes = new EditText(this); notes.setHint(tr("Notes", "Notes", "הערות")); notes.setText(run.optString("notes", "")); notes.setMinLines(3); root.addView(notes);

        if (pts != null && pts.length() > 1) {
            root.addView(label(tr("Elevation profile", "Profil d'altitude", "גרף טיפוס")));
            root.addView(new RunChartView(this, pts, true, prefs.getInt("distance_unit", 0) == 1), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
            root.addView(label(tr("Pace by distance", "Allure par distance", "קצב לפי מרחק")));
            root.addView(new RunChartView(this, pts, false, prefs.getInt("distance_unit", 0) == 1), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        }

        Button save = sportyButton(tr("SAVE SUMMARY", "ENREGISTRER", "שמור סיכום"));
        save.setOnClickListener(v -> {
            try {
                run.put("difficulty", (int)difficulty.getRating()); run.put("beauty", (int)beauty.getRating()); run.put("notes", notes.getText().toString()); run.put("photo", pendingRunPhotoUri);
                replaceRun(run); log("Run summary updated"); showHistory();
            } catch (JSONException ignored) {}
        });
        root.addView(save);
        Button home = sportyButton(tr("HOME", "ACCUEIL", "ראשי")); home.setOnClickListener(v -> showHome()); root.addView(home);
    }

    private TextView summaryLine(String a, String b) { TextView t=label(a + ":  " + b); t.setTextSize(18); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return t; }
    private RatingBar rating(int value) { RatingBar r = new RatingBar(this, null, android.R.attr.ratingBarStyle); r.setNumStars(5); r.setStepSize(1); r.setRating(value); return r; }

    private void logGoogleMapsDiagnostic(String stage) {
        try {
            String packageName = getPackageName();
            String version = getVersionName();
            String key = getString(com.runtracker.app.R.string.google_maps_key);
            String keySummary = key == null ? "null" : (key.length() >= 10 ? key.substring(0, 6) + "..." + key.substring(key.length()-4) : "too-short");
            String sha1 = runtimeSigningSha1();
            boolean network = false;
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
                android.net.Network n = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                network = caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (Exception ignored) {}
            log("Google Maps diagnostic [" + stage + "]: package=" + packageName
                + ", version=" + version + ", signingSHA1=" + sha1
                + ", key=" + keySummary + ", keyLength=" + (key == null ? 0 : key.length())
                + ", internet=" + network);
        } catch (Exception e) {
            log("Google Maps diagnostic failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String runtimeSigningSha1() {
        try {
            android.content.pm.PackageInfo info;
            if (Build.VERSION.SDK_INT >= 28) {
                info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.Signature[] sigs = info.signingInfo.getApkContentsSigners();
                if (sigs == null || sigs.length == 0) return "none";
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(sigs[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (int i=0;i<digest.length;i++) {
                    if (i>0) sb.append(":");
                    sb.append(String.format(Locale.US, "%02X", digest[i] & 0xff));
                }
                return sb.toString();
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(info.signatures[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (int i=0;i<digest.length;i++) {
                    if (i>0) sb.append(":");
                    sb.append(String.format(Locale.US, "%02X", digest[i] & 0xff));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }

    private void drawRoute(GoogleMap gm, JSONArray pts) {
        try {
            PolylineOptions line = new PolylineOptions().width(10f).color(Color.rgb(30,100,230));
            LatLngBounds.Builder bounds = new LatLngBounds.Builder();
            for (int i=0;i<pts.length();i++) { JSONObject p=pts.getJSONObject(i); LatLng ll=new LatLng(p.getDouble("lat"),p.getDouble("lon")); line.add(ll); bounds.include(ll); }
            gm.addPolyline(line); if (pts.length() > 1) gm.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(45))); else gm.moveCamera(CameraUpdateFactory.newLatLngZoom(line.getPoints().get(0), 16f));
        } catch (Exception e) { log("Map route error: " + e.getMessage()); }
    }

    private void chooseImage(int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, requestCode);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        if (requestCode == REQ_PROFILE_PHOTO) { prefs.edit().putString("profile_photo", uri.toString()).apply(); showSettings(); }
        if (requestCode == REQ_RUN_PHOTO && currentSummaryRun != null) { pendingRunPhotoUri = uri.toString(); showRunSummary(currentSummaryRun); }
    }

    private JSONArray historyArray() { try { return new JSONArray(prefs.getString(HISTORY_KEY, "[]")); } catch (Exception e) { return new JSONArray(); } }
    private void appendRun(JSONObject run) { JSONArray a=historyArray(); a.put(run); prefs.edit().putString(HISTORY_KEY,a.toString()).apply(); }
    private void replaceRun(JSONObject run) {
        JSONArray a=historyArray(), out=new JSONArray(); String id=run.optString("id");
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i); out.put(x!=null && id.equals(x.optString("id")) ? run : x);} prefs.edit().putString(HISTORY_KEY,out.toString()).apply();
    }
    private void deleteRun(String id) {
        JSONArray a=historyArray(), out=new JSONArray(); for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i); if(x!=null&&!id.equals(x.optString("id")))out.put(x);} prefs.edit().putString(HISTORY_KEY,out.toString()).apply(); log("Run deleted: "+id);
    }

    private void showLogs() {
        resetRoot(); root.addView(title("DEBUG / LOGS")); TextView text=new TextView(this); text.setText(prefs.getString("logs", "No logs yet.")); text.setTextIsSelectable(true); root.addView(text);
        Button clear=sportyButton("CLEAR LOGS"); clear.setOnClickListener(v->{prefs.edit().remove("logs").apply();showLogs();}); root.addView(clear);
        Button back=sportyButton("BACK"); back.setOnClickListener(v->showSettings()); root.addView(back);
    }
    private void log(String message) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + message + "\n";
        String old=prefs.getString("logs",""); if(old.length()>12000) old=old.substring(old.length()-8000); prefs.edit().putString("logs",old+line).apply(); android.util.Log.d("RunTracker",message);
    }

    private String tr(String en, String fr, String he) { int l=prefs.getInt("language",0); return l==1?fr:(l==2?he:en); }
    private String formatDate(long epoch) { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(epoch)); }
    private String getVersionName() { try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; } catch(Exception e){return "0.1.1";} }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    static class RoutePoint {
        final double lat,lon,alt; final long timeMs; final float speedMps;
        RoutePoint(double lat,double lon,double alt,long timeMs,float speedMps){this.lat=lat;this.lon=lon;this.alt=alt;this.timeMs=timeMs;this.speedMps=speedMps;}
        JSONObject toJson() throws JSONException { JSONObject o=new JSONObject();o.put("lat",lat);o.put("lon",lon);o.put("alt",alt);o.put("t",timeMs);o.put("speed",speedMps);return o; }
    }
    static class WeatherSnapshot { boolean valid=false; double temperatureC=Double.NaN,humidity=Double.NaN,windSpeedMs=0,windDirectionDeg=0; }

    /**
     * Distance-based chart used by the run summary.
     * Elevation mode: X = distance, Y = altitude.
     * Pace mode: X = distance, Y = rolling pace computed from route geometry and time.
     * Both modes render grid lines, numeric ticks and units so the graph is self-explanatory.
     */
    static class RunChartView extends View {
        final JSONArray points;
        final boolean elevation;
        final boolean miles;
        final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RunChartView(Context c, JSONArray pts, boolean elevation, boolean miles) {
            super(c); this.points=pts; this.elevation=elevation; this.miles=miles;
            linePaint.setStrokeWidth(4f); linePaint.setStyle(Paint.Style.STROKE); linePaint.setColor(Color.rgb(25,25,25));
            gridPaint.setStrokeWidth(1f); gridPaint.setColor(Color.rgb(205,205,205));
            textPaint.setTextSize(27f); textPaint.setColor(Color.rgb(80,80,80)); textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            fillPaint.setStyle(Paint.Style.FILL); fillPaint.setColor(Color.argb(38, 30, 100, 230));
            setBackgroundColor(Color.rgb(248,248,248));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (points.length() < 2) return;

            ArrayList<Double> distancesM = new ArrayList<>();
            ArrayList<Double> values = new ArrayList<>();
            ArrayList<Long> times = new ArrayList<>();
            double cumulative = 0.0;
            float[] dOut = new float[1];

            try {
                JSONObject first = points.getJSONObject(0);
                double prevLat = first.getDouble("lat"), prevLon = first.getDouble("lon");
                long prevT = first.optLong("t", 0);
                distancesM.add(0.0);
                times.add(prevT);
                double firstVal = elevation ? first.optDouble("alt", Double.NaN) : Double.NaN;
                values.add(firstVal);

                for (int i=1;i<points.length();i++) {
                    JSONObject x = points.getJSONObject(i);
                    double lat=x.getDouble("lat"), lon=x.getDouble("lon");
                    Location.distanceBetween(prevLat, prevLon, lat, lon, dOut);
                    if (dOut[0] > 0.5f && dOut[0] < 150f) cumulative += dOut[0];
                    long t=x.optLong("t", prevT);
                    distancesM.add(cumulative);
                    times.add(t);

                    if (elevation) {
                        values.add(x.optDouble("alt", Double.NaN));
                    } else {
                        int back=i-1;
                        while(back>0 && t - times.get(back) < 10000L) back--;
                        double windowDist = cumulative - distancesM.get(back);
                        long dt = t - times.get(back);
                        double speed = dt >= 3000 ? windowDist/(dt/1000.0) : 0.0;
                        double unitM = miles ? 1609.344 : 1000.0;
                        double paceMin = speed > 0.45 ? (unitM/speed)/60.0 : Double.NaN;
                        if (paceMin > 1.5 && paceMin < 30.0) values.add(paceMin); else values.add(Double.NaN);
                    }
                    prevLat=lat; prevLon=lon; prevT=t;
                }
            } catch(Exception ignored) { return; }

            ArrayList<Double> finite = new ArrayList<>();
            for(Double v:values) if(v!=null && !Double.isNaN(v) && !Double.isInfinite(v)) finite.add(v);
            if (finite.size() < 2 || cumulative <= 0.5) return;

            double min=Collections.min(finite), max=Collections.max(finite);
            if (elevation) {
                double pad=Math.max(2.0,(max-min)*0.15); min-=pad; max+=pad;
            } else {
                double pad=Math.max(0.25,(max-min)*0.12); min=Math.max(0,min-pad); max+=pad;
            }
            if(max-min<0.01) max=min+1;

            float left=82, right=getWidth()-18, top=26, bottom=getHeight()-52;
            int yTicks=4, xTicks=4;

            for(int i=0;i<=yTicks;i++){
                float y=top+(bottom-top)*(i/(float)yTicks);
                canvas.drawLine(left,y,right,y,gridPaint);
                double v=max-(max-min)*(i/(double)yTicks);
                String s=elevation ? String.format(Locale.US,"%.0f",v) : paceLabel(v);
                canvas.drawText(s,8,y+9,textPaint);
            }
            for(int i=0;i<=xTicks;i++){
                float x=left+(right-left)*(i/(float)xTicks);
                canvas.drawLine(x,top,x,bottom,gridPaint);
                double dm=cumulative*(i/(double)xTicks);
                double shown=miles?dm/1609.344:dm/1000.0;
                String s=shown<10?String.format(Locale.US,"%.1f",shown):String.format(Locale.US,"%.0f",shown);
                float w=textPaint.measureText(s);
                canvas.drawText(s,x-w/2,getHeight()-18,textPaint);
            }

            Path line=new Path(); Path fill=new Path(); boolean started=false;
            for(int i=0;i<values.size();i++){
                double v=values.get(i); if(Double.isNaN(v)||Double.isInfinite(v))continue;
                float x=(float)(left+(right-left)*(distancesM.get(i)/cumulative));
                float y=(float)(bottom-(bottom-top)*((v-min)/(max-min)));
                if(!started){line.moveTo(x,y);fill.moveTo(x,bottom);fill.lineTo(x,y);started=true;}
                else {line.lineTo(x,y);fill.lineTo(x,y);}
            }
            if(started){
                fill.lineTo(right,bottom); fill.close();
                canvas.drawPath(fill,fillPaint); canvas.drawPath(line,linePaint);
            }

            String yLabel=elevation ? (miles ? "Altitude (ft)" : "Altitude (m)") : (miles ? "Pace (min/mi)" : "Pace (min/km)");
            String xLabel=miles ? "Distance (mi)" : "Distance (km)";
            textPaint.setTextSize(24f);
            canvas.drawText(xLabel, Math.max(left, right-textPaint.measureText(xLabel)), getHeight()-18, textPaint);
            canvas.save();
            canvas.rotate(-90);
            canvas.drawText(yLabel, -bottom, 26, textPaint);
            canvas.restore();

            if(!elevation){
                long totalMs=times.get(times.size()-1)-times.get(0);
                String elapsed=String.format(Locale.US,"Time %02d:%02d:%02d",(totalMs/1000)/3600,((totalMs/1000)%3600)/60,(totalMs/1000)%60);
                canvas.drawText(elapsed,left,top+24,textPaint);
            }
        }

        private String paceLabel(double minutes) {
            int m=(int)Math.floor(minutes);
            int s=(int)Math.round((minutes-m)*60.0);
            if(s==60){m++;s=0;}
            return String.format(Locale.US,"%d:%02d",m,s);
        }
    }
}
