package com.fezzy.prodigy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground media service: real wake lock + MediaSession + notification with Prev / Play-Pause / Next. */
public class PlaybackService extends Service {
    static volatile String title = "", artist = "";
    static volatile boolean playing = false, running = false;
    static volatile long pos = 0, dur = 0;
    static volatile float speed = 1f;
    static volatile Bitmap art = null;

    private static final String CH = "prodigy_playback";
    private static final int ID = 7;
    private MediaSession ms;
    private PowerManager.WakeLock wl;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "Prodigy · Now playing", NotificationManager.IMPORTANCE_LOW));
        }
        ms = new MediaSession(this, "Prodigy");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { ProdigyBridgePlugin.emit("play", 0); }
            @Override public void onPause() { ProdigyBridgePlugin.emit("pause", 0); }
            @Override public void onSkipToNext() { ProdigyBridgePlugin.emit("next", 0); }
            @Override public void onSkipToPrevious() { ProdigyBridgePlugin.emit("prev", 0); }
            @Override public void onSeekTo(long p) { ProdigyBridgePlugin.emit("seek", p); }
        });
        ms.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prodigy:playback");
        wl.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        String a = in == null ? null : in.getAction();
        if ("prev".equals(a) || "next".equals(a)) ProdigyBridgePlugin.emit(a, 0);
        else if ("toggle".equals(a)) ProdigyBridgePlugin.emit(playing ? "pause" : "play", 0);
        refresh();
        return START_NOT_STICKY;
    }

    private void refresh() {
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
            if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            ms.setMetadata(mb.build());
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
            ms.setPlaybackState(new PlaybackState.Builder().setActions(acts)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? speed : 0f).build());
            if (playing && !wl.isHeld()) wl.acquire(6 * 60 * 60 * 1000L);
            else if (!playing && wl.isHeld()) wl.release();
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
        } catch (Exception e) {
            android.util.Log.w("Prodigy", "refresh failed: " + e);
        }
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl()));
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title == null || title.isEmpty() ? "Prodigy Syncing" : title)
                .setContentText(artist)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (art != null) b.setLargeIcon(art);
        b.addAction(android.R.drawable.ic_media_previous, "Prev", act("prev", 1));
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", act("toggle", 2));
        b.addAction(android.R.drawable.ic_media_next, "Next", act("next", 3));
        b.setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent act(String a, int rc) {
        return PendingIntent.getService(this, rc, new Intent(this, PlaybackService.class).setAction(a), fl());
    }

    private int fl() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override public void onTaskRemoved(Intent i) { stopSelf(); }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (wl != null && wl.isHeld()) wl.release();
            if (ms != null) { ms.setActive(false); ms.release(); }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
