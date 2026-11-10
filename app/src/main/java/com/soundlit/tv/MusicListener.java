package com.soundlit.tv;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app read other apps' media sessions (what Spotify is playing)
 * when it holds notification access, which is granted to this service.
 */
public class MusicListener extends NotificationListenerService {
}
