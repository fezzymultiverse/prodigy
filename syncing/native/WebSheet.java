package com.fezzy.prodigy;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * V47: bottom-sheet Chrome-style browser (half screen, minimise, maximise).
 * Real tabs (every tab is its own page with its own back history, kept alive while the sheet is open, restored next time),
 * bookmarks, history, Chrome-style menu and Chrome-style settings in bordered panels.
 * Address bar, search engines, cookies, desktop site, downloads, file upload, fullscreen video, bot-detection hardening.
 */
public class WebSheet {
    static final int REQ_FILE = 7301;
    private static final int ARROW = 0xFF39FF14; // V59: every navigating arrow is neon green
    private static final int GREEN = 0xFF00E5FF, CYAN = 0xFF00E5FF, BLACK = 0xFF000000, PANEL = 0xFF0A1418, DIM = 0xFF8AA7AD;
    private static final int CARD = 0xFF0B1317, LINE = 0xFF1B2A30, PRESS = 0xFF16262D, EDGE = 0xFF2C3E45, WARN = 0xFFFF5252, TILE = 0xFF10181D;
    private static final int MAX_TABS = 10, HIST_MAX = 300, BM_MAX = 500;
    private static final String[] ENG_N = {"Google", "DuckDuckGo", "Bing", "Brave", "Startpage"};
    private static final String[] ENG_H = {"https://www.google.com/", "https://duckduckgo.com/", "https://www.bing.com/", "https://search.brave.com/", "https://www.startpage.com/"};
    private static final String[] ENG_S = {"https://www.google.com/search?q=", "https://duckduckgo.com/?q=", "https://www.bing.com/search?q=", "https://search.brave.com/search?q=", "https://www.startpage.com/do/search?q="};
    // runs before any page script: a real Chrome has window.chrome and webdriver=false
    private static final String STEALTH_JS = "(function(){try{if(!window.chrome){window.chrome={app:{isInstalled:false},runtime:{}}}}catch(e){}try{Object.defineProperty(navigator,'webdriver',{get:function(){return false}})}catch(e){}})();";

    private static Dialog dlg;
    private static LinearLayout rootV;
    private static FrameLayout holder;
    private static EditText addr;
    private static ProgressBar bar;
    private static TextView tabsB;
    private static ValueCallback<Uri[]> fileCb;
    private static View customView;
    private static WebChromeClient.CustomViewCallback customCb;
    private static String baseUa;
    private static int mode = 1, prevMode = 1; // 0 = minimised bar, 1 = half screen, 2 = full screen
    private static Activity actRef;
    private static TextView minB, maxB, dlB;
    private static boolean kbUp = false, kbAuto = false;
    // V83: keyboard lag fix. Resize the sheet once after the keyboard animation settles, never twice, and skip no-op layouts.
    private static final android.os.Handler KB_H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable kbRun;
    private static int lastH = -1, lastVisBottom = -1;
    private static final Rect KB_R = new Rect();

    /** One browser tab. wv is null while the tab is only a saved address (restored from last time) and is created when you open it. */
    private static final class Tab {
        WebView wv;
        String url = "", title = "";
        Bitmap icon;
        boolean spAuto, stale;
        Tab parent;
    }

    private static final ArrayList<Tab> TABS = new ArrayList<Tab>();
    private static Tab cur;
    private static final ArrayList<Dialog> PANELS = new ArrayList<Dialog>();

