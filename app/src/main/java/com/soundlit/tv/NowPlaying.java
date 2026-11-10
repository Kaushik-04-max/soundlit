package com.soundlit.tv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** What is playing right now (Spotify, normally) and the controls for it, read from Android's media sessions. */
final class NowPlaying {
    interface Listener { void changed(); }

    private static final String TAG = "Equaliser.NowPlaying";

    volatile String title = "", artist = "", album = "";
    volatile Bitmap art;
    volatile long duration;
    volatile boolean playing;
    volatile boolean hasSession;
    volatile boolean hasAccess = true;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private ComponentName listenerName;
    private MediaController ctl;
    private long statePos, stateTime;
    private float speed = 1f;
    private String artUri = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { readMeta(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { readState(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };

    NowPlaying(Context ctx, Listener l) {
        this.ctx = ctx;
        this.listener = l;
    }

    void start() {
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerName = new ComponentName(ctx, MusicListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listenerName, main);
            hasAccess = true;
            refresh();
        } catch (SecurityException e) {
            hasAccess = false;
            Log.w(TAG, "no notification access", e);
            listener.changed();
        }
    }

    void stop() {
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (Exception ignored) {
        }
        if (ctl != null) {
            try { ctl.unregisterCallback(callback); } catch (Exception ignored) { }
            ctl = null;
        }
    }

    private void refresh() {
        try {
            pick(msm.getActiveSessions(listenerName));
            hasAccess = true;
        } catch (SecurityException e) {
            hasAccess = false;
            listener.changed();
        }
    }

    private void pick(List<MediaController> list) {
        MediaController chosen = null;
        if (list != null) {
            for (MediaController c : list) {
                if (c.getPackageName().toLowerCase().contains("spotify")) { chosen = c; break; }
            }
            if (chosen == null) {
                for (MediaController c : list) {
                    PlaybackState s = c.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { chosen = c; break; }
                }
            }
            if (chosen == null && !list.isEmpty()) chosen = list.get(0);
        }
        if (chosen == null) {
            if (ctl != null) { try { ctl.unregisterCallback(callback); } catch (Exception ignored) { } }
            ctl = null;
            hasSession = false;
            playing = false;
            listener.changed();
            return;
        }
        if (ctl == null || !ctl.getSessionToken().equals(chosen.getSessionToken())) {
            if (ctl != null) { try { ctl.unregisterCallback(callback); } catch (Exception ignored) { } }
            ctl = chosen;
            ctl.registerCallback(callback, main);
        }
        hasSession = true;
        readMeta();
        readState();
    }

    private void readMeta() {
        if (ctl == null) return;
        MediaMetadata m = ctl.getMetadata();
        if (m == null) return;
        title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        artist = nz(a);
        album = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b != null) {
            art = b;
        } else {
            String uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri != null && !uri.equals(artUri)) {
                artUri = uri;
                loadArt(uri);
            }
        }
        listener.changed();
    }

    private void readState() {
        if (ctl == null) return;
        PlaybackState s = ctl.getPlaybackState();
        if (s == null) return;
        playing = s.getState() == PlaybackState.STATE_PLAYING;
        statePos = s.getPosition();
        stateTime = s.getLastPositionUpdateTime();
        speed = s.getPlaybackSpeed();
        listener.changed();
    }

    /** Current position in ms, advanced locally between updates. */
    long position() {
        long p = statePos;
        if (playing) p += (long) ((SystemClock.elapsedRealtime() - stateTime) * speed);
        if (duration > 0 && p > duration) p = duration;
        return Math.max(0, p);
    }

    void play() { if (ctl != null) ctl.getTransportControls().play(); }
    void pause() { if (ctl != null) ctl.getTransportControls().pause(); }
    void next() { if (ctl != null) ctl.getTransportControls().skipToNext(); }
    void prev() { if (ctl != null) ctl.getTransportControls().skipToPrevious(); }
    void toggle() { if (playing) pause(); else play(); }

    private void loadArt(final String uri) {
        new Thread(() -> {
            InputStream in = null;
            try {
                if (uri.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    in = c.getInputStream();
                } else {
                    in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
                }
                final Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null && uri.equals(artUri)) {
                    art = b;
                    main.post(listener::changed);
                }
            } catch (Exception e) {
                Log.w(TAG, "art load failed: " + e);
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) { }
            }
        }, "art").start();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
