package com.fezzy.prodigy;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

/** S.O.I: NewPipe Extractor resolve + chunked download into MediaStore (Music/Prodigy). */
final class YtFetch {
    interface Prog { void on(double p, String msg); }

    static final class Result { long id; String name; long size; String mime; boolean dl; String title = "", artist = "", album = "", year = "", art = "", artErr = ""; }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build();
    private static boolean ready;

    private static final class Dl extends Downloader {
        @Override
        public Response execute(Request req) throws java.io.IOException, ReCaptchaException {
            byte[] data = req.dataToSend();
            RequestBody body = data != null ? RequestBody.create(data, null) : null;
            okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(req.url())
                    .method(req.httpMethod(), body).header("User-Agent", UA);
            for (Map.Entry<String, List<String>> e : req.headers().entrySet()) {
                b.removeHeader(e.getKey());
                for (String v : e.getValue()) b.addHeader(e.getKey(), v);
            }
            try (okhttp3.Response r = HTTP.newCall(b.build()).execute()) {
                if (r.code() == 429) throw new ReCaptchaException("YouTube rate limit (429)", req.url());
                ResponseBody rb = r.body();
                return new Response(r.code(), r.message(), r.headers().toMultimap(),
                        rb == null ? "" : rb.string(), r.request().url().toString());
            }
        }
    }

    private static synchronized void init() {
        if (ready) return;
        NewPipe.init(new Dl(), new Localization("en", "US"), new ContentCountry("US"));
        ready = true;
    }

