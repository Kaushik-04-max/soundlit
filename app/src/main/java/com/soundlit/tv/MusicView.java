package com.soundlit.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.Locale;

/**
 * The whole app is this one view: it draws the menu, the equaliser, the lyrics and the settings page,
 * and handles the remote. Everything is laid out on a 1920x1080 grid and scaled to the screen.
 */
public class MusicView extends View implements NowPlaying.Listener {
    private static final int MENU = 0, EQ = 1, LYRICS = 2, SETTINGS = 3;
    private static final int ACCENT = 0xFF5AE6FF;

    private final Theme theme;
    private final NowPlaying np;
    private final Audio audio = new Audio();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();

    private final Typeface outfit300, outfit500, outfit600, lora400, lora600;

    private int screen = MENU;
    private int focus = 1;            // menu: 0 settings, 1 equaliser, 2 lyrics, 3 prev, 4 play/pause, 5 next
    private int settingsFocus;        // settings: column on the theme row
    private int lookFocus;            // settings: column on the equaliser-look row
    private int settingsRow;          // settings: 0 theme row, 1 look row
    private static final int CLASSIC = 0, KINDLE = 1, MODERN = 2;
    private final android.content.SharedPreferences prefs;
    private int look;
    private float S = 1f;
    private boolean running;

    private Bitmap shownArt;
    private BitmapShader artShader;
    private Shader[] bgShaders;
    private int bgKey;

