package com.soundlit.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;

/** The chosen look (black, white or the song's colours) and the colours taken from the album art. */
final class Theme {
    static final int BLACK = 0, WHITE = 1, SONG = 2;

    int mode;
    int c1 = Color.rgb(20, 120, 160), c2 = Color.rgb(120, 40, 150), c3 = Color.rgb(40, 64, 160);
    private final SharedPreferences prefs;

    Theme(Context ctx) {
        prefs = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE);
        mode = prefs.getInt("mode", SONG);
    }

    void setMode(int m) {
        mode = m;
        prefs.edit().putInt("mode", m).apply();
    }

    boolean light() { return mode == WHITE; }
    int text() { return light() ? 0xFF141623 : 0xFFFFFFFF; }
    int sub() { return light() ? 0xFF505873 : 0xFFBEC8E6; }

    /** Picks two vivid, different colours from the artwork; keeps the old ones when there is none. */
    void fromArt(Bitmap b) {
        if (b == null) return;
        Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
        float[] score = new float[12];
        float[][] sum = new float[12][3];
        float[] cnt = new float[12];
        float[] hsv = new float[3];
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                Color.colorToHSV(s.getPixel(x, y), hsv);
                if (hsv[1] < .18f || hsv[2] < .15f) continue;
                int k = Math.min(11, (int) (hsv[0] / 30f));
                float w = hsv[1] * hsv[2];
                score[k] += w;
                sum[k][0] += hsv[0] * w;
                sum[k][1] += hsv[1] * w;
                sum[k][2] += hsv[2] * w;
                cnt[k] += w;
            }
        }
        if (s != b) s.recycle();
        int best = -1, second = -1;
        for (int k = 0; k < 12; k++) if (best < 0 || score[k] > score[best]) best = k;
        if (best < 0 || score[best] <= 0f) return;
        for (int k = 0; k < 12; k++) {
            int d = Math.min(Math.abs(k - best), 12 - Math.abs(k - best));
            if (d >= 2 && score[k] > 0f && (second < 0 || score[k] > score[second])) second = k;
        }
        c1 = tone(sum[best], cnt[best]);
        c2 = second >= 0 ? tone(sum[second], cnt[second]) : shift(c1, 40f);
        c3 = shift(c1, -35f);
    }

    private static int tone(float[] s, float w) {
        float h = s[0] / w, sat = Math.max(.55f, s[1] / w), v = Math.max(.55f, Math.min(.95f, s[2] / w));
        return Color.HSVToColor(new float[]{h, Math.min(1f, sat), v});
    }

    private static int shift(int c, float deg) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[0] = (hsv[0] + deg + 360f) % 360f;
        return Color.HSVToColor(hsv);
    }
}
