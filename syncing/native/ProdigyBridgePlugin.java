package com.fezzy.prodigy;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.content.ContentUris;
import android.util.Base64;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import com.getcapacitor.JSArray;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.ActivityCallback;
import androidx.activity.result.ActivityResult;

@CapacitorPlugin(name = "ProdigyBridge", permissions = {
    @Permission(alias = "audio", strings = {Manifest.permission.READ_MEDIA_AUDIO}),
    @Permission(alias = "storage", strings = {Manifest.permission.READ_EXTERNAL_STORAGE}),
    @Permission(alias = "mic", strings = {Manifest.permission.RECORD_AUDIO})
})
public class ProdigyBridgePlugin extends Plugin {
    private static ProdigyBridgePlugin inst;

    @Override
    public void load() { inst = this; }

    /** Called by the service (notification / lock-screen buttons) to reach the web player. */
    static void emit(String action, long position) {
        ProdigyBridgePlugin p = inst;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("position", position);
        p.notifyListeners("action", o);
    }

    /** V53: the in-app YouTube browser asks the web layer to download the page it is on. */
    static void emitYt(String url, String kind) { emitYt(url, kind, 0); }

    static void emitYt(String url, String kind, int itag) {
        ProdigyBridgePlugin p = inst;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("url", url);
        o.put("kind", kind);
        o.put("itag", itag);
        p.notifyListeners("ytBrowserDl", o);
    }

    private static long num(PluginCall c, String k) {
        Double d = c.getDouble(k, 0.0);
        return d == null ? 0L : d.longValue();
    }

    @PluginMethod
    public void update(PluginCall c) {
        try {
            PlaybackService.title = c.getString("title", "");
            PlaybackService.artist = c.getString("artist", "");
            PlaybackService.playing = Boolean.TRUE.equals(c.getBoolean("playing", false));
            PlaybackService.pos = num(c, "position");
            PlaybackService.dur = num(c, "duration");
            Double sp = c.getDouble("speed", 1.0);
            PlaybackService.speed = sp == null ? 1f : sp.floatValue();
            if (c.hasOption("art")) {
                String a = c.getString("art", "");
                Bitmap bm = null;
                if (a != null && a.contains(",")) {
                    byte[] b = Base64.decode(a.substring(a.indexOf(',') + 1), Base64.DEFAULT);
                    bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                }
                PlaybackService.art = bm;
            }
            Context x = getContext();
            Intent i = new Intent(x, PlaybackService.class).setAction("UPDATE");
            if (PlaybackService.running) x.startService(i);
            else ContextCompat.startForegroundService(x, i);
            c.resolve();
        } catch (Exception e) {
            c.reject("update failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall c) {
        try {
            PlaybackService.playing = false;
            getContext().stopService(new Intent(getContext(), PlaybackService.class));
            c.resolve();
        } catch (Exception e) {
            c.reject("stop failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void requestPerms(PluginCall c) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                    && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4201);
            }
            c.resolve();
        } catch (Exception e) {
            c.reject("perms failed: " + e.getMessage());
        }
    }

    /** V21: current microphone permission state: granted | prompt | prompt-with-rationale | denied */
    private String micStateStr() {
        PermissionState s = getPermissionState("mic");
        return s == null ? "denied" : s.toString();
    }

    @PluginMethod
    public void micState(PluginCall c) {
        JSObject o = new JSObject();
        o.put("state", micStateStr());
        c.resolve(o);
    }

    /** V20/V21: microphone permission for the voice recorder. Resolves {granted:boolean, state:string}. */
    @PluginMethod
    public void requestMic(PluginCall c) {
        if (getPermissionState("mic") == PermissionState.GRANTED) {
            JSObject o = new JSObject(); o.put("granted", true); o.put("state", "granted"); c.resolve(o);
            return;
        }
        requestPermissionForAlias("mic", c, "micPermCb");
    }

    @PermissionCallback
    private void micPermCb(PluginCall c) {
        JSObject o = new JSObject();
        o.put("granted", getPermissionState("mic") == PermissionState.GRANTED);
        o.put("state", micStateStr());
        c.resolve(o);
    }

    /** V21: opens this app's Android settings page (for "denied for good" permissions). */
    @PluginMethod
    public void openAppSettings(PluginCall c) {
        try {
            Context x = getContext();
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + x.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            x.startActivity(i);
            c.resolve();
        } catch (Exception e) {
            c.reject("app settings: " + e.getMessage());
        }
    }

    @PluginMethod
    public void batterySettings(PluginCall c) {
        Context x = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + x.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            x.startActivity(i);
            c.resolve();
        } catch (Exception e) {
            try {
                Intent j = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                x.startActivity(j);
                c.resolve();
            } catch (Exception e2) {
                c.reject("battery settings: " + e2.getMessage());
            }
        }
    }

    @PluginMethod
    public void openApp(PluginCall c) {
        String pkg = c.getString("pkg");
        if (pkg == null || pkg.isEmpty()) { c.reject("pkg missing"); return; }
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { c.reject("Not installed or not visible: " + pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        c.resolve();
    }

    /** V65: open a deep link (Waze) in a given app, or any URL. */
    @PluginMethod
    public void openUri(PluginCall c) {
        String u = c.getString("uri");
        if (u == null || u.isEmpty()) { c.reject("uri missing"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            String pk = c.getString("pkg");
            if (pk != null && !pk.isEmpty()) i.setPackage(pk);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            c.resolve();
        } catch (Throwable e) { c.reject("Could not open: " + e.getMessage()); }
    }

    // ---------- V14: browse + read music from the phone ----------
    private static Uri audioCol() {
        return Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    private static Uri downloadsCol() {
        return Build.VERSION.SDK_INT >= 29 ? MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : audioCol();
    }

    private static String alias() { return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage"; }

    @PluginMethod
    public void listAudio(PluginCall c) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), c, "audioPermCb");
            return;
        }
        doList(c);
    }

    @PermissionCallback
    private void audioPermCb(PluginCall c) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) doList(c);
        else c.reject("Music permission denied: allow it in Android settings > Apps > Prodigy Syncing > Permissions");
    }