    private Lyrics lyrics = new Lyrics();
    private String lyricsKey = "";
    private boolean lyricsLoading;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            audio.update(np.playing, SystemClock.uptimeMillis());
            invalidate();
            handler.postDelayed(this, (screen == EQ || screen == LYRICS) ? 33 : 66);
        }
    };

    public MusicView(Context ctx) {
        super(ctx);
        setFocusable(true);
        theme = new Theme(ctx);
        prefs = ctx.getSharedPreferences("look", Context.MODE_PRIVATE);
        look = Math.max(0, Math.min(2, prefs.getInt("look", CLASSIC)));
        np = new NowPlaying(ctx, this);
        outfit300 = font(ctx, "Outfit.ttf", 300);
        outfit500 = font(ctx, "Outfit.ttf", 500);
        outfit600 = font(ctx, "Outfit.ttf", 600);
        lora400 = font(ctx, "Lora.ttf", 400);
        lora600 = font(ctx, "Lora.ttf", 600);
    }

    private static Typeface font(Context ctx, String file, int weight) {
        try {
            Typeface t = new Typeface.Builder(ctx.getAssets(), "fonts/" + file)
                    .setFontVariationSettings("'wght' " + weight).build();
            if (t != null) return t;
        } catch (Exception ignored) {
        }
        return Typeface.DEFAULT;
    }

    // ---- lifecycle --------------------------------------------------------------------------

    void onActivityResume() {
        np.start();
        audio.start();
        running = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void onActivityPause() {
        running = false;
        handler.removeCallbacks(tick);
        np.stop();
        audio.stop();
    }

    void restartAudio() { audio.start(); }

    @Override
    public void changed() {
        String key = np.title + "|" + np.artist + "|" + np.duration;
        if (!key.equals(lyricsKey)) {
            lyricsKey = key;
            lyrics = new Lyrics();
            if (!np.title.isEmpty()) {
                lyricsLoading = true;
                final String k = key, t = np.title, a = np.artist, al = np.album;
                final long d = np.duration;
                new Thread(() -> {
                    final Lyrics l = Lyrics.fetch(t, a, al, d);
                    handler.post(() -> {
                        if (k.equals(lyricsKey)) {
                            lyrics = l;
                            lyricsLoading = false;
                            invalidate();
                        }
                    });
                }, "lyrics").start();
            } else {
                lyricsLoading = false;
            }
        }
        Bitmap a = np.art;
        if (a != null && a != shownArt) {
            shownArt = a;
            artShader = new BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            theme.fromArt(a);
            bgKey = 0;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        S = w / 1920f;
        bgKey = 0;
    }

    // ---- remote -----------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        switch (screen) {
            case MENU:
                if (back) return super.onKeyDown(code, e);
                if (ok) { activateMenu(); return true; }
                return moveMenu(code);
            case SETTINGS:
                if (back) { screen = MENU; invalidate(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP) settingsRow = 0;
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) settingsRow = 1;
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (settingsRow == 0 && settingsFocus > 0) settingsFocus--;
                    else if (settingsRow == 1 && lookFocus > 0) lookFocus--;
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (settingsRow == 0 && settingsFocus < 2) settingsFocus++;
                    else if (settingsRow == 1 && lookFocus < 2) lookFocus++;
                } else if (ok) {
                    if (settingsRow == 0) { theme.setMode(settingsFocus); bgKey = 0; }
                    else { look = lookFocus; prefs.edit().putInt("look", look).apply(); }
                }
                invalidate();
                return true;
            default:   // equaliser, lyrics: no controls on screen, Back returns to the menu
                if (back) { screen = MENU; invalidate(); return true; }
                return super.onKeyDown(code, e);
        }
    }

    private boolean moveMenu(int code) {
        int f = focus;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (f == 2) f = 1; else if (f == 5) f = 4; else if (f == 4) f = 3; else if (f == 0) f = 2;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (f == 1) f = 2; else if (f == 3) f = 4; else if (f == 4) f = 5; else if (f == 2) f = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (f == 3 || f == 4) f = 1; else if (f == 5) f = 2; else if (f == 1 || f == 2) f = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (f == 0) f = 2; else if (f == 1) f = 3; else if (f == 2) f = 5;
                break;
            default:
                return false;
        }
        focus = f;
        invalidate();
        return true;
    }

    private void activateMenu() {
        switch (focus) {
            case 0: screen = SETTINGS; settingsRow = 0; settingsFocus = theme.mode; lookFocus = look; break;
            case 1: screen = EQ; break;
            case 2: screen = LYRICS; break;
            case 3: np.prev(); break;
            case 4: np.toggle(); break;
            case 5: np.next(); break;
            default: break;
        }
        invalidate();
    }

    // ---- drawing helpers --------------------------------------------------------------------

    private float u(float v) { return v * S; }

    private void fill(Canvas c, float l, float t, float r, float b, float rad, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        rect.set(u(l), u(t), u(r), u(b));
        c.drawRoundRect(rect, u(rad), u(rad), p);
    }

    private void stroke(Canvas c, float l, float t, float r, float b, float rad, int color, float w) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(u(w));
        p.setColor(color);
        rect.set(u(l), u(t), u(r), u(b));
        c.drawRoundRect(rect, u(rad), u(rad), p);
        p.setStyle(Paint.Style.FILL);
    }

    private int ink(int alpha) { return theme.light() ? Color.argb(alpha, 20, 22, 35) : Color.argb(alpha, 255, 255, 255); }

    private void glass(Canvas c, float l, float t, float r, float b, float rad, boolean focused) {
        fill(c, l, t, r, b, rad, ink(focused ? 40 : 22));
        stroke(c, l, t, r, b, rad, ink(focused ? 170 : 55), focused ? 4 : 2);
        if (focused) {
            for (int i = 1; i <= 3; i++) stroke(c, l - i * 5, t - i * 5, r + i * 5, b + i * 5, rad + i * 5, withAlpha(ACCENT, 70 / i), 5);
        }
    }

    private static int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    private void txt(Canvas c, String s, float x, float top, float size, Typeface tf, int color, Paint.Align al) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(tf);
        p.setTextSize(u(size));
        p.setColor(color);
        p.setTextAlign(al);
        c.drawText(s, u(x), u(top) - p.getFontMetrics().ascent, p);
        p.setLetterSpacing(0f);
    }

    private String fit(String s, float size, Typeface tf, float maxW) {
        p.setTypeface(tf);
        p.setTextSize(u(size));
        if (p.measureText(s) <= u(maxW)) return s;
        int n = s.length();
        while (n > 1 && p.measureText(s, 0, n) + p.measureText("…") > u(maxW)) n--;
        return s.substring(0, n).trim() + "…";
    }

    /** Largest size (up to max) at which s fits in maxW. */
    private float fitSize(String s, float max, Typeface tf, float maxW) {
        p.setTypeface(tf);
        float size = max;
        p.setTextSize(u(size));
        while (size > max * .45f && p.measureText(s) > u(maxW)) {
            size -= 4;
            p.setTextSize(u(size));
        }
        return size;
    }

    private void spaced(Canvas c, String s, float cx, float top, float size, int color) {
        p.setLetterSpacing(.28f);
        txt(c, s, cx, top, size, outfit500, color, Paint.Align.CENTER);
        p.setLetterSpacing(0f);
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void drawBg(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (theme.mode == Theme.BLACK) { c.drawColor(0xFF000000); return; }
        if (theme.mode == Theme.WHITE) { c.drawColor(0xFFF6F6FA); return; }
        c.drawColor(0xFF07080E);
        int key = theme.c1 * 31 + theme.c2 * 17 + theme.c3 + w;
        if (bgShaders == null || key != bgKey) {
            bgKey = key;
            bgShaders = new Shader[]{
                    new RadialGradient(.15f * w, .2f * h, .62f * w, withAlpha(theme.c1, 140), withAlpha(theme.c1, 0), Shader.TileMode.CLAMP),
                    new RadialGradient(.9f * w, .85f * h, .62f * w, withAlpha(theme.c2, 125), withAlpha(theme.c2, 0), Shader.TileMode.CLAMP),
                    new RadialGradient(.6f * w, 0f, .5f * w, withAlpha(theme.c3, 80), withAlpha(theme.c3, 0), Shader.TileMode.CLAMP),
                    new RadialGradient(.5f * w, .5f * h, .85f * w, 0x00000000, 0xB4000000, Shader.TileMode.CLAMP)};
        }
        p.setStyle(Paint.Style.FILL);
        for (Shader s : bgShaders) {
            p.setShader(s);
            c.drawRect(0, 0, w, h, p);
        }
        p.setShader(null);
    }

    private void cover(Canvas c, float l, float t, float size) {
        rect.set(u(l), u(t), u(l + size), u(t + size));
        if (artShader != null && shownArt != null) {
            matrix.setScale(u(size) / shownArt.getWidth(), u(size) / shownArt.getHeight());
            matrix.postTranslate(u(l), u(t));
            artShader.setLocalMatrix(matrix);
            p.setShader(artShader);
        } else {
            p.setShader(new LinearGradient(u(l), u(t), u(l + size), u(t + size), theme.c1, theme.c2, Shader.TileMode.CLAMP));
        }
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(rect, u(size * .06f), u(size * .06f), p);
        p.setShader(null);
    }

    private void progress(Canvas c, float l, float r, float y) {
        float frac = np.duration > 0 ? Math.min(1f, np.position() / (float) np.duration) : 0f;
        fill(c, l, y, r, y + 6, 3, ink(55));
        float px = l + (r - l) * frac;
        fill(c, l, y, Math.max(px, l + 1), y + 6, 3, theme.text());
        p.setColor(theme.text());
        c.drawCircle(u(px), u(y + 3), u(9), p);
    }

    private int[] ledStops() {
        switch (theme.mode) {
            case Theme.WHITE: return new int[]{Color.rgb(20, 110, 220), Color.rgb(80, 80, 225), Color.rgb(150, 50, 200), Color.rgb(215, 60, 160)};
            case Theme.SONG: return new int[]{lighten(theme.c1, .25f), lighten(theme.c1, .1f), lighten(theme.c2, .1f), lighten(theme.c2, .3f)};
            default: return new int[]{Color.rgb(40, 215, 255), Color.rgb(95, 150, 255), Color.rgb(170, 100, 255), Color.rgb(235, 110, 220)};
        }
    }

    private static int lighten(int c, float f) {
        return Color.rgb((int) (Color.red(c) + (255 - Color.red(c)) * f), (int) (Color.green(c) + (255 - Color.green(c)) * f),
                (int) (Color.blue(c) + (255 - Color.blue(c)) * f));
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t), (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private static int ramp(int[] s, float t) {
        float x = t * (s.length - 1);
        int i = Math.min(s.length - 2, (int) x);
        return mix(s[i], s[i + 1], x - i);
    }

    // ---- screens ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        switch (screen) {
            case EQ: drawEq(c); break;
            case LYRICS: drawLyrics(c); break;
            case SETTINGS: drawSettings(c); break;
            default: drawMenu(c); break;
        }
    }

    private void drawMenu(Canvas c) {
        drawBg(c);
        // now playing pill
        glass(c, 110, 70, 640, 170, 50, false);
        cover(c, 134, 82, 76);
        if (np.hasSession && !np.title.isEmpty()) {
            txt(c, "NOW PLAYING", 232, 94, 15, outfit500, theme.sub(), Paint.Align.LEFT);
            txt(c, fit(np.title, 26, outfit600, 300), 232, 116, 26, outfit600, theme.text(), Paint.Align.LEFT);
            float lvl = 0;
            for (int i = 0; i < 12; i++) lvl += audio.level[i];
            lvl = Math.min(1f, lvl / 6f);
            int[] hs = {16, 30, 22, 36, 18};
            for (int i = 0; i < hs.length; i++) {
                float h = np.playing ? hs[i] * (.5f + .5f * lvl) : 8;
                fill(c, 570 + i * 10, 138 - h / 2, 576 + i * 10, 138 + h / 2, 3, ACCENT);
            }
        } else {
            txt(c, np.hasAccess ? "NOTHING PLAYING" : "ALLOW NOTIFICATION ACCESS", 232, 94, 15, outfit500, theme.sub(), Paint.Align.LEFT);
            txt(c, np.hasAccess ? "Start a song in Spotify" : "Settings > Apps > Special access", 232, 118, 24, outfit500, theme.text(), Paint.Align.LEFT);
        }
        // settings pill
        glass(c, 1590, 80, 1810, 160, 40, focus == 0);
        txt(c, "Settings", 1700, 99, 30, outfit500, theme.text(), Paint.Align.CENTER);
        // cards
        float cw = 540, ch = 500, y0 = 215, x0 = (1920 - (2 * cw + 70)) / 2f;
        for (int k = 0; k < 2; k++) {
            float bx = x0 + k * (cw + 70);
            boolean f = focus == 1 + k;
            glass(c, bx, y0, bx + cw, y0 + ch, 44, f);
            float cx = bx + cw / 2, cy = y0 + 200;
            if (k == 0) {
                int[] stops = ledStops();
                int[] hh = {3, 5, 4, 6, 5, 3, 4};
                for (int col = 0; col < 7; col++) {
                    for (int r = 0; r < 6; r++) {
                        float xx = cx - 105 + col * 30, yy = cy + 80 - (r + 1) * 28;
                        boolean lit = r < hh[col];
                        fill(c, xx, yy, xx + 22, yy + 22, 5, lit ? ramp(stops, r / 5f) : ink(26));
                    }
                }
            } else {
                int[] wd = {300, 240, 320, 200, 280};
                int[] al = {120, 255, 120, 90, 70};
                for (int i = 0; i < 5; i++) {
                    float yy = cy - 100 + i * 42;
                    fill(c, cx - 160, yy, cx - 160 + wd[i], yy + 17, 9, ink(al[i]));
                }
                fill(c, cx - 160, cy - 58, cx - 30, cy - 41, 9, ACCENT);
            }
            txt(c, k == 0 ? "Equaliser" : "Lyrics", cx, y0 + ch - 138, 56, outfit600, theme.text(), Paint.Align.CENTER);
            txt(c, k == 0 ? "Live sound bars" : "Word by word, in time", cx, y0 + ch - 66, 24, outfit300, theme.sub(), Paint.Align.CENTER);
        }
        // transport
        glass(c, 700, 800, 1220, 960, 80, false);
        float cy = 880;
        drawIcon(c, 3, 800, cy, 22, focus == 3);
        drawIcon(c, 5, 1120, cy, 22, focus == 5);
        if (focus == 4) glass(c, 892, cy - 68, 1028, cy + 68, 68, true);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(theme.light() ? 0xFF141623 : 0xF5FFFFFF);
        c.drawCircle(u(960), u(cy), u(58), p);
        int fg = theme.light() ? 0xFFFFFFFF : 0xFF141828;
        if (np.playing) {
            fill(c, 960 - 18, cy - 26, 960 - 5, cy + 26, 3, fg);
            fill(c, 960 + 5, cy - 26, 960 + 18, cy + 26, 3, fg);
        } else {
            tri(c, 960 - 14, cy - 28, 960 - 14, cy + 28, 960 + 24, cy, fg);
        }
    }

    private void drawIcon(Canvas c, int which, float cx, float cy, float s, boolean focused) {
        if (focused) glass(c, cx - 52, cy - 52, cx + 52, cy + 52, 52, true);
        int col = theme.text();
        if (which == 3) {
            fill(c, cx - s, cy - s * .8f, cx - s * .72f, cy + s * .8f, 2, col);
            tri(c, cx + s, cy - s * .85f, cx + s, cy + s * .85f, cx - s * .55f, cy, col);
        } else {
            fill(c, cx + s * .72f, cy - s * .8f, cx + s, cy + s * .8f, 2, col);
            tri(c, cx - s, cy - s * .85f, cx - s, cy + s * .85f, cx + s * .55f, cy, col);
        }
    }

    private final android.graphics.Path path = new android.graphics.Path();

    private void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        path.reset();
        path.moveTo(u(x1), u(y1));
        path.lineTo(u(x2), u(y2));
        path.lineTo(u(x3), u(y3));
        path.close();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private void drawEq(Canvas c) {
        drawBg(c);
        boolean have = np.hasSession && !np.title.isEmpty();
        spaced(c, have ? "NOW PLAYING" : "NOTHING PLAYING", 960, 64, 20, theme.sub());
        String title = have ? np.title : "Start a song in Spotify";
        float ts = fitSize(title, 112, outfit600, 1450);
        txt(c, title, 960, 104 + (112 - ts) * .35f, ts, outfit600, theme.text(), Paint.Align.CENTER);
        String sub = np.artist + (np.album.isEmpty() ? "" : "   ·   " + np.album);
        txt(c, fit(sub, 34, outfit300, 1300), 960, 262, 34, outfit300, theme.sub(), Paint.Align.CENTER);
        cover(c, 110, 90, 110);
        if (look == KINDLE) drawKindle(c, 110, 360, 1810, 930);
        else if (look == MODERN) drawModern(c, 110, 360, 1810, 930);
        else drawLeds(c, 110, 360, 1810, 930);
        progress(c, 110, 1810, 985);
        txt(c, clock(np.position()), 110, 1010, 22, outfit300, theme.sub(), Paint.Align.LEFT);
        txt(c, clock(np.duration), 1810, 1010, 22, outfit300, theme.sub(), Paint.Align.RIGHT);
    }

    private void drawLeds(Canvas c, float x0, float y0, float x1, float y1) {
        int cols = Audio.BANDS, rows = 15;
        float cw = (x1 - x0) / cols, ch = (y1 - y0) / rows, gap = cw * .2f, rad = cw * .16f;
        int[] stops = ledStops();
        int[] rowCol = new int[rows];
        for (int r = 0; r < rows; r++) rowCol[r] = ramp(stops, r / (float) (rows - 1));
        int off = ink(theme.light() ? 18 : 14);
        int peakCol = theme.light() ? 0xDC141623 : 0xDCF5EBFF;
        for (int i = 0; i < cols; i++) {
            int n = Math.round(audio.level[i] * rows);
            if (np.playing) n = Math.max(1, n);
            int pk = Math.min(rows - 1, Math.round(audio.peak[i] * rows));
            float bx = x0 + i * cw + gap / 2;
            for (int r = 0; r < rows; r++) {
                float by = y1 - (r + 1) * ch + gap / 2;
                int color = r < n ? rowCol[r] : (r == pk && pk >= n && np.playing && pk > 0) ? peakCol : off;
                fill(c, bx, by, bx + cw - gap, by + ch - gap, rad, color);
            }
            if (n > 0) {   // soft halo on the top lit block
                int r = n - 1;
                float by = y1 - (r + 1) * ch + gap / 2;
                fill(c, bx - 5, by - 5, bx + cw - gap + 5, by + ch - gap + 5, rad + 4, withAlpha(rowCol[r], 38));
            }
        }
    }

    /** Kindle-styled digital equaliser: fine segments in one cream ink colour straight on the background. */
    private void drawKindle(Canvas c, float x0, float y0, float x1, float y1) {
        final int ink = theme.light() ? 0xFF1B1B1B : 0xFFF1EADA;   // Kindle cream on dark, dark ink on light
        float iy1 = y1 - 40;
        int cols = Audio.BANDS, rows = 22;
        float cw = (x1 - x0) / cols, ch = (iy1 - y0) / rows;
        for (int i = 0; i < cols; i++) {
            int n = Math.round(audio.level[i] * rows);
            if (np.playing) n = Math.max(1, n);
            int pk = Math.min(rows - 1, Math.round(audio.peak[i] * rows));
            float bx = x0 + i * cw + cw * .12f;
            for (int r = 0; r < rows; r++) {
                float by = iy1 - (r + 1) * ch + ch * .2f;
                int color = r < n ? withAlpha(ink, 235) : (r == pk && np.playing && pk > 0) ? ink : withAlpha(ink, 26);
                fill(c, bx, by, bx + cw * .76f, by + ch * .6f, 2, color);
            }
        }
        fill(c, x0, iy1 + 14, x1, iy1 + 17, 1, withAlpha(ink, 200));
    }

    /** Modern look: slim rounded bars with a gradient, a soft glow, floating peak dots and a mirror reflection. */
    private void drawModern(Canvas c, float x0, float y0, float x1, float y1) {
        int cols = Audio.BANDS;
        float base = y1 - 70, top = y0 + 10;
        float cw = (x1 - x0) / cols, bw = cw * .5f;
        int[] g = ledStops();
        Shader shader = new LinearGradient(0, u(base), 0, u(top), g, null, Shader.TileMode.CLAMP);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < cols; i++) {
            float h = Math.max(bw, audio.level[i] * (base - top));
            float cx = x0 + (i + .5f) * cw;
            float l = cx - bw / 2, r = cx + bw / 2;
            p.setShader(shader);
            p.setAlpha(46);
            rect.set(u(l - 7), u(base - h - 7), u(r + 7), u(base + 7));
            c.drawRoundRect(rect, u(bw), u(bw), p);
            p.setAlpha(255);
            rect.set(u(l), u(base - h), u(r), u(base));
            c.drawRoundRect(rect, u(bw / 2), u(bw / 2), p);
            p.setAlpha(60);   // reflection
            float rh = Math.min(64f, h * .3f);
            rect.set(u(l), u(base + 10), u(r), u(base + 10 + rh));
            c.drawRoundRect(rect, u(bw / 2), u(bw / 2), p);
            p.setAlpha(255);
            if (np.playing && audio.peak[i] > audio.level[i] + .03f) {
                p.setShader(null);
                p.setColor(withAlpha(theme.text(), 200));
                c.drawCircle(u(cx), u(base - audio.peak[i] * (base - top) - bw), u(bw * .38f), p);
            }
        }
        p.setShader(null);
        fill(c, x0, base + 2, x1, base + 4, 2, ink(40));
    }

    private int lineIndex(long pos) {
        int idx = -1;
        for (int i = 0; i < lyrics.times.length; i++) {
            if (lyrics.times[i] <= pos) idx = i; else break;
        }
        return idx;
    }

    private StaticLayout layout(String s, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f).setIncludePad(false).build();
    }

    private void drawLyrics(Canvas c) {
        drawBg(c);
        boolean have = np.hasSession && !np.title.isEmpty();
        cover(c, 120, 120, 300);
        tp.setTypeface(outfit600);
        tp.setTextSize(u(54));
        tp.setColor(theme.text());
        StaticLayout tl = layout(have ? np.title : "Nothing playing", tp, (int) u(480));
        float titleH = Math.min(tl.getHeight(), tl.getLineBottom(Math.min(2, tl.getLineCount() - 1)));
        c.save();
        c.translate(u(120), u(462));
        c.clipRect(0, 0, u(480), titleH);
        tl.draw(c);
        c.restore();
        txt(c, fit(np.artist, 28, outfit300, 480), 122, 462 + titleH / S + 14, 28, outfit300, theme.sub(), Paint.Align.LEFT);
        progress(c, 120, 420, 462 + titleH / S + 84);

        float lx = 640, lw = 1160;
        if (!have) { hint(c, lx, "Start a song in Spotify, then come back here."); return; }
        if (lyricsLoading) { hint(c, lx, "Finding lyrics…"); return; }
        if (!lyrics.ok()) { hint(c, lx, "No synced lyrics for this song."); return; }

        long pos = np.position();
        int idx = lineIndex(pos);
        // previous non-empty lines, above the current one
        float curTop = 500;
        tp.setTypeface(lora400);
        tp.setTextSize(u(58));
        float y = curTop - 34;
        int shown = 0;
        for (int i = idx - 1; i >= 0 && shown < 2; i--) {
            String s = lyrics.lines[i];
            if (s.isEmpty()) continue;
            tp.setColor(withAlpha(theme.text(), shown == 0 ? 150 : 80));
            StaticLayout l = layout(s, tp, (int) u(lw));
            y -= l.getHeight() / S;
            c.save();
            c.translate(u(lx), u(y));
            l.draw(c);
            c.restore();
            y -= 26;
            shown++;
        }
        if (idx < 0) { hint(c, lx, "♪"); return; }
        String line = lyrics.lines[idx];
        if (line.isEmpty()) return;   // instrumental gap: leave the page blank
        String[] words = line.split("\\s+");
        long start = lyrics.times[idx];
        long dur = idx + 1 < lyrics.times.length ? lyrics.times[idx + 1] - start : 5000;
        float typeMs = Math.max(600f, Math.min(dur * .8f, 7000f));
        int count = Math.min(words.length, Math.max(1, (int) Math.ceil((pos - start) / typeMs * words.length)));
        if (!np.playing && pos - start < 150) count = Math.max(1, count);
        int reveal = 0;
        for (int k = 0; k < count; k++) reveal += words[k].length() + (k > 0 ? 1 : 0);
        reveal = Math.min(reveal, line.length());

        tp.setTypeface(lora600);
        tp.setTextSize(u(78));
        tp.setColor(theme.text());
        StaticLayout full = layout(line, tp, (int) u(lw));
        float ox = u(lx), oy = u(curTop);
        // soft panel behind the line being typed
        fill(c, lx - 30, curTop - 22, lx + lw + 10, curTop + full.getHeight() / S + 22, 44, ink(theme.light() ? 10 : 12));
        float caretX = ox, caretY = oy, caretH = u(86);
        for (int i = 0; i < full.getLineCount(); i++) {
            int a = full.getLineStart(i), b = Math.min(full.getLineEnd(i), reveal);
            float base = oy + full.getLineBaseline(i);
            if (b > a) {
                String seg = line.substring(a, b);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(lora600);
                p.setTextSize(u(78));
                p.setColor(theme.text());
                p.setTextAlign(Paint.Align.LEFT);
                c.drawText(seg, ox, base, p);
                caretX = ox + p.measureText(seg);
                caretY = base - u(66);
            } else if (reveal <= a && i == 0) {
                caretY = base - u(66);
            }
            if (b >= reveal) break;
        }
        if ((SystemClock.uptimeMillis() / 500) % 2 == 0) {
            p.setColor(ACCENT);
            rect.set(caretX + u(6), caretY, caretX + u(14), caretY + caretH);
            c.drawRoundRect(rect, u(4), u(4), p);
        }
    }

    private void hint(Canvas c, float x, String s) {
        txt(c, s, x, 500, 52, lora400, withAlpha(theme.text(), 120), Paint.Align.LEFT);
    }

    private void drawSettings(Canvas c) {
        drawBg(c);
        txt(c, "Settings", 140, 44, 64, outfit600, theme.text(), Paint.Align.LEFT);
        float cw = 520, ch = 290, gx = 56, x0 = (1920 - (3 * cw + 2 * gx)) / 2f;
        p.setLetterSpacing(.28f);
        txt(c, "THEME", x0 + 4, 150, 22, outfit500, theme.sub(), Paint.Align.LEFT);
        String[] names = {"Black", "White", "Song colours"};
        String[] subs = {"Always pure black", "Always light", "Follows the album art"};
        for (int k = 0; k < 3; k++) {
            float bx = x0 + k * (cw + gx), y0 = 195;
            glass(c, bx, y0, bx + cw, y0 + ch, 36, settingsRow == 0 && settingsFocus == k);
            drawMini(c, k, bx + 20, y0 + 20, cw - 40, 150);
            txt(c, names[k], bx + 32, y0 + 188, 36, outfit600, theme.text(), Paint.Align.LEFT);
            txt(c, subs[k], bx + 32, y0 + 238, 22, outfit300, theme.sub(), Paint.Align.LEFT);
            if (theme.mode == k) tick(c, bx + cw - 56, y0 + ch - 52);
        }
        p.setLetterSpacing(.28f);
        txt(c, "EQUALISER LOOK", x0 + 4, 525, 22, outfit500, theme.sub(), Paint.Align.LEFT);
        String[] lnames = {"Classic", "Kindle", "Modern"};
        String[] lsubs = {"Round LED blocks", "Cream ink digital bars", "Smooth glowing bars"};
        for (int k = 0; k < 3; k++) {
            float bx = x0 + k * (cw + gx), y0 = 570;
            glass(c, bx, y0, bx + cw, y0 + ch, 36, settingsRow == 1 && lookFocus == k);
            drawLookMini(c, k, bx + 20, y0 + 20, cw - 40, 150);
            txt(c, lnames[k], bx + 32, y0 + 188, 36, outfit600, theme.text(), Paint.Align.LEFT);
            txt(c, lsubs[k], bx + 32, y0 + 238, 22, outfit300, theme.sub(), Paint.Align.LEFT);
            if (look == k) tick(c, bx + cw - 56, y0 + ch - 52);
        }
    }

    private void tick(Canvas c, float cx, float cy) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(ACCENT);
        c.drawCircle(u(cx), u(cy), u(24), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(u(5));
        p.setColor(0xFF0A1428);
        path.reset();
        path.moveTo(u(cx - 12), u(cy + 1));
        path.lineTo(u(cx - 4), u(cy + 9));
        path.lineTo(u(cx + 12), u(cy - 10));
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawLookMini(Canvas c, int k, float l, float t, float w, float h) {
        fill(c, l, t, l + w, t + h, 22, 0xFF080A12);
        int[] pat = {3, 5, 4, 6, 5, 3, 4, 6, 5, 4, 3, 5};
        if (k == 0) {
            int[] stops = {Color.rgb(40, 215, 255), Color.rgb(95, 150, 255), Color.rgb(170, 100, 255), Color.rgb(235, 110, 220)};
            int cols = 16;
            float cs = (w - 60) / cols;
            for (int i = 0; i < cols; i++) {
                for (int r = 0; r < 6; r++) {
                    float bx = l + 30 + i * cs, by = t + h - 20 - (r + 1) * 20;
                    fill(c, bx, by, bx + cs - 5, by + 15, 4, r < pat[i % pat.length] ? ramp(stops, r / 5f) : 0x14FFFFFF);
                }
            }
        } else if (k == 1) {
            int cols = 20;
            float cs = (w - 70) / cols;
            for (int i = 0; i < cols; i++) {
                int n = 3 + (pat[i % pat.length] - 3) * 2 + (i % 3);
                for (int r = 0; r < 11; r++) {
                    float bx = l + 35 + i * cs, by = t + h - 26 - (r + 1) * 10;
                    fill(c, bx, by, bx + cs * .76f, by + 6, 1, r < n ? 0xEBF1EADA : 0x1AF1EADA);
                }
            }
            fill(c, l + 35, t + h - 18, l + w - 35, t + h - 15, 1, 0xC8F1EADA);
        } else {
            int[] g = {Color.rgb(40, 215, 255), Color.rgb(95, 150, 255), Color.rgb(170, 100, 255), Color.rgb(235, 110, 220)};
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, u(t + h - 34), 0, u(t + 18), g, null, Shader.TileMode.CLAMP));
            int cols = 18;
            float cs = (w - 60) / cols;
            for (int i = 0; i < cols; i++) {
                float bh = (.25f + .7f * Math.abs((float) Math.sin(i * .7 + 1))) * 98;
                float bx = l + 30 + i * cs;
                rect.set(u(bx), u(t + h - 34 - bh), u(bx + cs * .5f), u(t + h - 34));
                c.drawRoundRect(rect, u(cs * .25f), u(cs * .25f), p);
                p.setAlpha(60);
                rect.set(u(bx), u(t + h - 28), u(bx + cs * .5f), u(t + h - 28 + bh * .18f));
                c.drawRoundRect(rect, u(cs * .25f), u(cs * .25f), p);
                p.setAlpha(255);
            }
            p.setShader(null);
        }
    }

    private void drawMini(Canvas c, int k, float l, float t, float w, float h) {
        int bg = k == 0 ? 0xFF000000 : k == 1 ? 0xFFF6F6FA : 0xFF14183A;
        fill(c, l, t, l + w, t + h, 26, bg);
        int ink = k == 1 ? 0xFF141623 : 0xFFFFFFFF;
        fill(c, l + 30, t + 28, l + 98, t + 96, 10, k == 2 ? theme.c1 : (k == 1 ? 0xFFAA96E6 : 0xFF7858C8));
        fill(c, l + 116, t + 40, l + 260, t + 56, 8, ink);
        fill(c, l + 116, t + 66, l + 200, t + 78, 6, withAlpha(ink, 120));
        int[] stops = k == 1 ? new int[]{Color.rgb(20, 110, 220), Color.rgb(150, 50, 200)}
                : k == 2 ? new int[]{lighten(theme.c1, .25f), lighten(theme.c2, .2f)}
                : new int[]{Color.rgb(70, 220, 255), Color.rgb(255, 90, 200)};
        int n = 14;
        float bw = (w - 60) / (n * 1.4f);
        for (int i = 0; i < n; i++) {
            float hh = (.25f + .7f * Math.abs((float) Math.sin(i * .8 + 1))) * (h - 50) * .5f;
            float bx = l + 30 + i * bw * 1.4f;
            fill(c, bx, t + h - 24 - hh, bx + bw, t + h - 24, bw / 2, mix(stops[0], stops[1], i / (float) (n - 1)));
        }
    }
}
