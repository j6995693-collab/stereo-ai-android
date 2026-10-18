package com.stereo.ai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.util.Log;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.MimeTypeMap;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private WebView wv;
    private static final String BASE = "http://127.0.0.1:8765/";
    private ValueCallback<Uri[]> fileCb;
    private MediaRecorder voiceRec;
    private String voicePath;
    private boolean voiceOn;
    private long voiceStartT;
    private AudioManager am;
    private boolean spkOn;
    private int prevMode = -1;
    private int prevVol = -1;
    static volatile boolean foreground = false;
    static volatile String curUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StereoService.start(this);
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSavePassword(false);
        s.setSaveFormData(false);
        s.setGeolocationEnabled(false);
        s.setDatabaseEnabled(false);
        try { WebView.setWebContentsDebuggingEnabled(false); } catch (Throwable ig) { }
        wv.setBackgroundColor(0xFFFFFFFF);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        bridge = new VoiceBridge();
        wv.addJavascriptInterface(bridge, "Android");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return navGuard(r == null ? null : r.getUrl());
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                curUrl = url == null ? "" : url;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadData("<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;color:#8a909b'><div style='text-align:center'><p>AI 服务启动中…</p><p style='font-size:13px'>服务在 APP 内部运行，稍等片刻会自动恢复</p></div></body></html>", "text/html; charset=utf-8", "utf-8");
                }
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Uri origin = request.getOrigin();
                        String scheme = origin == null ? null : origin.getScheme();
                        String host = origin == null ? null : origin.getHost();
                        if ("http".equals(scheme) && "127.0.0.1".equals(host)) {
                            request.grant(request.getResources());
                        } else {
                            request.deny();
                        }
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择文件"), 7);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(wv);
        if (checkSelfPermission("android.permission.RECORD_AUDIO")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 1);
        }
        if (checkSelfPermission("android.permission.READ_CALENDAR")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    "android.permission.READ_CALENDAR",
                    "android.permission.WRITE_CALENDAR",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"}, 41);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 42);
        }
        handleIntent(getIntent());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grantResults) {
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("AIAPK", "perm denied req=" + req + " " + perms[i]);
            }
        }
        super.onRequestPermissionsResult(req, perms, grantResults);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        StereoService.start(this);
        final String u = wv == null ? null : wv.getUrl();
        if (u == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (probe()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { loadWhenReady(u); }
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        foreground = false;
    }

    private void loadWhenReady(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 15000) {
                    if (probe()) break;
                    try { Thread.sleep(400); } catch (Exception e) { }
                }
                go(url);
            }
        }).start();
    }

    private boolean probe() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE).openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(700);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) { return false; }
    }

    private void go(final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() { wv.loadUrl(url); }
        });
    }

    private void goHdr(final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                java.util.HashMap<String, String> h = new java.util.HashMap<String, String>();
                try {
                    String t = Store.getToken(MainActivity.this);
                    if (t != null && t.length() > 0) h.put("x-ai-token", t);
                } catch (Exception ig) { }
                wv.loadUrl(url, h);
            }
        });
    }

    private VoiceBridge bridge;

    @Override
    public void onBackPressed() {
        String u = wv.getUrl();
        if (u != null && u.startsWith(BASE) && !BASE.equals(u)) { go(BASE); return; }
        boolean home = bridge != null && bridge.homeMode();
        Log.i("AIAPK", "back: url=" + u + " home=" + home);
        if (u != null && u.startsWith(BASE) && !home) {
            wv.evaluateJavascript("try{openSheet('histSheet');loadConvs()}catch(e){}", null);
            return;
        }
        moveTaskToBack(true);
    }

    private boolean navGuard(Uri u) {
        if (u == null) return true;
        String scheme = u.getScheme();
        String host = u.getHost();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception e) { }
            return true;
        }
        if ("file".equals(scheme) || "content".equals(scheme) || "javascript".equals(scheme)
                || "data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme)
                || scheme == null) {
            Log.e("AIAPK", "nav blocked scheme=" + scheme);
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) { }
        return true;
    }

    private void handleIntent(Intent i) {
        String action = i == null ? null : i.getAction();
        boolean share = Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action)
                || Intent.ACTION_VIEW.equals(action);
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri d = i.getData();
            if (d != null && AiFiles.AUTHORITY.equals(d.getAuthority())) {
                loadWhenReady(BASE);
                return;
            }
        }
        if (!share) {
            loadWhenReady(BASE);
            return;
        }
        final Intent fi = i;
        new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                if (Intent.ACTION_VIEW.equals(fi.getAction())) {
                    Uri d = fi.getData();
                    if (d != null) uris.add(d);
                } else {
                    ArrayList<Uri> many = null;
                    try {
                        many = fi.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    } catch (Exception ig) { }
                    if (many != null) {
                        uris.addAll(many);
                    } else {
                        Uri one = fi.getParcelableExtra(Intent.EXTRA_STREAM);
                        if (one != null) uris.add(one);
                    }
                }
                {
                    java.util.LinkedHashSet<Uri> uniq = new java.util.LinkedHashSet<Uri>(uris);
                    uris = new ArrayList<Uri>(uniq);
                }
                Log.e("AIAPK", "share uris=" + uris.size() + " action=" + fi.getAction());
                StringBuilder attach = new StringBuilder("[");
                int ok = 0;
                for (Uri u : uris) {
                    String[] r = uploadStream(u);
                    if (r != null) {
                        if (ok > 0) attach.append(",");
                        attach.append("{\"path\":\"").append(jsq(r[0]))
                              .append("\",\"name\":\"").append(jsq(r[1])).append("\"}");
                        ok++;
                    }
                }
                attach.append("]");
                String text = fi.getStringExtra(Intent.EXTRA_TEXT);
                String url = BASE;
                if (ok > 0) url += "?attach=" + URLEncoder.encode(attach.toString());
                if (text != null && text.trim().length() > 0) {
                    url += (url.indexOf('?') >= 0 ? "&" : "?") + "text=" + URLEncoder.encode(text);
                }
                final String fu = url;
                final int fok = ok;
                final int fall = uris.size();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadWhenReady(fu);
                        if (fall > 0 && fok == 0) {
                            Toast.makeText(MainActivity.this,
                                    "文件接收失败，请重试",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 7) {
            super.onActivityResult(req, res, data);
            return;
        }
        final ValueCallback<Uri[]> cb = fileCb;
        fileCb = null;
        if (cb == null) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (data != null && data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        if (uris.isEmpty() && data != null && data.getData() != null) {
            uris.add(data.getData());
        }
        java.util.LinkedHashSet<Uri> uniq = new java.util.LinkedHashSet<Uri>(uris);
        uris = new ArrayList<Uri>(uniq);
        Log.i("AIAPK", "file chooser result n=" + uris.size() + " req=" + req);
        if (uris.isEmpty()) {
            cb.onReceiveValue(null);
            return;
        }
        cb.onReceiveValue(uris.toArray(new Uri[0]));
        final ArrayList<Uri> fu = uris;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder js = new StringBuilder();
                int ok = 0;
                for (Uri u : fu) {
                    String[] r = uploadStream(u);
                    if (r != null) {
                        if (ok > 0) js.append(";");
                        js.append("addAttach('").append(jsq(r[0])).append("','").append(jsq(r[1]))
                          .append("',").append(r.length > 2 && r[2] != null ? r[2] : "0").append(")");
                        ok++;
                    }
                }
                final int fok = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fok == 0) toast("文件上传失败");
                        else wv.evaluateJavascript(js.toString(), null);
                    }
                });
            }
        }).start();
    }

    private String[] uploadStream(Uri u) {
        try {
            ContentResolver cr = getContentResolver();
            String name = "";
            try {
                Cursor c = cr.query(u, null, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst() && c.getString(idx) != null) {
                        name = c.getString(idx);
                    }
                    c.close();
                }
            } catch (Exception e) { }
            if ((name == null || name.length() == 0) && u.getLastPathSegment() != null) {
                name = u.getLastPathSegment();
            }
            if (name == null || name.length() == 0) name = "file";
            InputStream in = cr.openInputStream(u);
            ByteArrayOutputStream cache = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while (in != null && (n = in.read(buf)) > 0) { cache.write(buf, 0, n); total += n; }
            try { in.close(); } catch (Exception ig) { }
            if (total == 0) {
                Log.e("AIAPK", "uploadStream empty stream (no read permission on source?): " + u);
                return null;
            }
            byte[] data = cache.toByteArray();
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(
                            BASE + "api/upload?filename=" + URLEncoder.encode(name, "UTF-8") + tkParam()
                    ).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(15000);
                    conn.setFixedLengthStreamingMode(data.length);
                    OutputStream out = conn.getOutputStream();
                    out.write(data);
                    out.close();
                    int code = conn.getResponseCode();
                    InputStream rs = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] rb = new byte[4096];
                    int rn;
                    while ((rn = rs.read(rb)) > 0) bos.write(rb, 0, rn);
                    rs.close();
                    String resp = new String(bos.toByteArray(), "UTF-8");
                    String path = jsonStr(resp, "path");
                    if (path != null) return new String[]{path, name, String.valueOf(total)};
                    Log.e("AIAPK", "uploadStream no path http=" + code + " resp=" + resp.substring(0, Math.min(120, resp.length())) + " u=" + u);
                } catch (Exception e) {
                    Log.e("AIAPK", "uploadStream attempt" + attempt + " fail " + u + " : " + e, e);
                }
                try { Thread.sleep(400); } catch (InterruptedException ig) { }
            }
        } catch (Exception e) {
            Log.e("AIAPK", "uploadStream fail " + u + " : " + e, e);
        }
        return null;
    }

    private static String jsonStr(String resp, String key) {
        int k = resp.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = resp.indexOf(':', k + key.length() + 2);
        if (colon < 0) return null;
        int s2 = colon + 1;
        while (s2 < resp.length() && Character.isWhitespace(resp.charAt(s2))) s2++;
        if (s2 >= resp.length() || resp.charAt(s2) != '"') return null;
        s2++;
        StringBuilder sb = new StringBuilder();
        while (s2 < resp.length()) {
            char ch = resp.charAt(s2);
            if (ch == '"') break;
            if (ch == '\\' && s2 + 1 < resp.length()) {
                char nx = resp.charAt(s2 + 1);
                if (nx == 'n') { sb.append('\n'); s2 += 2; continue; }
                if (nx == '"' || nx == '\\' || nx == '/') { sb.append(nx); s2 += 2; continue; }
                if (nx == 'u' && s2 + 5 < resp.length()) {
                    try {
                        sb.append((char) Integer.parseInt(resp.substring(s2 + 2, s2 + 6), 16));
                        s2 += 6;
                        continue;
                    } catch (Exception e) { }
                }
                sb.append(nx);
                s2 += 2;
                continue;
            }
            sb.append(ch);
            s2++;
        }
        return sb.toString();
    }

    private class VoiceBridge {
        private volatile boolean homeMode = false;

        boolean homeMode() { return homeMode; }

        @JavascriptInterface
        public void uiState(String s) {
            homeMode = "home".equals(s);
        }

        @JavascriptInterface
        public void hideKb() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager)
                                        getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null && wv != null) {
                            try {
                                imm.restartInput(wv);
                            } catch (Exception ig) { }
                            imm.hideSoftInputFromWindow(wv.getWindowToken(), 0);
                            wv.clearFocus();
                        }
                    } catch (Exception e) { }
                }
            });
        }

        @JavascriptInterface
        public String startVoice() {
            if (voiceOn) return "started";
            if (checkSelfPermission("android.permission.RECORD_AUDIO")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 1);
                return "noperm";
            }
            try {
                voicePath = getCacheDir().getAbsolutePath() + "/voice_" + System.currentTimeMillis() + ".m4a";
                voiceRec = new MediaRecorder();
                voiceRec.setAudioSource(MediaRecorder.AudioSource.MIC);
                voiceRec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                voiceRec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                voiceRec.setAudioSamplingRate(44100);
                voiceRec.setAudioEncodingBitRate(96000);
                voiceRec.setOutputFile(voicePath);
                voiceRec.prepare();
                voiceRec.start();
                voiceOn = true;
                voiceStartT = System.currentTimeMillis();
                return "started";
            } catch (Exception e) {
                Log.e("AIAPK", "startVoice fail", e);
                releaseRec();
                return "error:" + e.getClass().getSimpleName();
            }
        }

        @JavascriptInterface
        public String stopVoice() {
            if (!voiceOn) return "idle";
            voiceOn = false;
            long dur = System.currentTimeMillis() - voiceStartT;
            if (dur < 500) {
                releaseRec();
                notifyVoice("", "");
                return "short";
            }
            try {
                voiceRec.stop();
            } catch (Exception e) {
                Log.e("AIAPK", "stopVoice fail", e);
                releaseRec();
                notifyVoice("", "");
                return "error";
            }
            releaseRecKeepFile();
            final String fp = voicePath;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String text = asrFile(fp);
                    Log.e("AIAPK", "asr result len=" + (text == null ? -1 : text.length()));
                    String dest = fp == null ? "" : uploadVoice(fp);
                    if (fp != null) new File(fp).delete();
                    notifyVoice(text, dest);
                }
            }).start();
            return "stopped";
        }

        @JavascriptInterface
        public boolean isRecording() {
            return voiceOn;
        }

        @JavascriptInterface
        public void haptic(String kind) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 29) {
                    int id = "heavy".equals(kind) ? VibrationEffect.EFFECT_HEAVY_CLICK
                            : ("click".equals(kind) ? VibrationEffect.EFFECT_CLICK
                            : VibrationEffect.EFFECT_TICK);
                    v.vibrate(VibrationEffect.createPredefined(id));
                } else {
                    v.vibrate(VibrationEffect.createOneShot(
                            "heavy".equals(kind) ? 28 : 14, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public String setSpeaker(boolean on) {
            try {
                if (am == null) am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am == null) return "noam";
                if (on) {
                    if (prevMode < 0) prevMode = am.getMode();
                    if (prevVol < 0) prevVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    boolean ok = false;
                    if (Build.VERSION.SDK_INT >= 31) {
                        try {
                            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                    ok = am.setCommunicationDevice(d);
                                    break;
                                }
                            }
                        } catch (Exception e) { }
                    }
                    if (!ok) am.setSpeakerphoneOn(true);
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                            am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
                    spkOn = true;
                    return ok ? "speaker" : "speaker-legacy";
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    try { am.clearCommunicationDevice(); } catch (Exception e) { }
                }
                am.setSpeakerphoneOn(false);
                if (prevMode >= 0) am.setMode(prevMode);
                if (prevVol >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVol, 0);
                prevMode = -1;
                prevVol = -1;
                spkOn = false;
                return "earpiece";
            } catch (Exception e) {
                Log.e("AIAPK", "setSpeaker fail", e);
                return "err:" + e.getClass().getSimpleName();
            }
        }

        @JavascriptInterface
        public boolean isSpeakerOn() {
            return spkOn;
        }

        @JavascriptInterface
        public String aiToken() {
            try {
                String u = curUrl;
                if (u == null || !u.startsWith(BASE)) return "";
                if (u.contains("api/audio?path=")) return "";
                return Store.getToken(MainActivity.this);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean hasAllFiles() {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    return android.os.Environment.isExternalStorageManager();
                }
                return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String openAllFilesSettings() {
            try {
                if (android.os.Build.VERSION.SDK_INT < 30) return "old";
                Intent i = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "ok";
            } catch (Exception e) {
                try {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return "ok";
                } catch (Exception e2) {
                    return "err:" + e2;
                }
            }
        }

        @JavascriptInterface
        public String openFile(String path) {
            if (path == null || path.length() == 0) return "err:empty";
            if (!isPrivatePath(path)) return "err:bad";
            final String p = path;
            new Thread(new Runnable() {
                @Override
                public void run() { fetchAndOpen(p); }
            }).start();
            return "opening";
        }

        @JavascriptInterface
        public String shareFile(String path) {
            if (path == null || path.length() == 0) return "err:empty";
            if (!isPrivatePath(path)) return "err:bad";
            final String p = path;
            new Thread(new Runnable() {
                @Override
                public void run() { fetchAndShare(p); }
            }).start();
            return "sharing";
        }

        @JavascriptInterface
        public String cancelVoice() {
            if (!voiceOn && voicePath == null) return "idle";
            voiceOn = false;
            releaseRec();
            if (voicePath != null) {
                try { new File(voicePath).delete(); } catch (Exception e) { }
                voicePath = null;
            }
            return "canceled";
        }

        @JavascriptInterface
        public String deleteVoice(String path) {
            if (path == null || path.length() == 0) return "err:empty";
            if (!path.startsWith("uploads/voice_") || path.contains("..")) return "err:bad";
            try {
                File f = new File(Store.uploadsDir(MainActivity.this), path.substring("uploads/".length()));
                if (f.isFile() && f.delete()) return "ok";
                return "err:missing";
            } catch (Exception e) {
                return "err:" + e.getClass().getSimpleName();
            }
        }
    }

    private void releaseRec() {
        try { voiceRec.release(); } catch (Exception e) { }
        voiceRec = null;
        voiceOn = false;
    }

    private void releaseRecKeepFile() {
        try { voiceRec.release(); } catch (Exception e) { }
        voiceRec = null;
    }

    private String asrFile(String path) {
        try {
            File f = new File(path);
            FileInputStream fin = new FileInputStream(f);
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE + "api/asr" + tkParam().replace("&", "?")).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode((int) f.length());
            OutputStream out = conn.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
            fin.close();
            out.close();
            InputStream rs = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] rb = new byte[4096];
            int rn;
            while ((rn = rs.read(rb)) > 0) bos.write(rb, 0, rn);
            rs.close();
            String resp = new String(bos.toByteArray(), "UTF-8");
            String t = jsonStr(resp, "text");
            if (t != null) return t;
        } catch (Exception e) { }
        return "";
    }

    private String uploadVoice(String path) {
        try {
            File f = new File(path);
            FileInputStream fin = new FileInputStream(f);
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE + "api/upload?filename=" + URLEncoder.encode(
                            "voice_" + System.currentTimeMillis() + ".m4a", "UTF-8") + tkParam()
            ).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode((int) f.length());
            OutputStream out = conn.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
            fin.close();
            out.close();
            InputStream rs = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] rb = new byte[8192];
            int rn;
            while ((rn = rs.read(rb)) > 0) bos.write(rb, 0, rn);
            rs.close();
            String p = jsonStr(new String(bos.toByteArray(), "UTF-8"), "path");
            Log.e("AIAPK", "upload voice ok " + p);
            return p == null ? "" : p;
        } catch (Exception e) {
            Log.e("AIAPK", "upload voice fail", e);
            return "";
        }
    }

    private void notifyVoice(final String text, final String path) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                wv.evaluateJavascript("window.onNativeVoice&&onNativeVoice(" +
                        (text == null ? "''" : "'" + jsq(text) + "'") + "," +
                        (path == null ? "''" : "'" + jsq(path) + "'") + ")", null);
            }
        });
    }

    private String tkParam() {
        try {
            String t = Store.getToken(this);
            if (t == null || t.length() == 0) return "";
            return "&tk=" + URLEncoder.encode(t, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private File fetchToCache(String path) {
        try {
            String name = new File(path).getName();
            if (name.length() == 0) name = "file";
            final File dir = AiFiles.dir(this);
            final File dst = new File(dir, md5hex(path) + "_" + name);
            if (!dst.isFile() || dst.length() == 0) {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        BASE + "api/audio?path=" + URLEncoder.encode(path, "UTF-8") + tkParam()).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(180000);
                if (c.getResponseCode() != 200) {
                    toast("文件读取失败");
                    return null;
                }
                if (c.getContentLength() > 300L * 1024 * 1024) {
                    toast("文件太大，暂不支持");
                    return null;
                }
                File tmp = new File(dir, dst.getName() + ".part");
                InputStream in = c.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                if (!tmp.renameTo(dst)) {
                    tmp.delete();
                    toast("文件缓存失败");
                    return null;
                }
            }
            File cur = new File(dir, name);
            if (!cur.getAbsolutePath().equals(dst.getAbsolutePath())) {
                java.io.FileInputStream in = new java.io.FileInputStream(dst);
                java.io.FileOutputStream out = new java.io.FileOutputStream(cur);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
            }
            trimCache(dir, cur);
            return cur;
        } catch (Exception e) {
            Log.e("AIAPK", "fetchToCache fail", e);
            toast("文件获取失败：" + e.getClass().getSimpleName());
            return null;
        }
    }

    private void fetchAndOpen(String path) {
        String mime0 = guessMime(new File(path).getName());
        if (mime0 != null && mime0.startsWith("text/html")) {
            final String enc = Uri.encode(path);
            runOnUiThread(new Runnable() {
                @Override
                public void run() { goHdr(BASE + "api/audio?path=" + enc); }
            });
            return;
        }
        File dst = fetchToCache(path);
        if (dst == null) return;
        String name = new File(path).getName();
        String mime = guessMime(name);
        final Uri uri = Uri.parse("content://" + AiFiles.AUTHORITY + "/" + Uri.encode(dst.getName()));
        final String fm = mime;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, fm);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    toast("没有能打开该文件的应用");
                }
            }
        });
    }

    private void fetchAndShare(String path) {
        File dst = fetchToCache(path);
        if (dst == null) return;
        String name = new File(path).getName();
        String mime = guessMime(name);
        final Uri uri = Uri.parse("content://" + AiFiles.AUTHORITY + "/" + Uri.encode(dst.getName()));
        final String fm = mime;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType(fm);
                s.putExtra(Intent.EXTRA_STREAM, uri);
                s.putExtra(Intent.EXTRA_TITLE, name);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(s, "分享"));
                } catch (Exception e) {
                    toast("没有可分享的应用");
                }
            }
        });
    }

    private static void trimCache(File dir, File keep) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= 25) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (int i = 0; i < fs.length - 25; i++) {
                if (fs[i].equals(keep)) continue;
                fs[i].delete();
            }
        } catch (Exception e) { }
    }

    private static String md5hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "f" + Integer.toHexString(s.hashCode());
        }
    }

    private static String guessMime(String name) {
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            String t = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(i + 1).toLowerCase());
            if (t != null) return t;
            String e = name.substring(i + 1).toLowerCase();
            if (e.equals("html") || e.equals("htm")) return "text/html";
            if (e.equals("webp")) return "image/webp";
            if (e.equals("svg")) return "image/svg+xml";
            if (e.equals("csv")) return "text/csv";
            if (e.equals("md")) return "text/plain";
            if (e.equals("js")) return "application/javascript";
            if (e.equals("json")) return "application/json";
        }
        return "application/octet-stream";
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private boolean isPrivatePath(String path) {
        try {
            String p = new File(path).getCanonicalPath();
            String files = getFilesDir().getCanonicalPath() + File.separator;
            String cache = getCacheDir().getCanonicalPath() + File.separator;
            return p.startsWith(files) || p.startsWith(cache);
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(final String t) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String jsq(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && wv != null) {
            final android.os.Handler h = new android.os.Handler(getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.evaluateJavascript("(window.appBack&&appBack())===true", new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String v) {
                                Log.i("AIAPK", "back: js=" + v + " url=" + wv.getUrl());
                                if ("true".equals(v)) return;
                                String u = wv.getUrl();
                                if (u != null && u.startsWith(BASE) && !BASE.equals(u)) { go(BASE); return; }
                                moveTaskToBack(true);
                            }
                        });
                    } catch (Exception e) {
                        moveTaskToBack(true);
                    }
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        try {
            if (spkOn && am != null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    try { am.clearCommunicationDevice(); } catch (Exception e) { }
                }
                am.setSpeakerphoneOn(false);
                if (prevMode >= 0) am.setMode(prevMode);
                if (prevVol >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVol, 0);
            }
        } catch (Exception e) { }
        try { ResponseProxy.stop(); } catch (Exception e) { }
        if (wv != null) wv.destroy();
        super.onDestroy();
    }
}