    private static String clean(String s) {
        String t = s == null ? "audio" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 90) t = t.substring(0, 90).trim();
        return t.isEmpty() ? "audio" : t;
    }

    /** V20: cover art as base64 ("" on failure, reason in err[0]).
     *  Tries NewPipe's thumbnails (<=720px first, biggest first), then the standard
     *  i.ytimg.com names for the video id, and keeps going until one really downloads. */
    private static String thumb(StreamInfo info, String[] err) {
        List<String> urls = new ArrayList<>();
        try {
            List<Image> ims = new ArrayList<>(info.getThumbnails());
            java.util.Collections.sort(ims, (x, y) -> {
                int rx = rank(x.getHeight()), ry = rank(y.getHeight());
                if (rx != ry) return Integer.compare(rx, ry);
                return rx == 0 ? Integer.compare(y.getHeight(), x.getHeight()) : Integer.compare(x.getHeight(), y.getHeight());
            });
            for (Image im : ims) { String u = im.getUrl(); if (u != null && !u.isEmpty() && !urls.contains(u)) urls.add(u); }
        } catch (Throwable t) { err[0] = "thumbs: " + t; }
        String vid = info.getId();
        if (vid != null && !vid.isEmpty()) {
            for (String n : new String[]{"maxresdefault", "sddefault", "hqdefault", "mqdefault"})
                urls.add("https://i.ytimg.com/vi/" + vid + "/" + n + ".jpg");
        }
        for (String u : urls) {
            try {
                okhttp3.Request rq = new okhttp3.Request.Builder().url(u).header("User-Agent", UA).build();
                try (okhttp3.Response r = HTTP.newCall(rq).execute()) {
                    ResponseBody rb = r.body();
                    if (!r.isSuccessful() || rb == null) { err[0] = "art HTTP " + r.code(); continue; }
                    byte[] b = rb.bytes();
                    if (b.length < 2000 || b.length > 3000000) { err[0] = "art size " + b.length; continue; }
                    return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
                }
            } catch (Throwable t) { err[0] = "art: " + t; }
        }
        return "";
    }

    /** 0 = known and <=720px, 1 = bigger than 720px, 2 = unknown height */
    private static int rank(int h) { return h <= 0 ? 2 : (h <= 720 ? 0 : 1); }

    /** V28: YouTube search via NewPipe. kind: "videos" | "music_songs" | "playlists". Row: url, title, by, dur, views, thumb, type (v|p), count */
    static List<String[]> search(String q, String kind, int max) throws Exception {
        init();
        List<String[]> out = new ArrayList<>();
        SearchQueryHandler qh = ServiceList.YouTube.getSearchQHFactory().fromQuery(q, java.util.Collections.singletonList(kind), "");
        SearchInfo si = SearchInfo.getInfo(ServiceList.YouTube, qh);
        for (InfoItem it : si.getRelatedItems()) {
            if (out.size() >= max) break;
            String th = "";
            try { List<Image> ims = it.getThumbnails(); if (ims != null && !ims.isEmpty()) th = ims.get(ims.size() - 1).getUrl(); } catch (Throwable ignored) {}
            if (th == null) th = "";
            if (it instanceof StreamInfoItem) {
                StreamInfoItem s = (StreamInfoItem) it;
                String by = s.getUploaderName() == null ? "" : s.getUploaderName();
                out.add(new String[]{s.getUrl(), s.getName(), by, String.valueOf(s.getDuration()), String.valueOf(s.getViewCount()), th, "v", "0"});
            } else if (it instanceof PlaylistInfoItem) {
                PlaylistInfoItem p = (PlaylistInfoItem) it;
                String by = p.getUploaderName() == null ? "" : p.getUploaderName();
                out.add(new String[]{p.getUrl(), p.getName(), by, "0", "-1", th, "p", String.valueOf(p.getStreamCount())});
            }
        }
        return out;
    }

    /** Expand a playlist URL into video URLs + titles (first `max` items). */
    static List<String[]> playlist(String url, int max) throws Exception {
        init();
        List<String[]> out = new ArrayList<>();
        PlaylistInfo info = PlaylistInfo.getInfo(url);
        for (StreamInfoItem it : info.getRelatedItems()) { if (out.size() >= max) break; out.add(new String[]{it.getUrl(), it.getName()}); }
        Page next = info.getNextPage();
        while (next != null && out.size() < max) {
            ListExtractor.InfoItemsPage<StreamInfoItem> pg;
            try { pg = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next); } catch (Exception e) { break; }
            for (StreamInfoItem it : pg.getItems()) { if (out.size() >= max) break; out.add(new String[]{it.getUrl(), it.getName()}); }
            next = pg.getNextPage();
        }
        return out;
    }

    static Result run(Context ctx, String url, Prog prog) throws Exception { return run(ctx, url, 0, prog); }

    static Result run(Context ctx, String url, int itag, Prog prog) throws Exception {
        init();
        prog.on(0.02, "Resolving…");
        StreamInfo info = StreamInfo.getInfo(url);
        AudioStream best = null;
        for (AudioStream a : info.getAudioStreams()) {
            if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !a.isUrl()) continue;
            boolean m4a = a.getFormat() != null && "m4a".equalsIgnoreCase(a.getFormat().getSuffix());
            boolean bm4a = best != null && best.getFormat() != null && "m4a".equalsIgnoreCase(best.getFormat().getSuffix());
            if (best == null || (m4a && !bm4a) || (m4a == bm4a && a.getAverageBitrate() > best.getAverageBitrate())) best = a;
        }
        { AudioStream p128 = pickAudio(info); if (p128 != null) best = p128; }   // V90: default = AAC closest to 128 kbps
        if (itag > 0) { for (AudioStream a : info.getAudioStreams()) if (a.getItag() == itag && a.isUrl()) { best = a; break; } }
        if (best == null) throw new Exception("no direct audio stream (age/region/live?)");
        String ext = best.getFormat() != null ? best.getFormat().getSuffix().toLowerCase(Locale.ROOT) : "m4a";
        String mime = best.getFormat() != null ? best.getFormat().getMimeType() : "audio/mp4";
        String uploader = info.getUploaderName() == null ? "" : info.getUploaderName().replaceAll("(?i)\\s*-\\s*Topic$", "").replaceAll("(?i)VEVO$", "").trim();
        String rawTitle = info.getName() == null ? "audio" : info.getName();
        String artist = uploader, track = rawTitle;
        int dash = rawTitle.indexOf(" - ");
        if (dash > 0 && dash < rawTitle.length() - 3) { artist = rawTitle.substring(0, dash).trim(); track = rawTitle.substring(dash + 3).trim(); }
        track = track.replaceAll("(?i)\\s*[\\(\\[][^\\)\\]]*(official|video|audio|lyric|visuali[sz]er|\\bhd\\b|4k|music video)[^\\)\\]]*[\\)\\]]", "").trim();
        if (track.isEmpty()) track = rawTitle;
        String album = "", year = "";
        try {
            String d = info.getDescription() == null ? "" : info.getDescription().getContent();
            if (d != null && d.contains("Provided to YouTube by")) {
                String[] ln = d.split("\\r?\\n");
                int k = 0, seen = 0;
                for (; k < ln.length; k++) if (ln[k].startsWith("Provided to YouTube by")) break;
                for (k++; k < ln.length && seen < 2; k++) {
                    String t = ln[k].trim(); if (t.isEmpty()) continue;
                    seen++;
                    if (seen == 1 && t.contains(" \u00b7 ")) { String[] pr = t.split(" \u00b7 "); track = pr[0].trim(); if (pr.length > 1) artist = pr[1].trim(); }
                    if (seen == 2) album = t;
                }
            }
            Matcher m = Pattern.compile("\u2117\\s*(\\d{4})").matcher(d == null ? "" : d);
            if (m.find()) year = m.group(1);
        } catch (Throwable ignored) {}
        String title = clean(track);
        String fname = (artist.isEmpty() ? "" : clean(artist) + " - ") + title + "." + ext;

        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        Uri col;
        boolean dl = Build.VERSION.SDK_INT >= 29;
        if (dl) {
            col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Prodigy");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            cv.put(MediaStore.Audio.Media.TITLE, title);
            if (!artist.isEmpty()) cv.put(MediaStore.Audio.Media.ARTIST, artist);
        }
        Uri out = cr.insert(col, cv);
        if (out == null) throw new Exception("MediaStore insert failed");

        long total = 0, off = 0;
        final int CH = 1048576;
        boolean ok = false;
        try (OutputStream os = cr.openOutputStream(out)) {
            if (os == null) throw new Exception("cannot open output");
            for (;;) {
                okhttp3.Request rq = new okhttp3.Request.Builder().url(best.getContent())
                        .header("User-Agent", UA).header("Range", "bytes=" + off + "-" + (off + CH - 1)).build();
                try (okhttp3.Response r = HTTP.newCall(rq).execute()) {
                    if (r.code() == 416) break;
                    if (r.code() == 429) throw new Exception("YouTube rate limit (429), retry later");
                    if (r.code() != 200 && r.code() != 206) throw new Exception("HTTP " + r.code());
                    String cr2 = r.header("Content-Range");
                    if (cr2 != null && cr2.contains("/")) { try { total = Long.parseLong(cr2.substring(cr2.indexOf('/') + 1).trim()); } catch (Exception ignored) {} }
                    else if (r.code() == 200) { ResponseBody rb0 = r.body(); if (rb0 != null) total = rb0.contentLength(); }
                    ResponseBody rb = r.body();
                    if (rb == null) break;
                    long got = 0;
                    try (InputStream in = rb.byteStream()) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n); got += n; off += n;
                            if (total > 0) prog.on(0.05 + 0.93 * Math.min(1.0, (double) off / total), "Downloading…");
                        }
                    }
                    if (got == 0 || r.code() == 200) break;
                }
                if (total > 0 && off >= total) break;
            }
            os.flush();
            ok = off > 0;
        } finally {
            if (!ok) { try { cr.delete(out, null, null); } catch (Exception ignored) {} }
        }
        if (!ok) throw new Exception("empty download");
        if (dl) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(out, done, null, null);
        }
        Result res = new Result();
        res.id = android.content.ContentUris.parseId(out);
        res.name = fname; res.size = off; res.mime = mime; res.dl = dl;
        res.title = title; res.artist = artist; res.album = album; res.year = year;
        String[] ae = {""};
        try { res.art = thumb(info, ae); } catch (Throwable t) { ae[0] = String.valueOf(t); }
        res.artErr = res.art.isEmpty() ? ae[0] : "";
        prog.on(1.0, "Saved");
        return res;
    }

    /** V21: best single-file (video+audio) progressive stream, preferring mp4 and <=720p. null if none. */
    private static VideoStream bestVideo(StreamInfo info) {
        VideoStream best = null;
        for (VideoStream v : info.getVideoStreams()) {
            if (v.isVideoOnly() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !v.isUrl()) continue;
            if (best == null) { best = v; continue; }
            boolean mp4 = v.getFormat() != null && "mp4".equalsIgnoreCase(v.getFormat().getSuffix());
            boolean bmp4 = best.getFormat() != null && "mp4".equalsIgnoreCase(best.getFormat().getSuffix());
            boolean ok = v.getHeight() <= 720, bok = best.getHeight() <= 720;
            if (ok != bok) { if (ok) best = v; continue; }
            if (mp4 != bmp4) { if (mp4) best = v; continue; }
            if (v.getHeight() > best.getHeight()) best = v;
        }
        return best;
    }

    /** V56: size of a stream via a 1-byte range request (0 = unknown). */
    private static long probe(String u) {
        try {
            okhttp3.Request rq = new okhttp3.Request.Builder().url(u).header("User-Agent", UA).header("Range", "bytes=0-0").build();
            try (okhttp3.Response r = HTTP.newCall(rq).execute()) {
                String cr = r.header("Content-Range");
                if (cr != null && cr.contains("/")) return Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** V56: every direct audio / single-file video format with its size. Row: k(a|v), itag, ext, label, size */
    static List<String[]> formats(String url, String[] titleOut) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(url);
        titleOut[0] = info.getName() == null ? "" : info.getName();
        List<String[]> out = new java.util.ArrayList<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (AudioStream a : info.getAudioStreams()) {
            if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !a.isUrl() || !seen.add(a.getItag())) continue;
            String ext = a.getFormat() != null ? a.getFormat().getSuffix().toLowerCase(Locale.ROOT) : "m4a";
            int br = a.getAverageBitrate();
            out.add(new String[]{"a", String.valueOf(a.getItag()), ext, br > 0 ? br + " kbps" : "audio", String.valueOf(probe(a.getContent()))});
        }
        for (VideoStream v : info.getVideoStreams()) {
            if (v.isVideoOnly() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !v.isUrl() || !seen.add(v.getItag())) continue;
            String ext = v.getFormat() != null ? v.getFormat().getSuffix().toLowerCase(Locale.ROOT) : "mp4";
            out.add(new String[]{"v", String.valueOf(v.getItag()), ext, v.getHeight() > 0 ? v.getHeight() + "p" : "video", String.valueOf(probe(v.getContent()))});
        }
        // V75: H.264 video-only MP4 streams (720p, 1080p ...) are merged with the 128 kbps m4a audio on the phone
        AudioStream ma = pickAudio(info);
        if (ma != null) {
            long asz = probe(ma.getContent());
            java.util.TreeMap<Integer, VideoStream> pick = new java.util.TreeMap<>();
            for (VideoStream v : info.getVideoStreams()) {
                if (!v.isVideoOnly() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !v.isUrl() || v.getHeight() <= 0) continue;
                if (v.getFormat() == null || !"mp4".equalsIgnoreCase(v.getFormat().getSuffix())) continue;
                String cd = v.getCodec() == null ? "" : v.getCodec().toLowerCase(Locale.ROOT);
                if (!cd.startsWith("avc1")) continue;
                int key = v.getHeight() * 10 + (v.getFps() > 30 ? 1 : 0);
                VideoStream o = pick.get(key);
                if (o == null) pick.put(key, v);
            }
            for (VideoStream v : pick.descendingMap().values()) {
                if (!seen.add(v.getItag())) continue;
                out.add(new String[]{"m", String.valueOf(v.getItag()), "mp4", v.getHeight() + "p" + (v.getFps() > 30 ? String.valueOf(v.getFps()) : ""), String.valueOf(probe(v.getContent()) + asz)});
            }
        }
        return out;
    }

    /** V75: the AAC (m4a) stream closest to 128 kbps, falling back to the best m4a. */
    private static AudioStream pickAudio(StreamInfo info) {
        AudioStream best = null;
        for (AudioStream a : info.getAudioStreams()) {
            if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !a.isUrl()) continue;
            if (a.getFormat() == null || !"m4a".equalsIgnoreCase(a.getFormat().getSuffix())) continue;
            if (best == null || Math.abs(a.getAverageBitrate() - 128) < Math.abs(best.getAverageBitrate() - 128)) best = a;
        }
        return best;
    }

    /** V75: chunked download of one stream into a cache file, progress p0..p1. */
    private static void fetchFile(String u, File f, Prog prog, double p0, double p1, String msg) throws Exception {
        long total = 0, off = 0;
        final int CH = 1048576;
        try (OutputStream os = new java.io.FileOutputStream(f)) {
            for (;;) {
                okhttp3.Request rq = new okhttp3.Request.Builder().url(u)
                        .header("User-Agent", UA).header("Range", "bytes=" + off + "-" + (off + CH - 1)).build();
                try (okhttp3.Response r = HTTP.newCall(rq).execute()) {
                    if (r.code() == 416) break;
                    if (r.code() == 429) throw new Exception("YouTube rate limit (429), retry later");
                    if (r.code() != 200 && r.code() != 206) throw new Exception("HTTP " + r.code());
                    String cr2 = r.header("Content-Range");
                    if (cr2 != null && cr2.contains("/")) { try { total = Long.parseLong(cr2.substring(cr2.indexOf('/') + 1).trim()); } catch (Exception ignored) {} }
                    else if (r.code() == 200) { ResponseBody rb0 = r.body(); if (rb0 != null) total = rb0.contentLength(); }
                    ResponseBody rb = r.body();
                    if (rb == null) break;
                    long got = 0;
                    try (InputStream in = rb.byteStream()) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n); got += n; off += n;
                            if (total > 0) prog.on(p0 + (p1 - p0) * Math.min(1.0, (double) off / total), msg);
                        }
                    }
                    if (got == 0 || r.code() == 200) break;
                }
                if (total > 0 && off >= total) break;
            }
        }
        if (off <= 0) throw new Exception("empty download");
    }

    private static int trackOf(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** V75: lossless merge (no re-encoding) of one video file and one audio file into an MP4. */
    private static void mux(File vf, File af, File out) throws Exception {
        MediaExtractor ve = new MediaExtractor(), ae = new MediaExtractor();
        MediaMuxer mx = null;
        boolean started = false;
        try {
            ve.setDataSource(vf.getAbsolutePath());
            ae.setDataSource(af.getAbsolutePath());
            int vt = trackOf(ve, "video/"), at = trackOf(ae, "audio/");
            if (vt < 0 || at < 0) throw new Exception("could not read the downloaded streams");
            ve.selectTrack(vt);
            ae.selectTrack(at);
            mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vi = mx.addTrack(ve.getTrackFormat(vt)), ai = mx.addTrack(ae.getTrackFormat(at));
            mx.start();
            started = true;
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 << 20);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean vd = false, ad = false;
            while (!vd || !ad) {
                long vtm = vd ? Long.MAX_VALUE : ve.getSampleTime();
                long atm = ad ? Long.MAX_VALUE : ae.getSampleTime();
                if (!vd && vtm < 0) { vd = true; continue; }
                if (!ad && atm < 0) { ad = true; continue; }
                boolean useV = !vd && (ad || vtm <= atm);
                MediaExtractor ex = useV ? ve : ae;
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) { if (useV) vd = true; else ad = true; continue; }
                bi.offset = 0;
                bi.size = n;
                bi.presentationTimeUs = ex.getSampleTime();
                bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mx.writeSampleData(useV ? vi : ai, buf, bi);
                ex.advance();
            }
            mx.stop();
            started = false;
        } finally {
            try { if (mx != null) { if (started) { try { mx.stop(); } catch (Throwable ignored) {} } mx.release(); } } catch (Throwable ignored) {}
            try { ve.release(); } catch (Throwable ignored) {}
            try { ae.release(); } catch (Throwable ignored) {}
        }
    }

    /** V75: video-only MP4 (720p, 1080p ...) + 128 kbps m4a downloaded and merged into one MP4 in Download/Prodigy. */
    static Result runMerge(Context ctx, String url, int vItag, int aItag, Prog prog) throws Exception {
        init();
        prog.on(0.02, "Resolving…");
        StreamInfo info = StreamInfo.getInfo(url);
        VideoStream vs = null;
        for (VideoStream v : info.getVideoStreams())
            if (v.getItag() == vItag && v.isVideoOnly() && v.isUrl() && v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) { vs = v; break; }
        if (vs == null) throw new Exception("that video format is not available for this link");
        AudioStream as = null;
        if (aItag > 0) { for (AudioStream a : info.getAudioStreams()) if (a.getItag() == aItag && a.isUrl()) { as = a; break; } }
        if (as == null) as = pickAudio(info);
        if (as == null) throw new Exception("no audio stream to merge with");
        File dir = ctx.getCacheDir();
        File vf = new File(dir, "pz_v.tmp"), af = new File(dir, "pz_a.tmp"), of = new File(dir, "pz_m.mp4");
        try {
            long vsz = probe(vs.getContent()), asz = probe(as.getContent());
            double wv = (vsz + asz) > 0 ? (double) vsz / (vsz + asz) : 0.8;
            double mid = 0.04 + 0.78 * wv;
            fetchFile(vs.getContent(), vf, prog, 0.04, mid, "Downloading video…");
            fetchFile(as.getContent(), af, prog, mid, 0.82, "Downloading audio…");
            prog.on(0.84, "Merging video + audio…");
            mux(vf, af, of);
            String title = clean(info.getName() == null ? "video" : info.getName());
            String fname = title + " (" + vs.getHeight() + "p).mp4";
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            Uri col;
            boolean dl = Build.VERSION.SDK_INT >= 29;
            if (dl) {
                col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Prodigy");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            } else {
                col = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                cv.put(MediaStore.Video.Media.TITLE, title);
            }
            Uri out = cr.insert(col, cv);
            if (out == null) throw new Exception("MediaStore insert failed");
            long size = 0;
            boolean ok = false;
            try (InputStream in = new java.io.FileInputStream(of); OutputStream os = cr.openOutputStream(out)) {
                if (os == null) throw new Exception("cannot open output");
                byte[] buf = new byte[262144]; int n;
                while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); size += n; prog.on(0.85 + 0.14 * Math.min(1.0, (double) size / Math.max(1, of.length())), "Saving…"); }
                os.flush();
                ok = size > 0;
            } finally {
                if (!ok) { try { cr.delete(out, null, null); } catch (Exception ignored) {} }
            }
            if (!ok) throw new Exception("empty merge");
            if (dl) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(out, done, null, null);
            }
            Result res = new Result();
            res.id = android.content.ContentUris.parseId(out);
            res.name = fname; res.size = size; res.mime = "video/mp4"; res.dl = dl;
            res.title = title;
            prog.on(1.0, "Saved");
            return res;
        } finally {
            vf.delete(); af.delete(); of.delete();
        }
    }

    /** V21: can this link be saved as a video file? Returns {title, height(0 = none)}. */
    static String[] canVideo(String url) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(url);
        VideoStream v = bestVideo(info);
        return new String[]{info.getName() == null ? "" : info.getName(), String.valueOf(v == null ? 0 : v.getHeight())};
    }

    /** V21: download the video file into Download/Prodigy (MediaStore, chunked like audio). */
    static Result runVideo(Context ctx, String url, Prog prog) throws Exception { return runVideo(ctx, url, 0, prog); }

    static Result runVideo(Context ctx, String url, int itag, Prog prog) throws Exception {
        init();
        prog.on(0.02, "Resolving…");
        StreamInfo info = StreamInfo.getInfo(url);
        VideoStream best = bestVideo(info);
        if (itag > 0) { for (VideoStream v : info.getVideoStreams()) if (v.getItag() == itag && !v.isVideoOnly() && v.isUrl() && v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) { best = v; break; } }
        if (best == null) throw new Exception("no single-file video stream for this link (only separate video/audio)");
        String ext = best.getFormat() != null ? best.getFormat().getSuffix().toLowerCase(Locale.ROOT) : "mp4";
        String mime = best.getFormat() != null ? best.getFormat().getMimeType() : "video/mp4";
        String title = clean(info.getName() == null ? "video" : info.getName());
        String fname = title + (best.getHeight() > 0 ? " (" + best.getHeight() + "p)" : "") + "." + ext;
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        Uri col;
        boolean dl = Build.VERSION.SDK_INT >= 29;
        if (dl) {
            col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Prodigy");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            col = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            cv.put(MediaStore.Video.Media.TITLE, title);
        }
        Uri out = cr.insert(col, cv);
        if (out == null) throw new Exception("MediaStore insert failed");
        long total = 0, off = 0;
        final int CH = 1048576;
        boolean ok = false;
        try (OutputStream os = cr.openOutputStream(out)) {
            if (os == null) throw new Exception("cannot open output");
            for (;;) {
                okhttp3.Request rq = new okhttp3.Request.Builder().url(best.getContent())
                        .header("User-Agent", UA).header("Range", "bytes=" + off + "-" + (off + CH - 1)).build();
                try (okhttp3.Response r = HTTP.newCall(rq).execute()) {
                    if (r.code() == 416) break;
                    if (r.code() == 429) throw new Exception("YouTube rate limit (429), retry later");
                    if (r.code() != 200 && r.code() != 206) throw new Exception("HTTP " + r.code());
                    String cr2 = r.header("Content-Range");
                    if (cr2 != null && cr2.contains("/")) { try { total = Long.parseLong(cr2.substring(cr2.indexOf('/') + 1).trim()); } catch (Exception ignored) {} }
                    else if (r.code() == 200) { ResponseBody rb0 = r.body(); if (rb0 != null) total = rb0.contentLength(); }
                    ResponseBody rb = r.body();
                    if (rb == null) break;
                    long got = 0;
                    try (InputStream in = rb.byteStream()) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n); got += n; off += n;
                            if (total > 0) prog.on(0.05 + 0.93 * Math.min(1.0, (double) off / total), "Downloading video…");
                        }
                    }
                    if (got == 0 || r.code() == 200) break;
                }
                if (total > 0 && off >= total) break;
            }
            os.flush();
            ok = off > 0;
        } finally {
            if (!ok) { try { cr.delete(out, null, null); } catch (Exception ignored) {} }
        }
        if (!ok) throw new Exception("empty download");
        if (dl) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(out, done, null, null);
        }
        Result res = new Result();
        res.id = android.content.ContentUris.parseId(out);
        res.name = fname; res.size = off; res.mime = mime; res.dl = dl; res.title = title;
        prog.on(1.0, "Saved");
        return res;
    }
}
