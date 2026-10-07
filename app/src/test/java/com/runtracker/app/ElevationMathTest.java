package com.runtracker.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ElevationMathTest {
    @Test public void barometerDetectsApproximatelyFourMeterClimb() {
        double baseline = 1013.25;
        // Approx pressure corresponding to roughly +4 m relative altitude.
        double current = 1012.77;
        double delta = ElevationMath.relativeAltitudeMeters(baseline, current);
        assertTrue(delta > 3.0 && delta < 5.2);
    }

    @Test public void medianFilterRejectsSingleGpsSpike() {
        ArrayList<Double> in = new ArrayList<>(Arrays.asList(
            100.0, 100.2, 100.1, 111.0, 100.3, 100.2, 100.4
        ));
        ArrayList<Double> out = ElevationMath.medianSmoothPreservingGaps(in, 2);
        assertTrue(out.get(3) < 101.0);
    }

    @Test public void positiveGainIgnoresSmallNoise() {
        ArrayList<Double> in = new ArrayList<>(Arrays.asList(
            100.0, 100.4, 99.8, 100.5, 100.1, 103.8, 104.1
        ));
        double gain = ElevationMath.positiveGainMeters(in, 1.5);
        assertTrue(gain >= 3.0 && gain <= 4.5);
    }

    @Test public void flatNoisyTrackDoesNotCreateLargeGain() {
        ArrayList<Double> in = new ArrayList<>(Arrays.asList(
            100.0, 100.3, 99.9, 100.4, 99.8, 100.2, 100.0
        ));
        double gain = ElevationMath.positiveGainMeters(in, 1.5);
        assertEquals(0.0, gain, 0.001);
    }
}