    private void doList(final PluginCall c) {
        new Thread(() -> {
            try {
                JSArray arr = new JSArray();
                String rel = Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
                String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, rel};
                try (Cursor cur = getContext().getContentResolver().query(audioCol(), proj,
                        MediaStore.Audio.Media.DURATION + ">=?", new String[]{"30000"}, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                    while (cur != null && cur.moveToNext()) {
                        JSObject o = new JSObject();
                        o.put("id", String.valueOf(cur.getLong(0)));
                        o.put("name", cur.getString(1) == null ? "audio" : cur.getString(1));
                        o.put("title", cur.getString(2) == null ? "" : cur.getString(2));
                        String ar = cur.getString(3);
                        o.put("artist", ar == null || ar.equals("<unknown>") ? "" : ar);
                        o.put("album", cur.getString(4) == null ? "" : cur.getString(4));
                        o.put("duration", cur.getLong(5));
                        o.put("size", cur.getLong(6));
                        o.put("mime", cur.getString(7) == null ? "" : cur.getString(7));
                        String f = cur.getString(8) == null ? "" : cur.getString(8);
                        if (Build.VERSION.SDK_INT < 29 && f.contains("/")) { f = f.substring(0, f.lastIndexOf('/')); f = f.substring(f.lastIndexOf('/') + 1); }
                        o.put("folder", f);
                        arr.put(o);
                    }
                }
                JSObject r = new JSObject();
                r.put("items", arr);
                c.resolve(r);
            } catch (Exception e) {
                c.reject("list failed: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void readChunk(PluginCall c) {
        try {
            long id = Long.parseLong(c.getString("id", "0"));
            long off = num(c, "offset");
            int len = (int) Math.max(1, Math.min(2097152, num(c, "length")));
            Uri u = ContentUris.withAppendedId(Boolean.TRUE.equals(c.getBoolean("dl", false)) ? downloadsCol() : audioCol(), id);
            ParcelFileDescriptor pfd = getContext().getContentResolver().openFileDescriptor(u, "r");
            if (pfd == null) { c.reject("cannot open file"); return; }
            try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                FileChannel ch = in.getChannel();
                ch.position(off);
                ByteBuffer bb = ByteBuffer.allocate(len);
                int n = 0, r;
                while (n < len && (r = ch.read(bb)) > 0) n += r;
                JSObject o = new JSObject();
                o.put("n", n);
                o.put("eof", n < len);
                o.put("b64", Base64.encodeToString(bb.array(), 0, n, Base64.NO_WRAP));
                c.resolve(o);
            }
        } catch (Exception e) {
            c.reject("read failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void ytDownload(PluginCall c) {
        final String url = c.getString("url", "");
        if (url.isEmpty()) { c.reject("no url"); return; }
        new Thread(() -> {
            try {
                YtFetch.Result r = YtFetch.run(getContext(), url, c.getInt("itag", 0), (p, msg) -> {
                    JSObject o = new JSObject(); o.put("p", p); o.put("msg", msg);
                    notifyListeners("ytProgress", o);
                });
                JSObject o = new JSObject();
                o.put("id", String.valueOf(r.id)); o.put("name", r.name);
                o.put("size", r.size); o.put("mime", r.mime); o.put("dl", r.dl);
                o.put("title", r.title); o.put("artist", r.artist); o.put("album", r.album); o.put("year", r.year); o.put("art", r.art); o.put("artErr", r.artErr);
                c.resolve(o);
            } catch (Throwable e) {
                c.reject("yt failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V56: list audio/video formats with sizes. Resolves {title, items:[{k,itag,ext,label,size}]} */
    @PluginMethod
    public void ytFormats(PluginCall c) {
        final String url = c.getString("url", "");
        if (url.isEmpty()) { c.reject("no url"); return; }
        new Thread(() -> {
            try {
                String[] t = {""};
                java.util.List<String[]> l = YtFetch.formats(url, t);
                JSArray a = new JSArray();
                for (String[] e : l) {
                    JSObject o = new JSObject();
                    o.put("k", e[0]); o.put("itag", Integer.parseInt(e[1])); o.put("ext", e[2]);
                    o.put("label", e[3]); o.put("size", Long.parseLong(e[4]));
                    a.put(o);
                }
                JSObject r = new JSObject(); r.put("title", t[0]); r.put("items", a);
                c.resolve(r);
            } catch (Throwable e) {
                c.reject("formats failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V21: can this link be saved as a video file? resolves {ok:boolean, height:int, title:string} */
    @PluginMethod
    public void ytCan(PluginCall c) {
        final String url = c.getString("url", "");
        if (url.isEmpty()) { c.reject("no url"); return; }
        new Thread(() -> {
            try {
                String[] r = YtFetch.canVideo(url);
                int h = 0;
                try { h = Integer.parseInt(r[1]); } catch (Exception ignored) {}
                JSObject o = new JSObject();
                o.put("ok", h > 0); o.put("height", h); o.put("title", r[0]);
                c.resolve(o);
            } catch (Throwable e) {
                c.reject("check failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V21: download the video file into Download/Prodigy. */
    @PluginMethod
    public void ytVideo(PluginCall c) {
        final String url = c.getString("url", "");
        if (url.isEmpty()) { c.reject("no url"); return; }
        new Thread(() -> {
            try {
                YtFetch.Result r = YtFetch.runVideo(getContext(), url, c.getInt("itag", 0), (p, msg) -> {
                    JSObject o = new JSObject(); o.put("p", p); o.put("msg", msg);
                    notifyListeners("ytProgress", o);
                });
                JSObject o = new JSObject();
                o.put("id", String.valueOf(r.id)); o.put("name", r.name);
                o.put("size", r.size); o.put("mime", r.mime); o.put("dl", r.dl);
                c.resolve(o);
            } catch (Throwable e) {
                c.reject("video failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V75: 720p / 1080p video (video-only stream + 128 kbps audio merged on the phone) into Download/Prodigy. */
    @PluginMethod
    public void ytMerge(PluginCall c) {
        final String url = c.getString("url", "");
        if (url.isEmpty()) { c.reject("no url"); return; }
        new Thread(() -> {
            try {
                YtFetch.Result r = YtFetch.runMerge(getContext(), url, c.getInt("itag", 0), c.getInt("aitag", 0), (p, msg) -> {
                    JSObject o = new JSObject(); o.put("p", p); o.put("msg", msg);
                    notifyListeners("ytProgress", o);
                });
                JSObject o = new JSObject();
                o.put("id", String.valueOf(r.id)); o.put("name", r.name);
                o.put("size", r.size); o.put("mime", r.mime); o.put("dl", r.dl);
                c.resolve(o);
            } catch (Throwable e) {
                c.reject("merge failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V76: save one file (base64) and optionally open the share sheet. Images go to Pictures/Prodigy, everything else to Download/Prodigy. */
    @PluginMethod
    public void shareFile(PluginCall c) {
        try {
            final String b64 = c.getString("data", "");
            final String name = c.getString("name", "file");
            final String mime = c.getString("mime", "application/octet-stream");
            final String text = c.getString("text", "");
            final boolean share = !Boolean.FALSE.equals(c.getBoolean("share", true));
            if (b64.isEmpty() || getActivity() == null) { c.reject("no data"); return; }
            if (Build.VERSION.SDK_INT < 29) { c.reject("needs Android 10 or newer"); return; }
            final Uri u = putShared(Base64.decode(b64, Base64.DEFAULT), name, mime);
            if (u == null) { c.reject("could not create file"); return; }
            if (share) {
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType(mime);
                it.putExtra(Intent.EXTRA_STREAM, u);
                if (!text.isEmpty()) it.putExtra(Intent.EXTRA_TEXT, text);
                it.setClipData(android.content.ClipData.newRawUri("file", u));
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent ch = Intent.createChooser(it, "Share");
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(ch);
            }
            JSObject out = new JSObject();
            out.put("uri", u.toString());
            c.resolve(out);
        } catch (Throwable e) {
            c.reject("share failed: " + e.getMessage());
        }
    }

    /** V76: share several files at once. items: [{data(base64), name, mime}] */
    @PluginMethod
    public void shareFiles(PluginCall c) {
        try {
            final JSArray arr = c.getArray("items");
            if (arr == null || arr.length() == 0 || getActivity() == null) { c.reject("nothing to share"); return; }
            if (Build.VERSION.SDK_INT < 29) { c.reject("needs Android 10 or newer"); return; }
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            String mime0 = null; boolean same = true;
            for (int i = 0; i < arr.length() && i < 30; i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String mime = o.optString("mime", "application/octet-stream");
                Uri u = putShared(Base64.decode(o.optString("data", ""), Base64.DEFAULT), o.optString("name", "file" + i), mime);
                if (u != null) uris.add(u);
                if (mime0 == null) mime0 = mime; else if (!mime0.equals(mime)) same = false;
            }
            if (uris.isEmpty()) { c.reject("could not create files"); return; }
            Intent it = new Intent(Intent.ACTION_SEND_MULTIPLE);
            it.setType(same && mime0 != null ? mime0 : "*/*");
            it.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(it, "Share");
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(ch);
            JSObject out = new JSObject();
            out.put("count", uris.size());
            c.resolve(out);
        } catch (Throwable e) {
            c.reject("share failed: " + e.getMessage());
        }
    }

    private Uri putShared(byte[] raw, String name, String mime) throws Exception {
        final android.content.ContentResolver cr = getContext().getContentResolver();
        final boolean img = mime.startsWith("image/");
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (img ? android.os.Environment.DIRECTORY_PICTURES : android.os.Environment.DIRECTORY_DOWNLOADS) + "/Prodigy");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri u = cr.insert(img ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : downloadsCol(), cv);
        if (u == null) return null;
        try (java.io.OutputStream os = cr.openOutputStream(u)) { os.write(raw); }
        android.content.ContentValues fin = new android.content.ContentValues();
        fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, fin, null, null);
        return u;
    }

    @PluginMethod
    public void ytPlaylist(PluginCall c) {
        final String url = c.getString("url", "");
        final int max = c.getInt("max", 100);
        new Thread(() -> {
            try {
                java.util.List<String[]> l = YtFetch.playlist(url, max);
                JSArray a = new JSArray();
                for (String[] e : l) { JSObject o = new JSObject(); o.put("url", e[0]); o.put("title", e[1]); a.put(o); }
                JSObject r = new JSObject(); r.put("items", a); c.resolve(r);
            } catch (Throwable e) {
                c.reject("playlist failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V28: YouTube search (NewPipe). Resolves {items:[{url,title,by,dur,views,thumb,type,count}]} */
    @PluginMethod
    public void ytSearch(PluginCall c) {
        final String q = c.getString("q", "");
        final String kind = c.getString("kind", "videos");
        if (q.trim().isEmpty()) { c.reject("empty search"); return; }
        new Thread(() -> {
            try {
                java.util.List<String[]> l = YtFetch.search(q.trim(), kind, 30);
                JSArray a = new JSArray();
                for (String[] e : l) {
                    JSObject o = new JSObject();
                    o.put("url", e[0]); o.put("title", e[1]); o.put("by", e[2]);
                    o.put("dur", Long.parseLong(e[3])); o.put("views", Long.parseLong(e[4]));
                    o.put("thumb", e[5]); o.put("type", e[6]); o.put("count", Long.parseLong(e[7]));
                    a.put(o);
                }
                JSObject r = new JSObject(); r.put("items", a); c.resolve(r);
            } catch (Throwable e) {
                c.reject("search failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }).start();
    }

    /** V30: full-screen Chrome-style browser (empty url opens the chosen search engine). */
    @PluginMethod
    public void browse(PluginCall c) {
        try {
            final String u = c.getString("url", "");
            if (getActivity() == null) { c.reject("no activity"); return; }
            WebSheet.show(getActivity(), u);
            c.resolve();
        } catch (Throwable e) {
            c.reject("browse failed: " + e.getMessage());
        }
    }

    /** V31: share a generated lyric image (base64 JPEG) to WhatsApp / Facebook / e-mail / any app, or just save it (Pictures/Prodigy). */
    @PluginMethod
    public void shareImage(PluginCall c) {
        try {
            final String b64 = c.getString("data", "");
            final String name = c.getString("name", "prodigy_lyrics.jpg");
            final String text = c.getString("text", "");
            final String target = c.getString("target", "any");
            if (b64.isEmpty() || getActivity() == null) { c.reject("no image"); return; }
            if (Build.VERSION.SDK_INT < 29) { c.reject("needs Android 10 or newer"); return; }
            final byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            final android.content.ContentResolver cr = getContext().getContentResolver();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Prodigy");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            final Uri u = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (u == null) { c.reject("could not create image"); return; }
            try (java.io.OutputStream os = cr.openOutputStream(u)) { os.write(raw); }
            android.content.ContentValues fin = new android.content.ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, fin, null, null);
            JSObject out = new JSObject();
            out.put("uri", u.toString());
            if ("save".equals(target)) { c.resolve(out); return; }
            Intent it;
            if ("email".equals(target)) {
                it = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
                it.putExtra(Intent.EXTRA_SUBJECT, text);
            } else {
                it = new Intent(Intent.ACTION_SEND);
                it.setType("image/jpeg");
            }
            it.putExtra(Intent.EXTRA_STREAM, u);
            it.putExtra(Intent.EXTRA_TEXT, text);
            it.setClipData(android.content.ClipData.newRawUri("image", u));
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String[] pk = "whatsapp".equals(target) ? new String[]{"com.whatsapp", "com.whatsapp.w4b"}
                    : "facebook".equals(target) ? new String[]{"com.facebook.katana"} : new String[0];
            boolean sent = false;
            for (String p : pk) {
                try {
                    Intent t = new Intent(it);
                    t.setPackage(p);
                    t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(t);
                    sent = true;
                    break;
                } catch (Throwable ignore) { }
            }
            if (!sent) {
                Intent ch = Intent.createChooser(it, "Share lyric image");
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(ch);
                out.put("fallback", pk.length > 0);
            }
            c.resolve(out);
        } catch (Throwable e) {
            c.reject("share failed: " + e.getMessage());
        }
    }

    /** V33: save a file (base64) to Download/Prodigy and optionally open the share sheet. Used by Export. */
    /** V77: guaranteed save. Opens the system "save as" screen so the file lands wherever you pick. */
    @PluginMethod
    public void saveAs(PluginCall c) {
        try {
            final String b64 = c.getString("data", "");
            final String name = c.getString("name", "prodigy-backup.json");
            final String mime = c.getString("mime", "application/json");
            if (b64.isEmpty() || getActivity() == null) { c.reject("no data"); return; }
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType(mime);
            it.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(c, it, "saveAsDone");
        } catch (Throwable e) {
            c.reject("saveAs failed: " + e.getMessage());
        }
    }

    @ActivityCallback
    private void saveAsDone(PluginCall c, ActivityResult r) {
        if (c == null) return;
        try {
            if (r == null || r.getResultCode() != android.app.Activity.RESULT_OK || r.getData() == null || r.getData().getData() == null) {
                JSObject o = new JSObject();
                o.put("cancelled", true);
                c.resolve(o);
                return;
            }
            Uri u = r.getData().getData();
            byte[] raw = Base64.decode(c.getString("data", ""), Base64.DEFAULT);
            try (java.io.OutputStream os = getContext().getContentResolver().openOutputStream(u, "wt")) {
                if (os == null) throw new java.io.IOException("no output stream");
                os.write(raw);
            }
            JSObject o = new JSObject();
            o.put("saved", true);
            o.put("uri", u.toString());
            c.resolve(o);
        } catch (Throwable e) {
            c.reject("saveAs failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void saveFile(PluginCall c) {
        try {
            final String b64 = c.getString("data", "");
            final String name = c.getString("name", "prodigy-backup.json");
            final String mime = c.getString("mime", "application/octet-stream");
            final boolean share = Boolean.TRUE.equals(c.getBoolean("share", false));
            if (b64.isEmpty() || getActivity() == null) { c.reject("no data"); return; }
            if (Build.VERSION.SDK_INT < 29) { c.reject("needs Android 10 or newer"); return; }
            final byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            final android.content.ContentResolver cr = getContext().getContentResolver();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Prodigy");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u0 = null;
            Throwable lastErr = null;
            for (int attempt = 0; attempt < 2 && u0 == null; attempt++) {
                try {
                    if (attempt == 1) {
                        int dot = name.lastIndexOf('.');
                        String stamp = "-" + System.currentTimeMillis();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, dot > 0 ? name.substring(0, dot) + stamp + name.substring(dot) : name + stamp);
                    }
                    u0 = cr.insert(downloadsCol(), cv);
                } catch (Throwable e1) { lastErr = e1; }
            }
            if (u0 == null) { c.reject("could not create file" + (lastErr == null ? "" : ": " + lastErr.getMessage())); return; }
            final Uri u = u0;
            try (java.io.OutputStream os = cr.openOutputStream(u)) {
                if (os == null) throw new java.io.IOException("no output stream");
                os.write(raw);
            }
            android.content.ContentValues fin = new android.content.ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, fin, null, null);
            if (share) {
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType(mime);
                it.putExtra(Intent.EXTRA_STREAM, u);
                it.putExtra(Intent.EXTRA_SUBJECT, name);
                it.setClipData(android.content.ClipData.newRawUri("file", u));
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent ch = Intent.createChooser(it, "Share backup");
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(ch);
            }
            JSObject out = new JSObject();
            out.put("uri", u.toString());
            c.resolve(out);
        } catch (Throwable e) {
            c.reject("save failed: " + e.getMessage());
        }
    }

    /** V38: biometric unlock for Secure Conduit + screenshot block. */
    @PluginMethod
    public void bioState(PluginCall c) {
        JSObject o = new JSObject();
        o.put("can", ConduitBio.can(getContext()));
        o.put("on", ConduitBio.enrolled(getContext()));
        c.resolve(o);
    }

    @PluginMethod
    public void bioEnroll(final PluginCall c) {
        final String s = c.getString("secret", "");
        getActivity().runOnUiThread(new Runnable() { @Override public void run() {
            ConduitBio.enroll(getActivity(), s, new ConduitBio.Cb() {
                @Override public void ok(String v) { c.resolve(); }
                @Override public void fail(String m) { c.reject(m); }
            });
        } });
    }

    @PluginMethod
    public void bioUnlock(final PluginCall c) {
        getActivity().runOnUiThread(new Runnable() { @Override public void run() {
            ConduitBio.unlock(getActivity(), new ConduitBio.Cb() {
                @Override public void ok(String v) { JSObject o = new JSObject(); o.put("secret", v); c.resolve(o); }
                @Override public void fail(String m) { c.reject(m); }
            });
        } });
    }

    @PluginMethod
    public void bioForget(PluginCall c) {
        ConduitBio.forget(getContext());
        c.resolve();
    }

    @PluginMethod
    public void secure(PluginCall c) {
        final boolean on = Boolean.TRUE.equals(c.getBoolean("on", false));
        getActivity().runOnUiThread(new Runnable() { @Override public void run() {
            int f = android.view.WindowManager.LayoutParams.FLAG_SECURE;
            getActivity().getWindow().setFlags(on ? f : 0, f);
        } });
        c.resolve();
    }

    /** V41: torch (flashlight). setTorchMode needs no camera permission. */
    private String torchCam() {
        try {
            android.hardware.camera2.CameraManager m = (android.hardware.camera2.CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            for (String id : m.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics cc = m.getCameraCharacteristics(id);
                Boolean f = cc.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lf = cc.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (f != null && f.booleanValue() && lf != null && lf.intValue() == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Exception e) { }
        return null;
    }

    @PluginMethod
    public void torchInfo(PluginCall c) {
        JSObject o = new JSObject();
        o.put("has", torchCam() != null);
        c.resolve(o);
    }

    @PluginMethod
    public void torch(PluginCall c) {
        try {
            String id = torchCam();
            if (id == null) { c.reject("No flash on this phone"); return; }
            Boolean b = c.getBoolean("on", false);
            boolean on = b != null && b.booleanValue();
            android.hardware.camera2.CameraManager m = (android.hardware.camera2.CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            m.setTorchMode(id, on);
            JSObject o = new JSObject();
            o.put("on", on);
            c.resolve(o);
        } catch (Exception e) {
            c.reject("torch: " + e.getMessage());
        }
    }

    /** V87: native Android clipboard. The WebView clipboard API is unreliable inside the terminal sheet. */
    @PluginMethod
    public void copyText(final PluginCall c) {
        final String text = c.getString("text");
        if (text == null || text.isEmpty()) { c.reject("nothing to copy"); return; }
        getActivity().runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null) { c.reject("no clipboard service"); return; }
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Prodigy Terminal", text));
                    c.resolve();
                } catch (Exception e) {
                    c.reject("copy: " + e.getMessage());
                }
            }
        });
    }
}
