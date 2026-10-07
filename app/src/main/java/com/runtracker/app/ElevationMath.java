package com.runtracker.app;

import java.util.*;

/**
 * Pure elevation helpers used both by production code and deterministic QA tests.
 */
public final class ElevationMath {
    private ElevationMath() {}

    /**
     * Converts pressure change into relative altitude using the standard barometric formula.
     * Only the relative change is used; absolute sea-level pressure is not required.
     */
    public static double relativeAltitudeMeters(double baselinePressureHpa, double currentPressureHpa) {
        if (baselinePressureHpa <= 0 || currentPressureHpa <= 0) return 0.0;
        return 44330.0 * (1.0 - Math.pow(currentPressureHpa / baselinePressureHpa, 0.190294957));
    }

    /**
     * Median smoothing with a symmetric window while preserving missing values.
     * radius=2 means a maximum 5-sample window.
     */
    public static ArrayList<Double> medianSmoothPreservingGaps(List<Double> source, int radius) {
        ArrayList<Double> out = new ArrayList<>();
        if (source == null) return out;
        int n = source.size();
        for (int i = 0; i < n; i++) {
            Double center = source.get(i);
            if (center == null || Double.isNaN(center) || Double.isInfinite(center)) {
                out.add(Double.NaN);
                continue;
            }
            ArrayList<Double> window = new ArrayList<>();
            for (int j = Math.max(0, i-radius); j <= Math.min(n-1, i+radius); j++) {
                Double v = source.get(j);
                if (v != null && !Double.isNaN(v) && !Double.isInfinite(v)) window.add(v);
            }
            Collections.sort(window);
            out.add(window.get(window.size()/2));
        }
        return out;
    }

    /**
     * Computes ascent using hysteresis so small measurement oscillations are not counted as climbing.
     */
    public static double positiveGainMeters(List<Double> altitudes, double thresholdM) {
        if (altitudes == null || altitudes.isEmpty()) return 0.0;
        Double anchor = null;
        double gain = 0.0;
        for (Double v : altitudes) {
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
            if (anchor == null) {
                anchor = v;
                continue;
            }
            double delta = v - anchor;
            if (delta >= thresholdM) {
                gain += delta;
                anchor = v;
            } else if (delta <= -thresholdM) {
                anchor = v;
            }
        }
        return gain;
    }
}
