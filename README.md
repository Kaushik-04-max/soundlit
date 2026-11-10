# Soundlit - Equaliser and lyrics for Android TV

A Spotify companion for Android / Google TV, made on a Sony Bravia (Android 14).
It reads what Spotify is playing and shows it in three ways:

- **Equaliser** – a bottom-up LED block grid that reacts to the real audio, with the song title, artist, album art and progress.
- **Lyrics** – time-synced lyrics typed out word by word, Kindle style, in a book serif font.
- **Settings** – theme: permanent Black, White, or *Song colours* (follows the album art).

The menu has Previous / Play-Pause / Next controls. No on-screen back button: the remote's Back key works everywhere.

## How it works

| Piece | Source |
| --- | --- |
| Now playing + controls | `MediaSessionManager` with a notification-listener (needs notification access) |
| Audio bars | `android.media.audiofx.Visualizer` on the output mix (needs the microphone permission; falls back to a simulated animation if the TV blocks it) |
| Lyrics | [LRCLIB](https://lrclib.net) (synced), with NetEase Cloud Music as a backup |
| Fonts | Outfit and Lora (SIL Open Font License) |

Everything is drawn on one custom `View` at 1920×1080 and scaled, so it fits any TV.

## Build

Requires JDK 17 and the Android SDK (API 34).

```
./gradlew assembleRelease
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.soundlit.tv android.permission.RECORD_AUDIO
adb shell cmd notification allow_listener com.soundlit.tv/com.soundlit.tv.MusicListener
```

Then open **Soundlit** from the TV's app row and start a song in Spotify.

## Notes

- Lyrics depend on community databases; some songs have none.
- Not affiliated with Spotify.