    private static SharedPreferences sp(Activity a) { return a.getSharedPreferences("fz_browser", Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ sheet size / keyboard

    /** Bottom sheet: minimised (top bar only, app stays usable), half screen (default) or full screen. */
    private static void setMode(Activity a, int m) {
        if (m == 1 && kbUp) { kbAuto = true; m = 2; }
        if (m == 0) kbAuto = false;
        mode = m;
        if (dlg == null || a == null) return;
        Window win = dlg.getWindow();
        if (win == null) return;
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int h = m == 2 ? ViewGroup.LayoutParams.MATCH_PARENT : (m == 0 ? (int) (58 * dm.density) : (int) (dm.heightPixels * 0.55f));
        if (h != lastH) {
            lastH = h;
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h);
            win.setGravity(Gravity.BOTTOM);
        }
        if (minB != null) minB.setText(m == 0 ? "\u25B4" : "\u25BE");
        if (maxB != null) maxB.setText("\u2922");
        if (m == 0 && addr != null) { hideKb(a, addr); addr.clearFocus(); }
    }

    /** V44: true while the on-screen keyboard is showing. */
    private static boolean imeShown(Activity a, View v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets wi = v.getRootWindowInsets();
                if (wi != null) return wi.isVisible(WindowInsets.Type.ime());
            }
        } catch (Throwable ignored) {}
        try {
            Rect r = new Rect();
            v.getWindowVisibleDisplayFrame(r);
            int sh = a.getResources().getDisplayMetrics().heightPixels;
            return sh - r.bottom > sh * 0.15f;
        } catch (Throwable e) { return false; }
    }

    /** V44: keyboard up -> sheet grows to full height so it sits above the keyboard; keyboard down -> back to half. */
    private static void kbSync(final Activity a, final View v) {
        if (kbRun != null) KB_H.removeCallbacks(kbRun);
        kbRun = new Runnable() { @Override public void run() { kbRun = null; kbApply(a, v); } };
        KB_H.postDelayed(kbRun, 90);
    }

    private static void kbApply(Activity a, View v) {
        if (dlg == null) return;
        boolean up = imeShown(a, v);
        if (up == kbUp) return;
        kbUp = up;
        if (up) { if (mode == 1) { kbAuto = true; setMode(a, 2); } }
        else if (kbAuto) { kbAuto = false; if (mode == 2) setMode(a, 1); }
    }

    private static boolean isSpotify(String u) {
        try { String h = Uri.parse(u).getHost(); return h != null && (h.equals("spotify.com") || h.endsWith(".spotify.com") || h.endsWith(".scdn.co")); }
        catch (Throwable e) { return false; }
    }

    /** V44: Spotify's web player refuses phone user agents, so use the desktop one for Spotify pages only (per tab since V47). */
    private static void smartUa(Activity a, Tab t, String u) {
        if (t == null || t.wv == null || u == null) return;
        if (sp(a).getBoolean("desktop", false)) return;
        boolean s = isSpotify(u);
        WebSettings ws = t.wv.getSettings();
        if (s && !t.spAuto) {
            t.spAuto = true;
            ws.setUserAgentString(ua(true));
            ws.setLoadWithOverviewMode(true);
        } else if (!s && t.spAuto) {
            t.spAuto = false;
            ws.setUserAgentString(ua(false));
            ws.setLoadWithOverviewMode(false);
        }
    }

    private static int eng(Activity a) { return Math.max(0, Math.min(ENG_N.length - 1, sp(a).getInt("engine", 0))); }

    static void show(final Activity a, final String url) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                String u = url == null ? "" : url.trim();
                try {
                    if (dlg != null && dlg.isShowing() && cur != null) {
                        if (u.length() > 0) openOrSwitch(a, u);
                        if (mode == 0) setMode(a, 1);
                        return;
                    }
                    build(a, u);
                } catch (Throwable e) {
                    try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u.length() > 0 ? u : ENG_H[eng(a)])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) {}
                }
            }
        });
    }

    static boolean onResult(int req, int res, Intent data) {
        if (req != REQ_FILE) return false;
        ValueCallback<Uri[]> cb = fileCb;
        fileCb = null;
        if (cb != null) {
            Uri[] r = null;
            try { r = WebChromeClient.FileChooserParams.parseResult(res, data); } catch (Throwable ignored) {}
            cb.onReceiveValue(r);
        }
        return true;
    }

    private static String target(Activity a, String in) {
        String t = in == null ? "" : in.trim();
        if (t.length() == 0) return ENG_H[eng(a)];
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:")) return t;
        if (t.matches("^[0-9.]+(:[0-9]+)?(/.*)?$")) return "http://" + t;
        boolean host = t.indexOf(' ') < 0 && (t.indexOf('.') > 0 || t.startsWith("localhost"));
        if (host) return "https://" + t;
        try { return ENG_S[eng(a)] + java.net.URLEncoder.encode(t, "UTF-8"); } catch (Exception e) { return ENG_H[eng(a)]; }
    }

    /** Same string real Chrome for Android sends (reduced UA): no "; wv", no "Version/4.0", device hidden. */
    private static String ua(boolean desktop) {
        String b = baseUa == null ? "" : baseUa;
        Matcher m = Pattern.compile("Chrome/([0-9]+)").matcher(b);
        String major = m.find() ? m.group(1) : "126";
        if (desktop) return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36";
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + ".0.0.0 Mobile Safari/537.36";
    }

    private static void apply(Activity a, WebView w, Tab t) {
        SharedPreferences p = sp(a);
        WebSettings s = w.getSettings();
        boolean desk = p.getBoolean("desktop", false) || (t != null && t.spAuto);
        s.setJavaScriptEnabled(p.getBoolean("js", true));
        s.setUserAgentString(ua(desk));
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(desk);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(p.getBoolean("cookies", true));
        cm.setAcceptThirdPartyCookies(w, p.getBoolean("tp", true));
        s.setTextZoom(p.getInt("zoom", 100));
    }

    /** Re-applies the saved switches to every open tab. reload = the open tab reloads now, other tabs reload when you switch to them. */
    private static void applyAll(Activity a, boolean reload) {
        for (Tab t : TABS) {
            if (t.wv == null) continue;
            apply(a, t.wv, t);
            if (reload) { if (t == cur) t.wv.reload(); else t.stale = true; }
        }
    }

    private static void stealth(WebView w) {
        // drops the X-Requested-With: <package> header that flags WebViews
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.getSettings(), Collections.<String>emptySet());
        } catch (Throwable ignored) {}
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(w, STEALTH_JS, Collections.singleton("*"));
        } catch (Throwable ignored) {}
    }

    private static TextView btn(Activity a, String t, int color, View.OnClickListener l) {
        TextView b = new TextView(a);
        b.setText(t);
        b.setTextColor(color);
        b.setTextSize(20f);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    private static void hideKb(Activity a, View v) {
        try { ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0); } catch (Throwable ignored) {}
    }

    /** Drops keyboard and focus from the address bar before a panel opens. */
    private static void blur(Activity a) {
        if (addr != null) { hideKb(a, addr); addr.clearFocus(); }
        if (rootV != null) rootV.requestFocus();
    }

    private static void hideCustom(Dialog dg) {
        try {
            if (customView != null) {
                ViewGroup p = (ViewGroup) customView.getParent();
                if (p != null) p.removeView(customView);
            }
            if (customCb != null) customCb.onCustomViewHidden();
        } catch (Throwable ignored) {}
        customView = null; customCb = null;
    }

    /** V74: social and music sites always keep their cookies, so you stay signed in until you log out. */
    private static boolean isSocial(String u) {
        if (u == null) return false;
        String s = u.toLowerCase();
        String[] hs = {"instagram.com", "facebook.com", "fb.com", "messenger.com", "tiktok.com", "threads.net", "threads.com", "badoo.com", "soundcloud.com", "audiomack.com", "reverbnation.com", "x.com", "twitter.com", "youtube.com", "google.com", "whatsapp.com", "linkedin.com", "snapchat.com", "pinterest.com", "reddit.com", "discord.com", "twitch.tv", "spotify.com", "claude.ai", "chatgpt.com", "openai.com", "deepseek.com", "github.com"};
        for (String x : hs) if (s.contains("." + x) || s.contains("//" + x)) return true;
        return false;
    }

    private static void keepCookies(WebView v) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(v, true);
        } catch (Throwable ignored) {}
    }

    // V77: a private copy of each social host's cookies. It is put back ONLY if the WebView lost them, so a login survives restarts, clean-ups and crashes.
    private static long lastCkSave = 0;

    private static String rootOf(String h) {
        if (h == null) return "";
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    private static void saveCookies(Activity a, String u) {
        try {
            if (a == null || u == null || !isSocial(u)) return;
            String h = hostOf(u);
            if (h == null || h.length() == 0) return;
            String ck = CookieManager.getInstance().getCookie(u);
            SharedPreferences.Editor e = a.getSharedPreferences("fz_cookies", Context.MODE_PRIVATE).edit();
            if (ck == null || ck.length() == 0) e.remove(h); else e.putString(h, ck);
            e.apply();
        } catch (Throwable ignored) {}
    }

    private static void restoreCookies(Activity a, String u) {
        try {
            if (a == null || u == null || !isSocial(u)) return;
            String h = hostOf(u);
            if (h == null || h.length() == 0) return;
            CookieManager cm = CookieManager.getInstance();
            String have = cm.getCookie(u);
            if (have != null && have.length() > 0) return;
            String saved = a.getSharedPreferences("fz_cookies", Context.MODE_PRIVATE).getString(h, null);
            if (saved == null || saved.length() == 0) return;
            String root = rootOf(h);
            for (String part : saved.split(";")) {
                String kv = part.trim();
                if (kv.indexOf('=') <= 0) continue;
                boolean host = kv.startsWith("__Host-");
                cm.setCookie(u, kv + "; Max-Age=31536000; Path=/; " + (host ? "" : "Domain=." + root + "; ") + "Secure");
            }
            cm.flush();
        } catch (Throwable ignored) {}
    }

    private static void toast(Activity a, String t) { try { Toast.makeText(a, t, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {} }

    // ------------------------------------------------------------------ url helpers

    private static String norm(String u) {
        if (u == null) return "";
        String s = u.trim().toLowerCase(Locale.US);
        if (s.startsWith("https://")) s = s.substring(8); else if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("www.")) s = s.substring(4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String pretty(String u) {
        if (u == null) return "";
        String s = u.trim();
        if (s.startsWith("https://")) s = s.substring(8); else if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("www.")) s = s.substring(4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String hostOf(String u) {
        try {
            String h = Uri.parse(u).getHost();
            if (h == null) return "";
            h = h.toLowerCase(Locale.US);
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Throwable e) { return ""; }
    }

    private static boolean isRoot(String u) {
        try { String p = Uri.parse(u).getPath(); return p == null || p.length() <= 1; } catch (Throwable e) { return false; }
    }

    private static boolean isWeb(String u) { return u != null && (u.startsWith("http://") || u.startsWith("https://")); }

    private static boolean isBlankTab(Tab t) {
        if (t.url == null || t.url.length() == 0 || t.url.startsWith("about:")) return true;
        String k = norm(t.url);
        for (String h : ENG_H) if (norm(h).equals(k)) return true;
        return false;
    }

    private static WebView curWv() { return cur == null ? null : cur.wv; }

    private static String curUrl() {
        WebView w = curWv();
        String u = w == null ? null : w.getUrl();
        if ((u == null || u.length() == 0) && cur != null) u = cur.url;
        return u == null ? "" : u;
    }

    private static String curTitle() {
        WebView w = curWv();
        String t = w == null ? null : w.getTitle();
        if ((t == null || t.length() == 0) && cur != null) t = cur.title;
        return t == null ? "" : t;
    }

    // ------------------------------------------------------------------ V53: YouTube download button

    /** Canonical YouTube link for the page (watch / shorts / live / youtu.be / playlist), or null when the page is not a YouTube item. */
    private static String ytCanon(String u) {
        if (u == null) return null;
        try {
            Uri p = Uri.parse(u);
            String h = p.getHost() == null ? "" : p.getHost().toLowerCase();
            if (h.startsWith("www.")) h = h.substring(4);
            if (h.startsWith("m.")) h = h.substring(2);
            String path = p.getPath() == null ? "" : p.getPath();
            String id = null;
            if (h.equals("youtu.be")) {
                id = path.length() > 1 ? path.substring(1).split("/")[0] : null;
            } else if (h.equals("youtube.com") || h.equals("music.youtube.com")) {
                if (path.equals("/watch")) id = p.getQueryParameter("v");
                else {
                    Matcher m = Pattern.compile("^/(shorts|live|embed)/([\\w-]{11})").matcher(path);
                    if (m.find()) id = m.group(2);
                }
                if (id == null && path.equals("/playlist")) {
                    String l = p.getQueryParameter("list");
                    if (l != null && l.length() > 0) return "https://www.youtube.com/playlist?list=" + l;
                }
            } else return null;
            if (id != null && id.matches("[\\w-]{11}")) return "https://www.youtube.com/watch?v=" + id;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Shows the download button only while the open tab is on a YouTube video or playlist. */
    private static void updateDl() {
        if (dlB == null) return;
        String u = cur == null ? null : (cur.wv != null && cur.wv.getUrl() != null ? cur.wv.getUrl() : cur.url);
        dlB.setVisibility(ytCanon(u) != null ? View.VISIBLE : View.GONE);
    }

    private static void ytDownload(final Activity a) {
        String u = cur == null ? null : (cur.wv != null && cur.wv.getUrl() != null ? cur.wv.getUrl() : cur.url);
        final String link = ytCanon(u);
        if (link == null) { toast(a, "Open a YouTube video first"); return; }
        if (link.contains("/playlist?")) {
            options(a, "Download playlist", new String[]{"\uD83C\uDFB5 Full songs \u00B7 whole playlist, add to library"}, new Runnable[]{new Runnable() { @Override public void run() {
                ProdigyBridgePlugin.emitYt(link, "audio");
                toast(a, "Downloading audio. Progress shows in the app");
                setMode(a, 0);
            } }}, new int[]{ARROW});
            return;
        }
        toast(a, "Reading qualities and formats\u2026");
        new Thread(new Runnable() { @Override public void run() {
            final String[] t = {""};
            java.util.List<String[]> l = null;
            try { l = YtFetch.formats(link, t); } catch (Throwable ignored) {}
            final java.util.List<String[]> fl = l;
            a.runOnUiThread(new Runnable() { @Override public void run() { ytPicker(a, link, t[0], fl); } });
        } }).start();
    }

    private static String mb(long n) {
        if (n <= 0) return "size ?";
        if (n >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        return (n / 1024) + " KB";
    }

    private static int lead(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (Throwable e) { return 0; }
    }

    /** V59: native picker inside the browser. Audio or video, quality, format and size, right where you tapped. */
    private static void ytPicker(final Activity a, final String link, String title, java.util.List<String[]> fl) {
        if (fl == null || fl.isEmpty()) {
            ProdigyBridgePlugin.emitYt(link, "pick");
            toast(a, "Pick a format in the app");
            setMode(a, 0);
            return;
        }
        java.util.List<String[]> au = new ArrayList<String[]>(), vi = new ArrayList<String[]>(), mg = new ArrayList<String[]>();
        for (String[] e : fl) { if ("a".equals(e[0])) au.add(e); else if ("m".equals(e[0])) mg.add(e); else vi.add(e); }
        java.util.Comparator<String[]> byQ = new java.util.Comparator<String[]>() {
            @Override public int compare(String[] x, String[] y) { return lead(y[3]) - lead(x[3]); }
        };
        java.util.Collections.sort(au, byQ);
        java.util.Collections.sort(vi, byQ);
        java.util.Collections.sort(mg, byQ);
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<Runnable> acts = new ArrayList<Runnable>();
        final ArrayList<Integer> cols = new ArrayList<Integer>();
        // V90: quick picks first. Video 720p (picture + sound merged) and Audio 128 kbps.
        String[] v720 = null;
        for (String[] e : mg) { if (lead(e[3]) == 720 && (v720 == null || "720p".equals(e[3]))) v720 = e; }
        if (v720 == null) for (String[] e : vi) { if (lead(e[3]) == 720) { v720 = e; break; } }
        String[] a128 = null;
        for (String[] e : au) {
            if (!"m4a".equalsIgnoreCase(e[2])) continue;
            if (a128 == null || Math.abs(lead(e[3]) - 128) < Math.abs(lead(a128[3]) - 128)) a128 = e;
        }
        if (v720 != null) {
            final String[] q = v720;
            labels.add("\uD83C\uDFAC VIDEO 720p \u00B7 MP4 \u00B7 " + mb(Long.parseLong(q[4])));
            acts.add(new Runnable() { @Override public void run() { ytGo(a, link, "m".equals(q[0]) ? "merge" : "video", Integer.parseInt(q[1]), "video 720p"); } });
            cols.add(Color.WHITE);
        }
        if (a128 != null) {
            final String[] q = a128;
            labels.add("\uD83C\uDFB5 AUDIO 128 kbps \u00B7 M4A \u00B7 " + mb(Long.parseLong(q[4])));
            acts.add(new Runnable() { @Override public void run() { ytGo(a, link, "audio", Integer.parseInt(q[1]), "audio 128 kbps"); } });
            cols.add(ARROW);
        }
        labels.add("\uD83C\uDFB5 AUDIO \u00B7 Best, auto");
        acts.add(new Runnable() { @Override public void run() { ytGo(a, link, "audio", 0, "audio"); } });
        cols.add(ARROW);
        for (final String[] e : au) {
            labels.add("\uD83C\uDFB5 AUDIO \u00B7 " + e[3] + " \u00B7 " + e[2].toUpperCase(java.util.Locale.ROOT) + " \u00B7 " + mb(Long.parseLong(e[4])));
            acts.add(new Runnable() { @Override public void run() { ytGo(a, link, "audio", Integer.parseInt(e[1]), "audio " + e[3]); } });
            cols.add(ARROW);
        }
        for (final String[] e : mg) {
            labels.add("\uD83C\uDFAC VIDEO \u00B7 " + e[3] + " \u00B7 MP4 \u00B7 " + mb(Long.parseLong(e[4])));
            acts.add(new Runnable() { @Override public void run() { ytGo(a, link, "merge", Integer.parseInt(e[1]), "video " + e[3]); } });
            cols.add(Color.WHITE);
        }
        for (final String[] e : vi) {
            labels.add("\uD83C\uDFAC VIDEO \u00B7 " + e[3] + " \u00B7 " + e[2].toUpperCase(java.util.Locale.ROOT) + " \u00B7 " + mb(Long.parseLong(e[4])));
            acts.add(new Runnable() { @Override public void run() { ytGo(a, link, "video", Integer.parseInt(e[1]), "video " + e[3]); } });
            cols.add(Color.WHITE);
        }
        if (mg.isEmpty() && vi.isEmpty()) toast(a, "No video formats for this link, audio only");
        else if (v720 == null) toast(a, "No 720p for this video. Pick another size");
        int n = labels.size();
        String[] la = labels.toArray(new String[n]);
        Runnable[] ac = acts.toArray(new Runnable[n]);
        int[] co = new int[n];
        for (int z = 0; z < n; z++) co[z] = cols.get(z);
        options(a, "\uD83D\uDCE5 " + (title == null || title.length() == 0 ? "Download" : title), la, ac, co);
    }

    private static void ytGo(Activity a, String link, String kind, int itag, String what) {
        ProdigyBridgePlugin.emitYt(link, kind, itag);
        toast(a, "Downloading " + what + ". Progress shows in the app");
        setMode(a, 0);
    }

    // ------------------------------------------------------------------ tabs

    private static void syncTab(Tab t) {
        if (t == null || t.wv == null) return;
        try {
            String u = t.wv.getUrl();
            if (u != null && u.length() > 0 && !u.startsWith("about:")) t.url = u;
            String ti = t.wv.getTitle();
            if (ti != null && ti.length() > 0) t.title = ti;
        } catch (Throwable ignored) {}
    }

    private static void updateBadge() {
        if (tabsB != null) tabsB.setText(String.valueOf(TABS.size()));
    }

    private static void killWv(Tab t) {
        WebView w = t.wv;
        t.wv = null;
        t.icon = null;
        if (w == null) return;
        try {
            w.stopLoading();
            ViewGroup p = (ViewGroup) w.getParent();
            if (p != null) p.removeView(w);
            w.destroy();
        } catch (Throwable ignored) {}
    }

    private static void persist(Activity a) {
        try {
            JSONArray arr = new JSONArray();
            int ci = 0, i = 0;
            for (Tab t : TABS) {
                syncTab(t);
                JSONObject o = new JSONObject();
                o.put("u", t.url == null ? "" : t.url);
                o.put("t", t.title == null ? "" : t.title);
                arr.put(o);
                if (t == cur) ci = i;
                i++;
            }
            sp(a).edit().putString("tabs", arr.toString()).putInt("tabcur", ci).apply();
        } catch (Throwable ignored) {}
    }

    /** Same page, or same site start page, already open? Data only, nothing is loaded. */
    private static Tab findTab(String u) {
        String k = norm(u);
        for (Tab t : TABS) if (norm(t.url).equals(k)) return t;
        if (isRoot(u)) {
            String h = hostOf(u);
            if (h.length() > 0) for (Tab t : TABS) if (!isBlankTab(t) && hostOf(t.url).equals(h)) return t;
        }
        return null;
    }

    /** Fills TABS from the last session (or one fresh tab) and shows the one you were on. Tabs that already exist in memory are kept. A link picks its tab before any page loads. */
    private static void restoreTabs(Activity a, String link) {
        if (TABS.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(sp(a).getString("tabs", "[]"));
                for (int i = 0; i < arr.length() && TABS.size() < MAX_TABS; i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Tab t = new Tab();
                    t.url = o.optString("u", "");
                    t.title = o.optString("t", "");
                    TABS.add(t);
                }
                int ci = sp(a).getInt("tabcur", 0);
                if (!TABS.isEmpty()) cur = TABS.get(Math.max(0, Math.min(ci, TABS.size() - 1)));
            } catch (Throwable ignored) {}
        }
        boolean hasLink = link != null && link.length() > 0;
        if (TABS.isEmpty()) {
            Tab t = new Tab();
            t.url = hasLink ? link : ENG_H[eng(a)];
            TABS.add(t);
            cur = t;
        }
        if (cur == null || !TABS.contains(cur)) cur = TABS.get(0);
        if (hasLink) {
            Tab hit = findTab(link);
            if (hit != null) cur = hit;
            else if (isBlankTab(cur)) { cur.url = link; cur.title = ""; }
            else if (TABS.size() < MAX_TABS) {
                Tab t = new Tab();
                t.url = link;
                TABS.add(t);
                cur = t;
            } else { cur.url = link; cur.title = ""; }
        }
        switchTo(a, cur);
    }

    private static void ensureWebView(Activity a, Tab t) {
        if (t.wv != null) return;
        t.wv = makeWebView(a, t);
        String u = (t.url == null || t.url.length() == 0) ? ENG_H[eng(a)] : t.url;
        t.url = u;
        restoreCookies(a, u);
        smartUa(a, t, u);
        t.wv.loadUrl(u);
    }

    /** Opens a new tab. load=false is for window.open popups (the page loads into the WebView by itself). */
    private static Tab openTab(Activity a, String url, Tab parent, boolean load, boolean show) {
        if (holder == null) return null;
        Tab t = new Tab();
        t.url = url == null ? "" : url;
        t.parent = parent;
        t.wv = makeWebView(a, t);
        int at = (parent != null && TABS.contains(parent)) ? TABS.indexOf(parent) + 1 : TABS.size();
        TABS.add(at, t);
        if (load) {
            String u = t.url.length() > 0 ? t.url : ENG_H[eng(a)];
            t.url = u;
            restoreCookies(a, u);
            smartUa(a, t, u);
            t.wv.loadUrl(u);
        }
        if (show) switchTo(a, t); else { updateBadge(); persist(a); }
        return t;
    }

    private static Tab newTab(Activity a, String url) {
        if (TABS.size() >= MAX_TABS) { toast(a, "Tab limit reached (" + MAX_TABS + ")"); return null; }
        return openTab(a, url, null, true, true);
    }

    private static void switchTo(Activity a, Tab t) {
        if (t == null || holder == null) return;
        cur = t;
        ensureWebView(a, t);
        holder.removeAllViews();
        try {
            ViewGroup pp = (ViewGroup) t.wv.getParent();
            if (pp != null) pp.removeView(t.wv);
        } catch (Throwable ignored) {}
        holder.addView(t.wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (t.stale) { t.stale = false; t.wv.reload(); }
        String u = t.wv.getUrl();
        if (u == null || u.length() == 0) u = t.url;
        if (addr != null) addr.setText(u == null ? "" : u);
        if (bar != null) {
            int p = t.wv.getProgress();
            bar.setProgress(p);
            bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
        }
        updateBadge();
        updateDl();
        persist(a);
    }

    private static void closeTab(Activity a, Tab t) {
        if (t == null || holder == null) return;
        int i = TABS.indexOf(t);
        if (i < 0) return;
        boolean wasCur = t == cur;
        killWv(t);
        TABS.remove(i);
        if (TABS.isEmpty()) { cur = null; openTab(a, "", null, true, true); return; }
        if (wasCur) {
            Tab next = (t.parent != null && TABS.contains(t.parent)) ? t.parent : TABS.get(Math.min(i, TABS.size() - 1));
            switchTo(a, next);
        } else { updateBadge(); persist(a); }
    }

    private static void closeAllTabs(Activity a) {
        for (Tab t : new ArrayList<Tab>(TABS)) killWv(t);
        TABS.clear();
        cur = null;
        if (holder != null) holder.removeAllViews();
        openTab(a, "", null, true, true);
    }

    /** Loads an address into the open tab. */
    private static void go(Activity a, Tab t, String u) {
        if (t == null) return;
        if (t.wv == null) t.wv = makeWebView(a, t);
        t.url = u;
        t.title = "";
        restoreCookies(a, u);
        smartUa(a, t, u);
        t.wv.loadUrl(u);
        if (t == cur && addr != null) addr.setText(u);
    }

    /** Link from the app (YouTube, Facebook, Spotify, a song link...). Same page already open -> switch to it. Site start page already open -> switch to it. Empty tab -> use it. Otherwise a new tab. */
    private static void openOrSwitch(Activity a, String u) {
        for (Tab t : TABS) syncTab(t);
        Tab hit = findTab(u);
        if (hit != null) { switchTo(a, hit); return; }
        if (cur != null && isBlankTab(cur)) { go(a, cur, u); return; }
        if (TABS.size() >= MAX_TABS) { if (cur != null) go(a, cur, u); return; }
        openTab(a, u, null, true, true);
    }

    private static void killTmp(final WebView x) {
        x.post(new Runnable() { @Override public void run() { try { x.stopLoading(); x.destroy(); } catch (Throwable ignored) {} } });
    }

    private static WebView makeWebView(final Activity a, final Tab t) {
        final WebView w = new WebView(a);
        if (baseUa == null) baseUa = w.getSettings().getUserAgentString();
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        stealth(w);
        apply(a, w, t);
        w.setBackgroundColor(Color.WHITE);

        w.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest r) {
                try { if (!r.isForMainFrame() && BrowserExt.blocked(sp(a), r.getUrl().toString())) return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0])); } catch (Throwable ignored) {}
                return super.shouldInterceptRequest(v, r);
            }
            @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                if (isSocial(u)) keepCookies(v);
                if (u != null) {
                    t.url = u;
                    if (t == cur && addr != null && !addr.hasFocus()) addr.setText(u);
                }
                if (t == cur) updateDl();
            }
            @Override public void doUpdateVisitedHistory(WebView v, String u, boolean isReload) {
                // YouTube is a single-page app: this fires on every in-page navigation
                if (u != null && !u.startsWith("about:")) {
                    t.url = u;
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastCkSave > 8000) { lastCkSave = nowMs; saveCookies(a, u); }
                    if (t == cur) {
                        if (addr != null && !addr.hasFocus()) addr.setText(u);
                        updateDl();
                    }
                }
            }
            @Override public void onPageFinished(WebView v, String u) {
                try { v.evaluateJavascript(BrowserExt.js(sp(a)), null); } catch (Throwable ignored) {}
                try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                saveCookies(a, u);
                if (u != null) {
                    t.url = u;
                    if (t == cur && addr != null && !addr.hasFocus()) addr.setText(u);
                }
                String ti = v.getTitle();
                if (ti != null && ti.length() > 0) t.title = ti;
                histAdd(a, t.title, u);
                persist(a);
                if (t == cur) updateDl();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (isSocial(u)) keepCookies(v);
                if (r.isForMainFrame()) smartUa(a, t, u);
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) return false;
                if (u.startsWith("mailto:") || u.startsWith("tel:") || u.startsWith("sms:")) {
                    try {
                        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                        it.addCategory(Intent.CATEGORY_BROWSABLE);
                        it.setComponent(null);
                        it.setSelector(null);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(it);
                    } catch (Throwable ignored) {}
                    return true;
                }
                // V74: nothing opens another app. App links (fb://, instagram://, intent://, market:) stay inside Prodigy.
                String fb = null;
                if (u.startsWith("intent:")) {
                    try { fb = Intent.parseUri(u, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url"); } catch (Throwable ignored) {}
                }
                if (fb != null && (fb.startsWith("http://") || fb.startsWith("https://"))) v.loadUrl(fb);
                else toast(a, "Staying inside Prodigy");
                return true;
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean gesture, android.os.Message msg) {
                if (holder != null && TABS.size() < MAX_TABS) {
                    // window.open / target=_blank: a real new tab (keeps window.opener so login popups work; window.close() closes it)
                    Tab nt = openTab(a, "", t, false, true);
                    if (nt != null && nt.wv != null) {
                        ((WebView.WebViewTransport) msg.obj).setWebView(nt.wv);
                        msg.sendToTarget();
                        return true;
                    }
                    return false;
                }
                // tab limit reached: load the popup target into this tab instead
                final WebView parent = v;
                WebView tmp = new WebView(v.getContext());
                tmp.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView x, WebResourceRequest r) { parent.loadUrl(r.getUrl().toString()); killTmp(x); return true; }
                    @Override public void onPageStarted(WebView x, String u, Bitmap b) { if (u != null && !"about:blank".equals(u)) { x.stopLoading(); parent.loadUrl(u); killTmp(x); } }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                if (holder != null) holder.post(new Runnable() { @Override public void run() { closeTab(a, t); } });
            }
            @Override public void onReceivedTitle(WebView v, String title) { if (title != null) t.title = title; }
            @Override public void onReceivedIcon(WebView v, Bitmap icon) { t.icon = icon; }
            @Override public void onPermissionRequest(final android.webkit.PermissionRequest req) {
                // V44: allow only protected-media (Widevine DRM) so Spotify / Netflix style players can start; camera and mic stay denied
                final String[] rs = req.getResources();
                java.util.ArrayList<String> ok = new java.util.ArrayList<String>();
                for (String x : rs) if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(x)) ok.add(x);
                final String[] grant = ok.toArray(new String[0]);
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    try { if (grant.length > 0) req.grant(grant); else req.deny(); } catch (Throwable ignored) {}
                } });
            }
            @Override public void onProgressChanged(WebView v, int p) {
                if (t == cur && bar != null) { bar.setProgress(p); bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE); }
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) { try { fileCb.onReceiveValue(null); } catch (Throwable ignored) {} }
                fileCb = cb;
                try { a.startActivityForResult(params.createIntent(), REQ_FILE); return true; }
                catch (Throwable e) { fileCb = null; return false; }
            }
            @Override public void onShowCustomView(View v, WebChromeClient.CustomViewCallback cb) {
                if (customView != null || dlg == null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb; prevMode = mode; setMode(a, 2);
                try { ((ViewGroup) dlg.getWindow().getDecorView()).addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); } catch (Throwable ignored) {}
            }
            @Override public void onHideCustomView() { boolean was = customView != null; hideCustom(dlg); if (was) setMode(a, prevMode); }
        });

        w.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String u, String agent, String cd, String mime, long len) {
                try {
                    if (u.startsWith("blob:") || u.startsWith("data:")) { toast(a, "This download type is not supported here"); return; }
                    DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(u));
                    rq.setMimeType(mime);
                    String ck = CookieManager.getInstance().getCookie(u);
                    if (ck != null) rq.addRequestHeader("Cookie", ck);
                    rq.addRequestHeader("User-Agent", agent);
                    String fn = URLUtil.guessFileName(u, cd, mime);
                    rq.setTitle(fn);
                    rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                    ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
                    toast(a, "Downloading " + fn);
                } catch (Throwable e) { toast(a, "Download failed"); }
            }
        });
        return w;
    }

    // ------------------------------------------------------------------ bookmarks + history (saved in app storage)

    // V90: bookmarks live in TWO places (SharedPreferences + a file in app storage), written synchronously,
    // so they survive the app being killed in the background. If one copy is missing the other restores it.
    private static java.io.File bmFile(Activity a) { return new java.io.File(a.getFilesDir(), "bookmarks.json"); }

    private static ArrayList<String[]> bmParse(String json) {
        ArrayList<String[]> l = new ArrayList<String[]>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String u = o.optString("u", "");
                if (u.length() > 0) l.add(new String[]{o.optString("t", ""), u});
            }
        } catch (Throwable ignored) {}
        return l;
    }

    private static String bmRead(java.io.File f) {
        try {
            if (f == null || !f.exists()) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable e) { return null; }
    }

    private static ArrayList<String[]> bmLoad(Activity a) {
        ArrayList<String[]> l = bmParse(sp(a).getString("bm", "[]"));
        if (l.isEmpty()) {
            // prefs empty: restore from the file copy (or its backup) if one exists
            String f = bmRead(bmFile(a));
            ArrayList<String[]> r = new ArrayList<String[]>();
            if (f != null) r = bmParse(f);   // a valid "[]" file means you removed them all: stay empty
            else {
                String b = bmRead(new java.io.File(a.getFilesDir(), "bookmarks.bak"));
                if (b != null) r = bmParse(b);
            }
            if (!r.isEmpty()) { l = r; bmSave(a, l); }
        }
        return l;
    }

    private static void bmSave(Activity a, ArrayList<String[]> l) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < l.size() && i < BM_MAX; i++) {
                JSONObject o = new JSONObject();
                o.put("t", l.get(i)[0]);
                o.put("u", l.get(i)[1]);
                arr.put(o);
            }
            String js = arr.toString();
            sp(a).edit().putString("bm", js).commit();
            try {
                java.io.File f = bmFile(a), tmp = new java.io.File(a.getFilesDir(), "bookmarks.tmp"), bak = new java.io.File(a.getFilesDir(), "bookmarks.bak");
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                out.write(js.getBytes("UTF-8")); out.getFD().sync(); out.close();
                if (f.exists() && arr.length() > 0) { bak.delete(); f.renameTo(bak); }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static boolean bmHas(Activity a, String u) {
        String k = norm(u);
        for (String[] b : bmLoad(a)) if (norm(b[1]).equals(k)) return true;
        return false;
    }

    private static void toggleBookmark(Activity a) {
        String u = curUrl();
        if (!isWeb(u)) { toast(a, "Nothing to bookmark"); return; }
        ArrayList<String[]> l = bmLoad(a);
        String k = norm(u);
        boolean found = false;
        for (Iterator<String[]> it = l.iterator(); it.hasNext(); ) {
            if (norm(it.next()[1]).equals(k)) { it.remove(); found = true; }
        }
        if (!found) {
            String ti = curTitle();
            l.add(0, new String[]{ti.length() > 0 ? ti : pretty(u), u});
        }
        bmSave(a, l);
        toast(a, found ? "Bookmark removed" : "Bookmark added");
    }

    private static ArrayList<String[]> histLoad(Activity a) {
        ArrayList<String[]> l = new ArrayList<String[]>();
        try {
            JSONArray arr = new JSONArray(sp(a).getString("hist", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                l.add(new String[]{o.optString("t", ""), o.optString("u", ""), String.valueOf(o.optLong("ts", 0L))});
            }
        } catch (Throwable ignored) {}
        return l;
    }

    private static void histSave(Activity a, ArrayList<String[]> l) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < l.size() && i < HIST_MAX; i++) {
                JSONObject o = new JSONObject();
                o.put("t", l.get(i)[0]);
                o.put("u", l.get(i)[1]);
                long ts = 0L;
                try { ts = Long.parseLong(l.get(i)[2]); } catch (Throwable ignored) {}
                o.put("ts", ts);
                arr.put(o);
            }
            sp(a).edit().putString("hist", arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static void histAdd(Activity a, String title, String url) {
        if (sp(a).getBoolean("priv", false)) return;
        if (!isWeb(url)) return;
        ArrayList<String[]> l = histLoad(a);
        String ti = (title == null || title.length() == 0) ? pretty(url) : title;
        String now = String.valueOf(System.currentTimeMillis());
        if (!l.isEmpty() && url.equals(l.get(0)[1])) { l.get(0)[0] = ti; l.get(0)[2] = now; }
        else l.add(0, new String[]{ti, url, now});
        histSave(a, l);
    }

    private static void histClear(Activity a) { sp(a).edit().remove("hist").apply(); }

    // ------------------------------------------------------------------ Chrome-style UI building blocks

    private static int dp(Activity a, float v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }

    /** V84: download button art: black circle, green ring, green arrow pointing down. */
    private static Drawable dlIcon(final float d) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float cx = b.exactCenterX(), cy = b.exactCenterY();
                float r = Math.min(b.width(), b.height()) / 2f - 1.5f * d;
                p.setStyle(Paint.Style.FILL); p.setColor(0xFF070B07); c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * d); p.setColor(0xFF39FF14); c.drawCircle(cx, cy, r, p);
                p.setStrokeWidth(2.6f * d); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
                float s = r * 0.46f;
                c.drawLine(cx, cy - s, cx, cy + s * 0.8f, p);
                Path h = new Path();
                h.moveTo(cx - s * 0.8f, cy + s * 0.05f); h.lineTo(cx, cy + s * 0.95f); h.lineTo(cx + s * 0.8f, cy + s * 0.05f);
                c.drawPath(h, p);
            }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private static GradientDrawable box(Activity a, int fill, float radiusDp, float strokeDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(a, strokeDp)), stroke);
        return g;
    }

    private static StateListDrawable press() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(PRESS));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private static TextView tv(Activity a, String t, float size, int color, boolean bold) {
        TextView x = new TextView(a);
        x.setText(t);
        x.setTextColor(color);
        x.setTextSize(size);
        if (bold) x.setTypeface(Typeface.DEFAULT_BOLD);
        return x;
    }

    private static TextView icon(Activity a, String g) {
        TextView x = tv(a, g, 19f, CYAN, false);
        x.setGravity(Gravity.CENTER);
        return x;
    }

    private static TextView avatar(Activity a, String host, float size) {
        String l = (host == null || host.length() == 0) ? "\u2022" : host.substring(0, 1).toUpperCase(Locale.getDefault());
        TextView x = tv(a, l, size, CYAN, true);
        x.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF0F2A30);
        g.setStroke(Math.max(1, dp(a, 1.5f)), CYAN);
        x.setBackground(g);
        return x;
    }

    private static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1)));
        lp.setMargins(0, dp(a, 6), 0, dp(a, 2));
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView section(Activity a, String t) {
        TextView x = tv(a, t, 13f, CYAN, true);
        x.setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 6));
        return x;
    }

    /** One settings / list row: optional lead view, title, grey subtitle, optional trailing view. */
    private static LinearLayout row(Activity a, View lead, String title, String sub, View trailing, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(a, 18), dp(a, 10), dp(a, 10), dp(a, 10));
        r.setMinimumHeight(dp(a, 56));
        r.setBackground(press());
        if (lead != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 36));
            lp.setMargins(0, 0, dp(a, 14), 0);
            r.addView(lead, lp);
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(a, title, 16f, Color.WHITE, false);
        t.setTag("title");
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = tv(a, sub, 13f, DIM, false);
            s.setTag("sub");
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, dp(a, 2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) r.addView(trailing, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (l != null) r.setOnClickListener(l);
        return r;
    }

    private static void titleColor(View row, int c) {
        View t = row.findViewWithTag("title");
        if (t instanceof TextView) ((TextView) t).setTextColor(c);
    }

    /** Chrome-style on/off switch. */
    private static final class Tgl extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final float d;

        Tgl(Context c, boolean on) {
            super(c);
            this.on = on;
            this.d = c.getResources().getDisplayMetrics().density;
        }

        void set(boolean v) { on = v; invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension((int) (46 * d), (int) (28 * d)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? 0x6600E5FF : 0xFF33444A);
            float tr = 7 * d;
            rf.set(3 * d, h / 2f - tr, w - 3 * d, h / 2f + tr);
            c.drawRoundRect(rf, tr, tr, p);
            p.setColor(on ? CYAN : 0xFF9AAAB0);
            float r = 10 * d;
            float cx = on ? w - r - 1 * d : r + 1 * d;
            c.drawCircle(cx, h / 2f, r, p);
        }
    }

    /** ScrollView that never grows past maxPx (for small cards). */
    private static final class MaxScroll extends ScrollView {
        private final int maxPx;
        MaxScroll(Context c, int maxPx) { super(c); this.maxPx = maxPx; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST));
        }
    }

    private static final class Panel {
        Dialog dg;
        LinearLayout card, list, actions;
        TextView title;
    }

    /** Bordered Chrome-style panel. kind 0 = tall page (Settings, Bookmarks...), 1 = centred card, 2 = menu card bottom-right. title null = no header. */
    private static Panel panel(final Activity a, String title, int kind) {
        final Panel p = new Panel();
        final Dialog dg = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        p.dg = dg;
        for (Iterator<Dialog> it = PANELS.iterator(); it.hasNext(); ) if (!it.next().isShowing()) it.remove();
        PANELS.add(dg);
        dg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();

        FrameLayout root = new FrameLayout(a);
        root.setFitsSystemWindows(true);
        root.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dg.dismiss(); } });

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(a, CARD, 18f, 2f, CYAN));
        card.setClipToOutline(true);
        card.setClickable(true);
        p.card = card;
        int m = dp(a, kind == 2 ? 10 : 12);
        FrameLayout.LayoutParams cl;
        if (kind == 0) {
            cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else if (kind == 1) {
            cl = new FrameLayout.LayoutParams(Math.min(dp(a, 360), dm.widthPixels - 2 * m), ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.gravity = Gravity.CENTER;
        } else {
            cl = new FrameLayout.LayoutParams(Math.min(dp(a, 290), dm.widthPixels - 2 * m), ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        }
        cl.setMargins(m, m, m, m);
        root.addView(card, cl);

        if (title != null) {
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(a, 6), 0, dp(a, 6), 0);
            TextView back = tv(a, "\u2190", 24f, ARROW, false);
            back.setGravity(Gravity.CENTER);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dg.dismiss(); } });
            head.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 56)));
            TextView tt = tv(a, title, 20f, Color.WHITE, false);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            p.title = tt;
            head.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout act = new LinearLayout(a);
            act.setOrientation(LinearLayout.HORIZONTAL);
            act.setGravity(Gravity.CENTER_VERTICAL);
            p.actions = act;
            head.addView(act, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View hl = new View(a);
            hl.setBackgroundColor(LINE);
            card.addView(hl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));
        }

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(a, 4), 0, dp(a, 8));
        p.list = list;
        ScrollView sv;
        if (kind == 0) {
            sv = new ScrollView(a);
            card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            sv = new MaxScroll(a, (int) (dm.heightPixels * 0.68f));
            card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dg.setContentView(root);
        Window w = dg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(kind == 2 ? 0.35f : 0.6f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return p;
    }

    /** Small centred list of actions (bookmark / history item options). */
    private static void options(final Activity a, String head, String[] labels, final Runnable[] acts, int[] colors) {
        final Panel pn = panel(a, null, 1);
        TextView h = tv(a, head, 14f, DIM, true);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        h.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 8));
        pn.list.addView(h);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            LinearLayout r = row(a, null, labels[i], null, null, new View.OnClickListener() {
                @Override public void onClick(View v) { pn.dg.dismiss(); acts[k].run(); }
            });
            if (colors != null && colors[i] != 0) titleColor(r, colors[i]);
            pn.list.addView(r);
        }
        pn.dg.show();
    }

    // ------------------------------------------------------------------ panels

    private static void copyLink(Activity a) {
        String u = curUrl();
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && u.length() > 0) { cm.setPrimaryClip(ClipData.newPlainText("link", u)); toast(a, "Link copied"); }
    }

    private static void openChrome(Activity a) {
        String u = curUrl();
        if (u.length() == 0) u = ENG_H[eng(a)];
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            it.setPackage("com.android.chrome");
            a.startActivity(it);
        } catch (Throwable e) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) {}
        }
    }

    private static void downloads(Activity a) {
        try { a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable e) { toast(a, "No downloads app found"); }
    }

    /** Closes every open panel (Settings, Bookmarks, History...) so the page behind is visible. */
    private static void closePanels() {
        for (Dialog d : new ArrayList<Dialog>(PANELS)) {
            try { if (d.isShowing()) d.dismiss(); } catch (Throwable ignored) {}
        }
        PANELS.clear();
    }

    private static void openInCurrent(Activity a, String u) {
        if (cur == null) return;
        closePanels();
        go(a, cur, u);
        if (mode == 0) setMode(a, 1);
    }

    /** Chrome-style three-dot menu. */
    private static void menu(final Activity a) {
        blur(a);
        final Panel pn = panel(a, null, 2);
        final String cu = curUrl();
        final boolean has = isWeb(cu) && bmHas(a, cu);
        pn.list.addView(row(a, icon(a, "\uFF0B"), "New tab", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); newTab(a, ""); }
        }));
        pn.list.addView(row(a, icon(a, has ? "\u2605" : "\u2606"), has ? "Remove bookmark" : "Add bookmark", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); toggleBookmark(a); }
        }));
        pn.list.addView(row(a, icon(a, "\u2630"), "Bookmarks", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); bookmarks(a, null); }
        }));
        pn.list.addView(row(a, icon(a, "\u27F2"), "History", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); history(a, null); }
        }));
        pn.list.addView(row(a, icon(a, "\uD83D\uDCE5"), "Downloads", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); downloads(a); }
        }));
        pn.list.addView(divider(a));
        final SharedPreferences p = sp(a);
        final Tgl g = new Tgl(a, p.getBoolean("desktop", false));
        pn.list.addView(row(a, icon(a, "\u25AD"), "Desktop site", null, g, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean nv = !p.getBoolean("desktop", false);
                p.edit().putBoolean("desktop", nv).apply();
                g.set(nv);
                applyAll(a, true);
                pn.dg.dismiss();
            }
        }));
        pn.list.addView(row(a, icon(a, "\u29C9"), "Copy link", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); copyLink(a); }
        }));
        pn.list.addView(row(a, icon(a, "\u2197"), "Open in Chrome", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); openChrome(a); }
        }));
        pn.list.addView(row(a, icon(a, "\uD83E\uDDE9"), "Extensions", "Ad blocker, dark mode, reader, scripts", null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); extensions(a); }
        }));
        pn.list.addView(divider(a));
        pn.list.addView(row(a, icon(a, "\u2699"), "Settings", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); settings(a); }
        }));
        pn.dg.show();
    }

    private static View tabCard(final Activity a, final Tab t, final Runnable pick, final Runnable close) {
        boolean on = t == cur;
        String host = hostOf(t.url);
        String label = t.title != null && t.title.length() > 0 ? t.title : (host.length() > 0 ? host : "New tab");
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(a, TILE, 14f, on ? 2.5f : 1.5f, on ? CYAN : EDGE));
        c.setPadding(dp(a, 8), dp(a, 4), dp(a, 4), dp(a, 8));
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pick.run(); } });

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView ti = tv(a, label, 13f, Color.WHITE, true);
        ti.setSingleLine(true);
        ti.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(ti, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = tv(a, "\u2715", 16f, DIM, false);
        x.setGravity(Gravity.CENTER);
        x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { close.run(); } });
        top.addView(x, new LinearLayout.LayoutParams(dp(a, 34), dp(a, 34)));
        c.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 34)));

        FrameLayout mid = new FrameLayout(a);
        mid.setBackground(box(a, 0xFF0A1014, 10f, 0f, 0));
        if (t.icon != null) {
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(t.icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mid.addView(iv, new FrameLayout.LayoutParams(dp(a, 46), dp(a, 46), Gravity.CENTER));
        } else {
            mid.addView(avatar(a, host, 22f), new FrameLayout.LayoutParams(dp(a, 56), dp(a, 56), Gravity.CENTER));
        }
        TextView hh = tv(a, host.length() > 0 ? host : "New tab", 11f, DIM, false);
        hh.setSingleLine(true);
        hh.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.setMargins(dp(a, 6), 0, dp(a, 6), dp(a, 6));
        mid.addView(hh, hp);
        c.addView(mid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return c;
    }

    /** Chrome-style tab switcher: two-column cards, close on each card, plus button for a new tab. */
    private static void tabsPanel(final Activity a) {
        blur(a);
        for (Tab t : TABS) syncTab(t);
        final Panel pn = panel(a, "", 0);
        TextView plus = tv(a, "\uFF0B", 26f, CYAN, false);
        plus.setGravity(Gravity.CENTER);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (newTab(a, "") != null) closePanels(); }
        });
        pn.actions.addView(plus, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        final Runnable[] fill = new Runnable[1];
        fill[0] = new Runnable() {
            @Override public void run() {
                pn.list.removeAllViews();
                int n = TABS.size();
                pn.title.setText(n + (n == 1 ? " tab" : " tabs"));
                final int ch = dp(a, 172);
                LinearLayout line = null;
                for (int i = 0; i < n; i++) {
                    final Tab t = TABS.get(i);
                    if (i % 2 == 0) {
                        line = new LinearLayout(a);
                        line.setOrientation(LinearLayout.HORIZONTAL);
                        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rp.setMargins(dp(a, 8), dp(a, 8), dp(a, 8), 0);
                        pn.list.addView(line, rp);
                    }
                    View c = tabCard(a, t, new Runnable() {
                        @Override public void run() { pn.dg.dismiss(); switchTo(a, t); }
                    }, new Runnable() {
                        @Override public void run() { closeTab(a, t); fill[0].run(); }
                    });
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ch, 1f);
                    cp.setMargins(dp(a, 4), 0, dp(a, 4), 0);
                    line.addView(c, cp);
                }
                if (n % 2 == 1 && line != null) {
                    LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, ch, 1f);
                    sp2.setMargins(dp(a, 4), 0, dp(a, 4), 0);
                    line.addView(new View(a), sp2);
                }
                pn.list.addView(divider(a));
                LinearLayout all = row(a, icon(a, "\u2715"), "Close all tabs", null, null, new View.OnClickListener() {
                    @Override public void onClick(View v) { closeAllTabs(a); pn.dg.dismiss(); }
                });
                titleColor(all, WARN);
                pn.list.addView(all);
            }
        };
        fill[0].run();
        pn.dg.show();
    }

    private static String when(String ts) {
        try {
            long t = Long.parseLong(ts);
            if (t <= 0) return "";
            return new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(t));
        } catch (Throwable e) { return ""; }
    }

    private static void bookmarks(final Activity a, final Runnable onClose) {
        blur(a);
        final Panel pn = panel(a, "Bookmarks", 0);
        if (onClose != null) pn.dg.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) { onClose.run(); } });
        final Runnable[] fill = new Runnable[1];
        fill[0] = new Runnable() {
            @Override public void run() {
                pn.list.removeAllViews();
                final String cu = curUrl();
                final boolean web = isWeb(cu);
                final boolean has = web && bmHas(a, cu);
                pn.list.addView(row(a, icon(a, has ? "\u2605" : "\u2606"), has ? "Remove this page" : "Bookmark this page",
                        web ? pretty(cu) : "No page open", null, new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleBookmark(a); fill[0].run(); }
                }));
                pn.list.addView(divider(a));
                ArrayList<String[]> bm = bmLoad(a);
                if (bm.isEmpty()) {
                    TextView e = tv(a, "No bookmarks yet", 15f, DIM, false);
                    e.setGravity(Gravity.CENTER);
                    e.setPadding(0, dp(a, 40), 0, dp(a, 40));
                    pn.list.addView(e);
                    return;
                }
                for (final String[] b : bm) {
                    TextView more = tv(a, "\u22EE", 22f, DIM, false);
                    more.setGravity(Gravity.CENTER);
                    more.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
                    more.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            options(a, b[0].length() > 0 ? b[0] : pretty(b[1]),
                                    new String[]{"Open in new tab", "Copy link", "Delete bookmark"},
                                    new Runnable[]{
                                        new Runnable() { @Override public void run() { if (newTab(a, b[1]) != null) closePanels(); } },
                                        new Runnable() {
                                            @Override public void run() {
                                                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                                                if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("link", b[1])); toast(a, "Link copied"); }
                                            }
                                        },
                                        new Runnable() {
                                            @Override public void run() {
                                                ArrayList<String[]> l = bmLoad(a);
                                                String k = norm(b[1]);
                                                for (Iterator<String[]> it = l.iterator(); it.hasNext(); ) if (norm(it.next()[1]).equals(k)) it.remove();
                                                bmSave(a, l);
                                                toast(a, "Bookmark removed");
                                                fill[0].run();
                                            }
                                        }},
                                    new int[]{0, 0, WARN});
                        }
                    });
                    pn.list.addView(row(a, avatar(a, hostOf(b[1]), 16f), b[0].length() > 0 ? b[0] : pretty(b[1]), pretty(b[1]), more, new View.OnClickListener() {
                        @Override public void onClick(View v) { openInCurrent(a, b[1]); pn.dg.dismiss(); }
                    }));
                }
            }
        };
        fill[0].run();
        pn.dg.show();
    }

    private static void history(final Activity a, final Runnable onClose) {
        blur(a);
        final Panel pn = panel(a, "History", 0);
        if (onClose != null) pn.dg.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) { onClose.run(); } });
        final Runnable[] fill = new Runnable[1];
        TextView clr = tv(a, "Clear all", 15f, CYAN, true);
        clr.setGravity(Gravity.CENTER);
        clr.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        clr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { histClear(a); toast(a, "History cleared"); fill[0].run(); }
        });
        pn.actions.addView(clr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48)));
        fill[0] = new Runnable() {
            @Override public void run() {
                pn.list.removeAllViews();
                ArrayList<String[]> h = histLoad(a);
                if (h.isEmpty()) {
                    TextView e = tv(a, "No history", 15f, DIM, false);
                    e.setGravity(Gravity.CENTER);
                    e.setPadding(0, dp(a, 40), 0, dp(a, 40));
                    pn.list.addView(e);
                    return;
                }
                for (final String[] it : h) {
                    TextView x = tv(a, "\u2715", 16f, DIM, false);
                    x.setGravity(Gravity.CENTER);
                    x.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
                    x.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            ArrayList<String[]> l = histLoad(a);
                            for (Iterator<String[]> i2 = l.iterator(); i2.hasNext(); ) {
                                String[] e2 = i2.next();
                                if (e2[1].equals(it[1]) && e2[2].equals(it[2])) { i2.remove(); break; }
                            }
                            histSave(a, l);
                            fill[0].run();
                        }
                    });
                    String w = when(it[2]);
                    String sub = hostOf(it[1]) + (w.length() > 0 ? "  \u00B7  " + w : "");
                    pn.list.addView(row(a, avatar(a, hostOf(it[1]), 16f), it[0].length() > 0 ? it[0] : pretty(it[1]), sub, x, new View.OnClickListener() {
                        @Override public void onClick(View v) { openInCurrent(a, it[1]); pn.dg.dismiss(); }
                    }));
                }
            }
        };
        fill[0].run();
        pn.dg.show();
    }

    private static void engines(final Activity a, final Runnable after) {
        final Panel pn = panel(a, "Search engine", 1);
        for (int i = 0; i < ENG_N.length; i++) {
            final int k = i;
            boolean sel = i == eng(a);
            TextView rb = tv(a, sel ? "\u25C9" : "\u25CB", 22f, sel ? CYAN : DIM, false);
            pn.list.addView(row(a, null, ENG_N[i], hostOf(ENG_H[i]), rb, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sp(a).edit().putInt("engine", k).apply();
                    pn.dg.dismiss();
                    if (after != null) after.run();
                }
            }));
        }
        pn.dg.show();
    }

    private static void clearData(final Activity a, final Runnable after) {
        final Panel pn = panel(a, "Clear browsing data", 1);
        final boolean[] on = {true, true, true};
        final String[] names = {"Browsing history", "Cookies and site data", "Cached images and files"};
        final String[] subs = {"Saved pages list and tab back history", "Signs you out of most sites", "Frees storage, pages load slower once"};
        TextView range = tv(a, "Time range: All time", 13f, DIM, false);
        range.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 4));
        pn.list.addView(range);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            final TextView cb = tv(a, "\u2611", 24f, CYAN, false);
            pn.list.addView(row(a, null, names[i], subs[i], cb, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    on[k] = !on[k];
                    cb.setText(on[k] ? "\u2611" : "\u2610");
                    cb.setTextColor(on[k] ? CYAN : DIM);
                }
            }));
        }
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(a, 8), dp(a, 4), dp(a, 14), dp(a, 12));
        TextView no = tv(a, "Cancel", 15f, DIM, true);
        no.setGravity(Gravity.CENTER);
        no.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
        no.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pn.dg.dismiss(); } });
        TextView yes = tv(a, "Clear data", 15f, CYAN, true);
        yes.setGravity(Gravity.CENTER);
        yes.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
        yes.setBackground(box(a, 0x2200E5FF, 18f, 1.5f, CYAN));
        yes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    if (on[0]) { histClear(a); for (Tab t : TABS) if (t.wv != null) t.wv.clearHistory(); }
                    if (on[1]) {
                        CookieManager cm = CookieManager.getInstance();
                        cm.removeAllCookies(null);
                        cm.flush();
                        WebStorage.getInstance().deleteAllData();
                        a.getSharedPreferences("fz_cookies", Context.MODE_PRIVATE).edit().clear().apply();
                    }
                    if (on[2]) { WebView w = curWv(); if (w != null) w.clearCache(true); }
                    toast(a, "Browsing data cleared");
                } catch (Throwable e) { toast(a, "Could not clear everything"); }
                pn.dg.dismiss();
                if (after != null) after.run();
            }
        });
        foot.addView(no);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yp.setMargins(dp(a, 8), 0, 0, 0);
        foot.addView(yes, yp);
        pn.card.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pn.dg.show();
    }

    private static View toggleRow(final Activity a, String title, final String subOn, final String subOff, final String key, final boolean def, final Runnable after) {
        final SharedPreferences p = sp(a);
        boolean now = p.getBoolean(key, def);
        final Tgl g = new Tgl(a, now);
        final LinearLayout r = row(a, null, title, now ? subOn : subOff, g, null);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean nv = !p.getBoolean(key, def);
                p.edit().putBoolean(key, nv).apply();
                g.set(nv);
                View s = r.findViewWithTag("sub");
                if (s instanceof TextView) ((TextView) s).setText(nv ? subOn : subOff);
                if (after != null) after.run();
            }
        });
        return r;
    }

    /** V68: extensions + extra browser switches. */
    private static void extensions(final Activity a) {
        blur(a);
        final Panel pn = panel(a, "Extensions", 0);
        final SharedPreferences p = sp(a);
        final Runnable re = new Runnable() { @Override public void run() { applyAll(a, true); } };
        pn.list.addView(section(a, "Extensions"));
        pn.list.addView(toggleRow(a, "Ad and tracker blocker", "On, known ad and tracking hosts are blocked", "Off", "x_ads", true, re));
        pn.list.addView(toggleRow(a, "Force dark mode", "On, pages are inverted to dark", "Off", "x_dark", false, re));
        pn.list.addView(toggleRow(a, "Video grabber", "On, a Save video button shows on pages with video", "Off", "x_vid", true, re));
        pn.list.addView(toggleRow(a, "Cookie popup remover", "On, common consent banners are hidden", "Off", "x_cookie", false, re));
        pn.list.addView(row(a, null, "Reader mode", "Clean text view of this page", null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); try { cur.wv.evaluateJavascript(BrowserExt.READER, null); } catch (Throwable ignored) {} }
        }));
        pn.list.addView(row(a, null, "Audio downloader", "Find and save audio files on this page", null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); try { cur.wv.evaluateJavascript(BrowserExt.GRAB + "('a')", null); } catch (Throwable ignored) {} }
        }));
        pn.list.addView(row(a, null, "Bulk image download", "List every image here, save one or all", null, new View.OnClickListener() {
            @Override public void onClick(View v) { pn.dg.dismiss(); try { cur.wv.evaluateJavascript(BrowserExt.GRAB + "('i')", null); } catch (Throwable ignored) {} }
        }));
        pn.list.addView(row(a, null, "My scripts", p.getString("x_js", "").length() > 0 ? "Saved, runs on every page" : "Run your own JavaScript on every page", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pn.dg.dismiss();
                final android.widget.EditText et = new android.widget.EditText(a);
                et.setText(p.getString("x_js", ""));
                et.setMinLines(6);
                et.setGravity(Gravity.TOP);
                new android.app.AlertDialog.Builder(a).setTitle("My scripts").setView(et)
                    .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { p.edit().putString("x_js", et.getText().toString()).apply(); toast(a, "Saved. Reload the page."); } })
                    .setNegativeButton("Cancel", null).show();
            }
        }));
        pn.list.addView(divider(a));
        pn.list.addView(section(a, "More"));
        pn.list.addView(toggleRow(a, "Private mode", "On, pages are not saved to history", "Off", "priv", false, null));
        final int[] Z = {80, 100, 120, 150, 180};
        pn.list.addView(row(a, null, "Page zoom", p.getInt("zoom", 100) + "%", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int c = p.getInt("zoom", 100), n = Z[0];
                for (int i = 0; i < Z.length; i++) if (Z[i] == c) n = Z[(i + 1) % Z.length];
                p.edit().putInt("zoom", n).apply();
                applyAll(a, false);
                pn.dg.dismiss();
                extensions(a);
            }
        }));
        pn.dg.show();
    }

    /** Chrome-style settings page in a bordered panel. */
    private static void settings(final Activity a) {
        blur(a);
        final Panel pn = panel(a, "Settings", 0);
        final Runnable[] fill = new Runnable[1];
        fill[0] = new Runnable() {
            @Override public void run() {
                pn.list.removeAllViews();
                final Runnable again = fill[0];
                pn.list.addView(section(a, "Basics"));
                pn.list.addView(row(a, null, "Search engine", ENG_N[eng(a)], null, new View.OnClickListener() {
                    @Override public void onClick(View v) { engines(a, again); }
                }));
                int nb = bmLoad(a).size();
                pn.list.addView(row(a, null, "Bookmarks", nb == 0 ? "None saved yet" : nb + (nb == 1 ? " page saved" : " pages saved"), null, new View.OnClickListener() {
                    @Override public void onClick(View v) { bookmarks(a, again); }
                }));
                int nh = histLoad(a).size();
                pn.list.addView(row(a, null, "History", nh == 0 ? "Empty" : nh + (nh == 1 ? " page" : " pages"), null, new View.OnClickListener() {
                    @Override public void onClick(View v) { history(a, again); }
                }));
                pn.list.addView(row(a, null, "Downloads", "Open the Downloads list", null, new View.OnClickListener() {
                    @Override public void onClick(View v) { downloads(a); }
                }));
                pn.list.addView(divider(a));
                pn.list.addView(section(a, "Site settings"));
                pn.list.addView(toggleRow(a, "Desktop site", "On, sites load their desktop version", "Off", "desktop", false, new Runnable() {
                    @Override public void run() { applyAll(a, true); }
                }));
                pn.list.addView(toggleRow(a, "JavaScript", "Allowed", "Blocked, many sites will not work", "js", true, new Runnable() {
                    @Override public void run() { applyAll(a, true); }
                }));
                pn.list.addView(toggleRow(a, "Cookies", "Allowed", "Blocked, you will be signed out of sites", "cookies", true, new Runnable() {
                    @Override public void run() { applyAll(a, false); }
                }));
                pn.list.addView(toggleRow(a, "Third-party cookies", "Allowed", "Blocked", "tp", true, new Runnable() {
                    @Override public void run() { applyAll(a, false); }
                }));
                pn.list.addView(divider(a));
                pn.list.addView(section(a, "Privacy and security"));
                pn.list.addView(row(a, null, "Clear browsing data", "History, cookies, cache and site data", null, new View.OnClickListener() {
                    @Override public void onClick(View v) { clearData(a, again); }
                }));
                pn.list.addView(divider(a));
                pn.list.addView(section(a, "About"));
                String ver = "";
                try { Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(baseUa == null ? "" : baseUa); if (m.find()) ver = m.group(1); } catch (Throwable ignored) {}
                pn.list.addView(row(a, null, "Prodigy Browser", ver.length() > 0 ? "Chromium " + ver + " (Android System WebView)" : "Android System WebView", null, null));
            }
        };
        fill[0].run();
        pn.dg.show();
    }

    // ------------------------------------------------------------------ the sheet itself

    private static void build(final Activity a, String url) {
        final float d = a.getResources().getDisplayMetrics().density;
        mode = 1; actRef = a; lastH = -1; lastVisBottom = -1;
        final Dialog dg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar);
        dlg = dg;

        final LinearLayout root = new LinearLayout(a);
        rootV = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        root.setFitsSystemWindows(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getWindowVisibleDisplayFrame(KB_R);
                if (KB_R.bottom == lastVisBottom) return;
                lastVisBottom = KB_R.bottom;
                kbSync(a, root);
            }
        });
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                WindowInsets r = v.onApplyWindowInsets(insets);
                v.post(new Runnable() { @Override public void run() { kbSync(a, root); } });
                return r;
            }
        });

        // ---- top bar: close | minimise | maximise | address | tabs | menu
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PANEL);
        top.setPadding((int) (6 * d), (int) (5 * d), (int) (6 * d), (int) (5 * d));
        top.addView(btn(a, "\u2715", GREEN, new View.OnClickListener() { @Override public void onClick(View v) { dg.dismiss(); } }),
                new LinearLayout.LayoutParams((int) (42 * d), (int) (42 * d)));
        minB = btn(a, "\u25BE", ARROW, new View.OnClickListener() { @Override public void onClick(View v) { setMode(a, mode == 0 ? 1 : 0); } });
        top.addView(minB, new LinearLayout.LayoutParams((int) (38 * d), (int) (42 * d)));
        maxB = btn(a, "\u2922", ARROW, new View.OnClickListener() { @Override public void onClick(View v) { setMode(a, mode == 2 ? 1 : 2); } });
        top.addView(maxB, new LinearLayout.LayoutParams((int) (38 * d), (int) (42 * d)));

        final EditText ed = new EditText(a);
        addr = ed;
        ed.setSingleLine(true);
        ed.setTextColor(Color.WHITE);
        ed.setHintTextColor(DIM);
        ed.setHint("Search or type URL");
        ed.setTextSize(14f);
        ed.setImeOptions(EditorInfo.IME_ACTION_GO);
        ed.setSelectAllOnFocus(true);
        ed.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(0xFF10181D);
        ab.setCornerRadius(21 * d);
        ab.setStroke((int) (1 * d), GREEN);
        ed.setBackground(ab);
        ed.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        ed.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    go(a, cur, target(a, v.getText().toString()));
                    hideKb(a, v);
                    v.clearFocus();
                    root.requestFocus();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (42 * d), 1f);
        lp.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
        top.addView(ed, lp);

        FrameLayout tabHost = new FrameLayout(a);
        tabHost.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tabsPanel(a); } });
        TextView tb = new TextView(a);
        tabsB = tb;
        tb.setText("1");
        tb.setTextColor(CYAN);
        tb.setTextSize(12f);
        tb.setTypeface(Typeface.DEFAULT_BOLD);
        tb.setGravity(Gravity.CENTER);
        tb.setBackground(box(a, Color.TRANSPARENT, 6f, 2f, CYAN));
        tabHost.addView(tb, new FrameLayout.LayoutParams((int) (25 * d), (int) (25 * d), Gravity.CENTER));
        top.addView(tabHost, new LinearLayout.LayoutParams((int) (42 * d), (int) (42 * d)));
        top.addView(btn(a, "\u22EE", CYAN, new View.OnClickListener() { @Override public void onClick(View v) { menu(a); } }),
                new LinearLayout.LayoutParams((int) (38 * d), (int) (42 * d)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        try { bar.getProgressDrawable().setColorFilter(GREEN, PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) {}
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * d)));

        holder = new FrameLayout(a);
        holder.setBackgroundColor(BLACK);
        root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- bottom bar: back | forward | home | reload | open in Chrome
        LinearLayout bot = new LinearLayout(a);
        bot.setOrientation(LinearLayout.HORIZONTAL);
        bot.setBackgroundColor(PANEL);
        bot.addView(btn(a, "\u25C0", ARROW, new View.OnClickListener() { @Override public void onClick(View v) { WebView w = curWv(); if (w != null && w.canGoBack()) w.goBack(); } }), new LinearLayout.LayoutParams(0, (int) (44 * d), 1f));
        bot.addView(btn(a, "\u25B6", ARROW, new View.OnClickListener() { @Override public void onClick(View v) { WebView w = curWv(); if (w != null && w.canGoForward()) w.goForward(); } }), new LinearLayout.LayoutParams(0, (int) (44 * d), 1f));
        bot.addView(btn(a, "\u2302", GREEN, new View.OnClickListener() { @Override public void onClick(View v) { go(a, cur, ENG_H[eng(a)]); } }), new LinearLayout.LayoutParams(0, (int) (44 * d), 1f));
        bot.addView(btn(a, "\u27F3", ARROW, new View.OnClickListener() { @Override public void onClick(View v) { WebView w = curWv(); if (w != null) w.reload(); } }), new LinearLayout.LayoutParams(0, (int) (44 * d), 1f));
        dlB = btn(a, "", BLACK, new View.OnClickListener() { @Override public void onClick(View v) { ytDownload(a); } });
        dlB.setBackground(dlIcon(d)); // V84: black circle, green ring, green down arrow
        dlB.setContentDescription("Download");
        dlB.setVisibility(View.GONE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (38 * d), (int) (38 * d));
        dlp.gravity = Gravity.CENTER_VERTICAL;
        dlp.setMargins((int) (10 * d), (int) (3 * d), (int) (10 * d), (int) (3 * d));
        bot.addView(dlB, dlp);
        bot.addView(btn(a, "\u2197", ARROW, new View.OnClickListener() { @Override public void onClick(View v) { openChrome(a); } }), new LinearLayout.LayoutParams(0, (int) (44 * d), 1f));
        root.addView(bot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dg.setContentView(root);
        dg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface di, int code, KeyEvent ev) {
                if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                    if (customView != null) { hideCustom(dg); setMode(a, prevMode); return true; }
                    WebView w = curWv();
                    if (w != null && w.canGoBack()) { w.goBack(); return true; }
                    if (cur != null && cur.parent != null && TABS.contains(cur.parent)) { closeTab(a, cur); return true; }
                }
                return false;
            }
        });
        dg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) {
                for (Tab t : TABS) saveCookies(a, t.url);
                try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                hideCustom(dg);
                if (fileCb != null) { try { fileCb.onReceiveValue(null); } catch (Throwable ignored) {} fileCb = null; }
                persist(a);
                closePanels();
                try { if (holder != null) holder.removeAllViews(); } catch (Throwable ignored) {}
                for (Tab t : TABS) killWv(t);
                dlg = null; rootV = null; holder = null; addr = null; bar = null; tabsB = null; minB = null; maxB = null; actRef = null; kbUp = false; kbAuto = false; lastH = -1; lastVisBottom = -1; if (kbRun != null) { KB_H.removeCallbacks(kbRun); kbRun = null; }
            }
        });

        Window win = dg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(BLACK));
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            win.setWindowAnimations(android.R.style.Animation_InputMethod);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        setMode(a, 1);
        dg.show();
        root.requestFocus();
        restoreTabs(a, url);
    }
}
