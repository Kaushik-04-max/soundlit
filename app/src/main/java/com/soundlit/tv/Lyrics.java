package com.soundlit.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-synced lyrics (line start times) fetched from LRCLIB, a free community lyrics database. */
final class Lyrics {
    long[] times = new long[0];
    String[] lines = new String[0];

    boolean ok() { return times.length > 0; }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]");

    /** Never returns null; an empty Lyrics means none were found. Call from a background thread. */
    static Lyrics fetch(String title, String artist, String album, long durationMs) {
        Lyrics none = new Lyrics();
        if (title == null || title.isEmpty()) return none;
        String clean = clean(title);
        String first = artist == null ? "" : artist.split("[,&;]")[0].trim();
        String synced = null;
        try { synced = get(title, artist, album, durationMs); } catch (Exception ignored) { }
        if (synced == null && !clean.equals(title)) {
            try { synced = get(clean, artist, album, durationMs); } catch (Exception ignored) { }
        }
        if (synced == null) try { synced = search("track_name=" + enc(clean) + "&artist_name=" + enc(first), durationMs); } catch (Exception ignored) { }
        if (synced == null) try { synced = search("q=" + enc(clean + " " + first), durationMs); } catch (Exception ignored) { }
        if (synced == null) try { synced = search("track_name=" + enc(clean), durationMs); } catch (Exception ignored) { }
        if (synced == null) try { synced = netease(clean, first, durationMs); } catch (Exception ignored) { }
        return synced == null ? none : parse(synced);
    }

    /** Drops Spotify decorations such as a " - Remastered" suffix or bracketed notes. */
    private static String clean(String t) {
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        t = t.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "");
        return t.trim().isEmpty() ? t : t.trim();
    }

    private static String get(String title, String artist, String album, long durMs) throws Exception {
        StringBuilder u = new StringBuilder("https://lrclib.net/api/get?track_name=").append(enc(title))
                .append("&artist_name=").append(enc(artist));
        if (album != null && !album.isEmpty()) u.append("&album_name=").append(enc(album));
        if (durMs > 0) u.append("&duration=").append(durMs / 1000);
        String body = http(u.toString());
        if (body == null) return null;
        String s = new JSONObject(body).optString("syncedLyrics", "");
        return s.isEmpty() || s.equals("null") ? null : s;
    }

    /** Picks the synced result whose length is closest to the playing track (within 6 s when known). */
    private static String search(String query, long durMs) throws Exception {
        String body = http("https://lrclib.net/api/search?" + query);
        if (body == null) return null;
        JSONArray arr = new JSONArray(body);
        String best = null;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String s = o.optString("syncedLyrics", "");
            if (s.isEmpty() || s.equals("null")) continue;
            double diff = durMs > 0 ? Math.abs(o.optDouble("duration", 0) - durMs / 1000.0) : 0;
            if (durMs > 0 && diff > 6) continue;
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        return best;
    }

    /** Backup source: NetEase Cloud Music's public lyric endpoint. */
    private static String netease(String title, String artist, long durMs) throws Exception {
        String body = http("https://music.163.com/api/search/get?type=1&limit=5&s=" + enc(title + " " + artist));
        if (body == null) return null;
        JSONObject res = new JSONObject(body).optJSONObject("result");
        if (res == null) return null;
        JSONArray songs = res.optJSONArray("songs");
        if (songs == null) return null;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.getJSONObject(i);
            double dur = song.optDouble("duration", 0) / 1000.0;
            if (durMs > 0 && dur > 0 && Math.abs(dur - durMs / 1000.0) > 6) continue;
            String lb = http("https://music.163.com/api/song/lyric?lv=1&id=" + song.optLong("id"));
            if (lb == null) continue;
            JSONObject lrc = new JSONObject(lb).optJSONObject("lrc");
            String s = lrc == null ? "" : lrc.optString("lyric", "");
            if (s.matches("(?s).*\\[\\d+:\\d+.*")) return s;
        }
        return null;
    }

    static Lyrics parse(String lrc) {
        List<long[]> t = new ArrayList<>();
        List<String> l = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                stamps.add((long) ((Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2))) * 1000));
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long s : stamps) {
                t.add(new long[]{s});
                l.add(text);
            }
        }
        // sort by time (a line can carry several stamps)
        Integer[] idx = new Integer[t.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(t.get(a)[0], t.get(b)[0]));
        Lyrics out = new Lyrics();
        out.times = new long[idx.length];
        out.lines = new String[idx.length];
        for (int i = 0; i < idx.length; i++) {
            out.times[i] = t.get(idx[i])[0];
            out.lines[i] = l.get(idx[i]);
        }
        return out;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Soundlit-TV/1.0 (personal TV app)");
        c.setRequestProperty("Referer", "https://music.163.com");
        if (c.getResponseCode() != 200) return null;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
