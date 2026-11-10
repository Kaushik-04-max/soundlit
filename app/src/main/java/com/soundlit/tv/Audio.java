package com.soundlit.tv;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Turns the TV's audio output into LED-bar levels. Uses Android's Visualizer on the global output mix.
 * If the TV blocks that (no data for a few seconds while music plays), it falls back to a simulated
 * animation and says so through {@link #status}.
 */
final class Audio {
    static final int BANDS = 44;
    private static final String TAG = "Equaliser.Audio";

    final float[] level = new float[BANDS];
    final float[] peak = new float[BANDS];
    String status = "starting";
    boolean simulated = true;

    private Visualizer viz;
    private byte[] fft;
    private float env = 30f;
    private long silentSince = -1;
    private long lastLog;

    void start() {
        stop();
        try {
            viz = new Visualizer(0);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            viz.setEnabled(true);
            fft = new byte[viz.getCaptureSize()];
            status = "visualizer " + viz.getCaptureSize() + " @" + viz.getSamplingRate();
            simulated = false;
        } catch (Throwable t) {
            viz = null;
            simulated = true;
            status = "visualizer unavailable: " + t;
            Log.w(TAG, status);
        }
    }

    void stop() {
        if (viz != null) {
            try { viz.setEnabled(false); } catch (Throwable ignored) { }
            try { viz.release(); } catch (Throwable ignored) { }
            viz = null;
        }
    }

    /** Call about 30 times a second. */
    void update(boolean playing, long nowMs) {
        float[] target = new float[BANDS];
        boolean real = false;
        if (viz != null && playing) {
            int r = viz.getFft(fft);
            if (r == Visualizer.SUCCESS) {
                float total = bands(target);
                if (total > 0f) {
                    real = true;
                    silentSince = -1;
                    simulated = false;
                } else {
                    if (silentSince < 0) silentSince = nowMs;
                    if (nowMs - silentSince > 3500) {
                        simulated = true;
                        status = "visualizer gives no audio data; using simulated bars";
                    }
                }
            } else {
                status = "getFft failed: " + r;
                simulated = true;
            }
        } else if (viz == null) {
            simulated = true;
        }
        if (!real) {
            if (playing && simulated) simulate(target, nowMs);
        }
        for (int i = 0; i < BANDS; i++) {
            float v = target[i];
            level[i] = v > level[i] ? level[i] + (v - level[i]) * .65f : level[i] * .87f;
            peak[i] = Math.max(level[i], peak[i] - .012f);
        }
        if (nowMs - lastLog > 10000) {
            lastLog = nowMs;
            Log.i(TAG, status + (real ? " (real audio)" : simulated ? " (simulated)" : ""));
        }
    }

    /** FFT bytes to log-spaced band levels in 0..1; returns the total energy (0 when silent). */
    private float bands(float[] out) {
        int n = fft.length;
        float hzPerBin = viz.getSamplingRate() / 1000f / n;
        float frameMax = 0f, total = 0f;
        float[] mag = new float[BANDS];
        for (int i = 0; i < BANDS; i++) {
            float f0 = (float) (40 * Math.pow(16000f / 40f, i / (double) BANDS));
            float f1 = (float) (40 * Math.pow(16000f / 40f, (i + 1) / (double) BANDS));
            int lo = Math.max(1, Math.round(f0 / hzPerBin));
            int hi = Math.max(lo + 1, Math.round(f1 / hzPerBin));
            float m = 0f;
            for (int k = lo; k < hi && 2 * k + 1 < n; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                m = Math.max(m, (float) Math.hypot(re, im));
            }
            mag[i] = m;
            total += m;
            frameMax = Math.max(frameMax, m);
        }
        env = Math.max(Math.max(frameMax, 14f), env * .993f);
        for (int i = 0; i < BANDS; i++) {
            float v = (float) Math.pow(mag[i] / env, .75);
            v *= .85f + .5f * i / BANDS;   // highs carry less energy; lift them a little
            out[i] = Math.max(0f, Math.min(1f, v));
        }
        return total;
    }

    private void simulate(float[] out, long nowMs) {
        float t = nowMs / 1000f;
        for (int i = 0; i < BANDS; i++) {
            float f = i / (float) (BANDS - 1);
            float e = .75f * (float) Math.pow(1 - f, 1.1) + .3f;
            float w = Math.abs((float) (Math.sin(t * (1.3 + i * .11) + i) * Math.cos(t * .8 + i * .37)));
            out[i] = Math.min(1f, e * (.25f + .85f * w));
        }
    }
}
