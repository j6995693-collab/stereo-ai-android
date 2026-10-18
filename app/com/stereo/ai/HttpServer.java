package com.stereo.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class HttpServer {
    private static final String TAG = "StereoHttp";
    private static final int MAX_BODY = 150 * 1024 * 1024;
    public interface Emitter {
        boolean emit(String event, JSONObject data);
    }

    private final Context ctx;
    private final CodexClient codex;
    private ServerSocket server;
    private volatile boolean running = false;
    private final Map<String, Emitter> emitters = new ConcurrentHashMap<String, Emitter>();
    private final Map<String, Emitter> turnEmitters = new ConcurrentHashMap<String, Emitter>();
    private final Map<String, LiveState> live = new ConcurrentHashMap<String, LiveState>();
    private final Map<String, Boolean> activeTurns = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, String[]> convModel = new ConcurrentHashMap<String, String[]>();
    private final Object forkLock = new Object();
    private volatile Map<String, String> forkChildOf = java.util.Collections.emptyMap();
    private volatile Map<String, String> forkTipOf = java.util.Collections.emptyMap();
    private final Map<String, Long> forkAt = new ConcurrentHashMap<String, Long>();
    private volatile long forkLearnAt = 0L;
    private final Map<String, java.util.Set<String>> turnSentFiles =
            new ConcurrentHashMap<String, java.util.Set<String>>();
    private volatile String lastPushPath = "";
    private volatile long lastPushAt = 0;
    private static final long DUP_PUSH_MS = 3000;
    private final Map<String, Map<String, Integer>> turnCallCounts = new ConcurrentHashMap<String, Map<String, Integer>>();
    private final Map<String, String> turnLastCmd = new ConcurrentHashMap<String, String>();
    private final Map<String, String> turnLastMsg = new ConcurrentHashMap<String, String>();
    private final Map<String, Long> turnLastSeen = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> turnStartedAt = new ConcurrentHashMap<String, Long>();
    private static final int LOOP_GUARD_LIMIT = 16;
    private static final int LOOP_GUARD_TOOL_LIMIT = 16;
    private static final long TURN_IDLE_MS = 45 * 60 * 1000L;
    private static final long TURN_MAX_MS = 180 * 60 * 1000L;
    public static final String DONE_MARK = "【任务完成】";
    public static final int AUTO_CONTINUE_MAX = 20;
    public static final String AUTO_CONTINUE_PROMPT =
            "（系统自动续跑：你上一轮还没做完就结束了回合。立刻继续执行，"
            + "不要汇报进度、不要停下等待。全部完成并交付后，在最后单独一行写 "
            + DONE_MARK + "。）";
    private static final String ATTACH_MARK = "[用户附带了";
    public static final int AUTO_CONTINUE_STALL_MAX = 3;
    private static final String[] TOOL_HINTS = {
            "read_image", "read_pdf", "send_file", "todo_write", "web_search",
            "web_fetch", "agent_spawn", "agent_wait", "read_file", "write_file",
            "list_dir", "search_files", "run_shell", "exec_command"
    };

    public static boolean promisesTool(String m) {
        if (m == null || m.length() == 0) return false;
        for (int i = 0; i < TOOL_HINTS.length; i++) {
            if (m.indexOf(TOOL_HINTS[i]) >= 0) return true;
        }
        return false;
    }
    private final Map<String, PermEntry> perms = new ConcurrentHashMap<String, PermEntry>();
    private final Object permLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong permSeq =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.List<JSONObject> fileMsgs =
            new java.util.concurrent.CopyOnWriteArrayList<JSONObject>();
    private final Map<String, String> callTids = new ConcurrentHashMap<String, String>();
    private final Map<String, String> callSids = new ConcurrentHashMap<String, String>();
    private long turnStartT = 0;

    private static class LiveState {
        String stage = "";
        String tool = "";
        StringBuilder text = new StringBuilder();
        String msgId = "";
        boolean msgStreamed = false;
    }

    private static class PermEntry {
        String id;
        String tool;
        String args;
        long ts;
        boolean approved;
        boolean answered;
    }

    public HttpServer(Context c, CodexClient cc) {
        ctx = c;
        codex = cc;
        codex.setListener(new CodexClient.Listener() {
            public void onNotification(String method, JSONObject params) {
                try {
                    String tid = os(params, "threadId", "");
                    if (tid.length() > 0) SubAgent.onEvent(tid, method, params);
                    if (tid.length() > 0) StereoService.onCodexEvent(tid, method, params);
                    if (tid.length() > 0) turnLastSeen.put(tid, Long.valueOf(System.currentTimeMillis()));
                    if (tid.length() > 0 && (method.startsWith("item/") || method.startsWith("turn/"))) {
                        try {
                            JSONObject item = params == null ? null : params.optJSONObject("item");
                            String it = item == null ? "" : item.optString("type", "");
                            String d = "";
                            if (item != null) {
                                if (it.equals("commandExecution")) d = os(item, "command", "");
                                else if (it.equals("agentMessage")) d = os(item, "text", "");
                                else if (it.equals("mcpToolCall")) d = os(item, "tool", "")
                                        + " " + String.valueOf(item.opt("arguments"));
                                else if (it.equals("reasoning")) d = os(item, "text", "");
                                else if (it.equals("fileChange")) d = os(item, "title", "");
                            }
                            d = d.replace('\n', ' ');
                            if (d.length() > 160) d = d.substring(0, 160);
                            Log.i(TAG, "ev " + method + " type=" + it + " " + d);
                        } catch (Exception ig) { }
                    }
                    Emitter em = tid.length() > 0 ? turnEmitters.get(tid) : null;
                    if (em != null) onCodexEvent(em, tid, method, params);
                } catch (Exception e) {
                    Log.e(TAG, "notif", e);
                }
            }

            public void onDead() {
                try {
                    JSONObject o = new JSONObject();
                    o.put("msg", "引擎进程退出，正在自动恢复，请稍后重发");
                    for (Emitter em : turnEmitters.values()) em.emit("error", o);
                } catch (Exception ig) { }
                restartCodex();
            }
        });
    }

    public synchronized void start() throws Exception {
        if (running) return;
        running = true;
        server = new ServerSocket(8765, 32, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
        Thread wd = new Thread(new Runnable() {
            public void run() { watchLoop(); }
        }, "turn-watchdog");
        wd.setDaemon(true);
        wd.start();
        Log.i(TAG, "http up on 8765");
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception e) { }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(s); }
                }, "http-conn");
                h.setDaemon(true);
                h.start();
            } catch (Exception e) {
                if (running) Log.e(TAG, "accept", e);
            }
        }
    }

    private static class Req {
        String method;
        String path;
        String query = "";
        Map<String, String> headers = new HashMap<String, String>();
        byte[] body = new byte[0];
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            Req req = new Req();
            String reqLine = readLine(in);
            if (reqLine == null || reqLine.length() == 0) {
                s.close();
                return;
            }
            String[] parts = reqLine.split(" ");
            req.method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";
            int qi = target.indexOf('?');
            if (qi >= 0) {
                req.path = URLDecoder.decode(target.substring(0, qi), "UTF-8");
                req.query = target.substring(qi + 1);
            } else {
                req.path = URLDecoder.decode(target, "UTF-8");
            }
            String line;
            int contentLen = 0;
            while ((line = readLine(in)) != null && line.length() > 0) {
                String low = line.toLowerCase();
                int ci = low.indexOf(':');
                if (ci > 0) req.headers.put(low.substring(0, ci).trim(), line.substring(ci + 1).trim());
                if (low.startsWith("content-length:")) {
                    try {
                        contentLen = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ig) { }
                }
            }
            if (contentLen > MAX_BODY) {
                sendBytes(s, 413, "application/json; charset=utf-8",
                        "{\"error\":\"payload too large\"}".getBytes("UTF-8"), null);
                s.close();
                return;
            }
            if (contentLen > 0) {
                byte[] b = new byte[contentLen];
                int off = 0;
                while (off < contentLen) {
                    int n = in.read(b, off, contentLen - off);
                    if (n < 0) break;
                    off += n;
                }
                req.body = b;
            }
            route(s, req);
        } catch (Throwable e) {
            try { s.close(); } catch (Throwable ig) { }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) >= 0) {
            if (prev == '\r' && c == '\n') break;
            if (c != '\n' && c != '\r') bos.write(c);
            prev = c;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static Map<String, String> queryParams(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i >= 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                        URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                else m.put(URLDecoder.decode(kv, "UTF-8"), "");
            } catch (Exception ig) { }
        }
        return m;
    }

    private void sendBytes(Socket s, int code, String mime, byte[] body, Map<String, String> extra) {
        try {
            OutputStream os = s.getOutputStream();
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(" ").append(code == 200 ? "OK" : "ERR").append("\r\n");
            h.append("Content-Type: ").append(mime).append("\r\n");
            h.append("Content-Length: ").append(body.length).append("\r\n");
            h.append("Access-Control-Allow-Origin: http://127.0.0.1:8765\r\n");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            h.append("Connection: close\r\n\r\n");
            os.write(h.toString().getBytes("UTF-8"));
            os.write(body);
            os.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ig) { }
        }
    }

    private void sendJson(Socket s, JSONObject o) {
        sendBytes(s, 200, "application/json; charset=utf-8", o.toString().getBytes(), null);
    }

    private void sseBegin(Socket s) throws Exception {
        OutputStream os = s.getOutputStream();
        os.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes("UTF-8"));
        os.flush();
    }

    private boolean sseSend(Socket s, String event, JSONObject data) {
        try {
            OutputStream os = s.getOutputStream();
            String payload = "event: " + event + "\ndata: " + data.toString() + "\n\n";
            String frame = Integer.toHexString(payload.getBytes("UTF-8").length)
                    + "\r\n" + payload + "\r\n";
            os.write(frame.getBytes("UTF-8"));
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sseEnd(Socket s) {
        try {
            OutputStream os = s.getOutputStream();
            os.write("0\r\n\r\n".getBytes("UTF-8"));
            os.flush();
        } catch (Exception e) { }
    }

    private boolean authed(Req req) {
        try {
            String tok = req.headers.get("x-ai-token");
            if (tok == null || tok.length() == 0) {
                Map<String, String> q = queryParams(req.query);
                tok = q.get("tk");
            }
            if (tok == null || tok.length() == 0) return false;
            String real = Store.getToken(ctx);
            return real != null && real.equals(tok);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean needsAuth(String p, String method) {
        if (!p.startsWith("/api/")) return false;
        if (p.equals("/api/healthz") || p.equals("/api/pagever")
                || p.equals("/api/mapcheck")) return false;
        return true;
    }

    private void route(Socket s, Req req) throws Exception {
        String p = req.path;
        if (needsAuth(p, req.method) && !authed(req)) {
            sendBytes(s, 403, "application/json; charset=utf-8",
                    "{\"error\":\"forbidden\"}".getBytes(), null);
            return;
        }
        if (p.equals("/") || p.equals("/index.html")) {
            byte[] b = asset("index.html");
            if (b == null) {
                sendBytes(s, 404, "text/plain", "no ui".getBytes(), null);
            } else {
                java.util.HashMap<String, String> h = new java.util.HashMap<String, String>();
                h.put("Cache-Control", "no-store, no-cache, must-revalidate");
                sendBytes(s, 200, "text/html; charset=utf-8", b, h);
            }
            return;
        }
        if (p.equals("/api/healthz") || p.equals("/api/pagever")) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("arch", "codex-native");
            o.put("ver", "3.0.1");
            o.put("codex", codex.isUp());
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/convs")) {
            handleConvs(s);
            return;
        }
        if (p.equals("/api/chat") && req.method.equals("POST")) {
            handleChat(s, new String(req.body, "UTF-8"));
            return;
        }
        if (p.equals("/api/mapcheck") && req.method.equals("POST")) {
            handleMapcheck(s, new String(req.body, "UTF-8"));
            return;
        }
        if (p.startsWith("/api/conv/") && !p.equals("/api/conv/del")
                && !p.equals("/api/conv/delall")) {
            String id = p.substring("/api/conv/".length());
            handleConvRead(s, id);
            return;
        }
        if (p.equals("/api/conv/del") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String id = b.optString("id", "");
            ensureForks();
            if (rootId(id).equals(id)) {
                java.util.List<String> sup = superseded(id);
                for (int i = 0; i < sup.size(); i++) {
                    String ch = sup.get(i);
                    try { codex.archiveThread(ch); } catch (Throwable ig) { }
                    try { deleteRollout(ch); } catch (Throwable ig) { }
                    callTids.remove(ch);
                    live.remove(ch);
                    activeTurns.remove(ch);
                    turnEmitters.remove(ch);
                }
                synchronized (forkLock) {
                    Map<String, String> c = new HashMap<String, String>(forkChildOf);
                    Map<String, String> t = new HashMap<String, String>(forkTipOf);
                    for (int i = 0; i < sup.size(); i++) {
                        String ch = sup.get(i);
                        c.remove(ch);
                        t.remove(ch);
                        forkAt.remove(ch);
                    }
                    t.remove(id);
                    forkAt.remove(id);
                    forkChildOf = c;
                    forkTipOf = t;
                }
            }
            JSONObject r = codex.archiveThread(id);
            boolean fok = deleteRollout(id);
            callTids.remove(id);
            live.remove(id);
            activeTurns.remove(id);
            turnEmitters.remove(id);
            try {
                JSONObject stt = Store.loadSettings(ctx);
                JSONArray ca = stt.optJSONArray("call_tids");
                if (ca != null) {
                    JSONArray na = new JSONArray();
                    for (int i = 0; i < ca.length(); i++) {
                        if (!ca.optString(i).equals(id)) na.put(ca.optString(i));
                    }
                    stt.put("call_tids", na);
                    Store.saveSettings(ctx, stt);
                }
            } catch (Exception ig) { }
            JSONObject o = new JSONObject().put("ok", fok || !r.has("error"));
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/conv/delall") && req.method.equals("POST")) {
            File base = new File(Store.codexHome(ctx), "sessions");
            int n = 0;
            java.util.List<File> fs = new java.util.ArrayList<File>();
            if (base.isDirectory()) collectRollouts(base, fs);
            for (File f : fs) {
                if (f.delete()) n++;
            }
            callTids.clear();
            try {
                JSONObject stt = Store.loadSettings(ctx);
                stt.put("call_tids", new JSONArray());
                Store.saveSettings(ctx, stt);
            } catch (Exception ig) { }
            JSONObject o = new JSONObject().put("ok", true).put("deleted", n);
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/files")) {
            Map<String, String> q = queryParams(req.query);
            long since = 0;
            try {
                since = Long.parseLong(q.get("since") == null ? "0" : q.get("since"));
            } catch (Exception ig) { }
            String fconv = q.get("conv");
            sendJson(s, new JSONObject().put("files", filesSince(since, fconv)));
            return;
        }
        if (p.equals("/api/perm-pending")) {            JSONArray arr = new JSONArray();
            synchronized (permLock) {
                for (PermEntry e : perms.values()) {
                    if (!e.answered) {
                        JSONObject pe = new JSONObject();
                        pe.put("id", e.id);
                        pe.put("tool", e.tool);
                        pe.put("args", e.args);
                        pe.put("ts", e.ts);
                        arr.put(pe);
                    }
                }
            }
            sendJson(s, new JSONObject().put("pending", arr));
            return;
        }
        if (p.equals("/api/perm-answer") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String id = b.optString("id", "");
            boolean approve = b.optBoolean("approve", false);
            boolean found = false;
            synchronized (permLock) {
                PermEntry e = perms.get(id);
                if (e != null && !e.answered) {
                    e.answered = true;
                    e.approved = approve;
                    found = true;
                }
            }
            if (found) {
                synchronized (permLock) {
                    permLock.notifyAll();
                }
            }
            sendJson(s, new JSONObject().put("ok", found));
            return;
        }
        if (p.equals("/api/models")) {
            JSONObject o = new JSONObject();
            o.put("providers", Store.providers(ctx));
            JSONObject cur = new JSONObject();
            cur.put("provider", Store.getSetting(ctx, "provider", "dashscope"));
            cur.put("model", Store.getSetting(ctx, "model", "qwen3.8-max"));
            cur.put("effort", Store.getSetting(ctx, "effort", "medium"));
            cur.put("plan", "1".equals(Store.getSetting(ctx, "plan_mode", "0")));
            o.put("current", cur);
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/model") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String model = b.optString("model", "");
            String provider = b.optString("provider", "");
            Log.i(TAG, "api model POST model=" + model + " provider=" + provider);
            if (model.length() > 0) {
                Store.putSetting(ctx, "model", model);
                Store.putSetting(ctx, "model_by", "user");
            }
            if (provider.length() > 0) Store.putSetting(ctx, "provider", provider);
            Store.writeConfig(ctx);
            callTids.clear();
            callSids.clear();
            try {
                JSONObject stt = Store.loadSettings(ctx);
                stt.put("call_tids", new JSONArray());
                Store.saveSettings(ctx, stt);
            } catch (Exception ig) { }
            sendJson(s, new JSONObject().put("ok", true));
            return;
        }
        if (p.equals("/api/effort")) {
            if (req.method.equals("POST")) {
                JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
                Store.putSetting(ctx, "effort", b.optString("level", "medium"));
                Store.writeConfig(ctx);
            }
            sendJson(s, new JSONObject().put("level", Store.getSetting(ctx, "effort", "medium")));
            return;
        }
        if (p.equals("/api/plan")) {
            if (req.method.equals("POST")) {
                JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
                Store.putSetting(ctx, "plan_mode", b.optBoolean("on", false) ? "1" : "0");
            }
            sendJson(s, new JSONObject().put("on",
                    "1".equals(Store.getSetting(ctx, "plan_mode", "0"))));
            return;
        }
        if (p.equals("/api/settings/obsidian")) {
            if (req.method.equals("POST")) {
                JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
                Store.putSetting(ctx, "obsidian_root", b.optString("root", ""));
            }
            JSONObject o = new JSONObject();
            String root = Store.getSetting(ctx, "obsidian_root", "");
            if (root == null) root = "";
            o.put("root", root);
            if (root.trim().length() == 0 || !new File(root.trim()).isDirectory()) {
                String det = Store.detectObsidianRoot(ctx);
                o.put("detected", det == null ? "" : det);
            }
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/key") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String provider = b.optString("provider", "");
            String key = b.optString("key", "");
            if (provider.length() == 0) {
                sendJson(s, new JSONObject().put("ok", false));
                return;
            }
            Store.saveKey(ctx, provider, key);
            restartCodex();
            sendJson(s, new JSONObject().put("ok", true));
            return;
        }
        if (p.equals("/api/keys")) {
            JSONObject keys = Store.loadKeys(ctx);
            JSONObject masked = new JSONObject();
            Iterator<String> it = keys.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = keys.optString(k);
                masked.put(k, "****");
            }
            sendJson(s, new JSONObject().put("keys", masked));
            return;
        }
        if (p.equals("/api/models/refresh") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String provider = b.optString("provider", "");
            String listUrl = null;
            JSONArray ps = Store.providers(ctx);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject pp = ps.optJSONObject(i);
                if (pp != null && provider.equals(pp.optString("id"))) {
                    listUrl = pp.optString("list_url", "");
                    break;
                }
            }
            if (provider.length() == 0 || listUrl == null || listUrl.length() == 0) {
                sendJson(s, new JSONObject().put("ok", false).put("error", "unknown provider"));
                return;
            }
            try {
                String key = Store.loadKeys(ctx).optString(provider, "");
                JSONArray ids = fetchModelIds(listUrl, key);
                if (ids == null) {
                    sendJson(s, new JSONObject().put("ok", false)
                            .put("error", "拉取失败，请检查接口地址与密钥"));
                    return;
                }
                seedModels(ctx, provider, ids);
                JSONObject o = new JSONObject().put("ok", true).put("count", ids.length());
                JSONArray ps2 = Store.providers(ctx);
                for (int i = 0; i < ps2.length(); i++) {
                    JSONObject pp = ps2.optJSONObject(i);
                    if (pp != null && provider.equals(pp.optString("id"))) {
                        o.put("models", pp.optJSONArray("models"));
                        break;
                    }
                }
                sendJson(s, o);
            } catch (Exception e) {
                sendJson(s, new JSONObject().put("ok", false).put("error", String.valueOf(e)));
            }
            return;
        }
        if (p.equals("/api/provider") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            boolean del = b.optBoolean("del", false);
            String id = b.optString("id", "").trim();
            JSONObject st = Store.loadSettings(ctx);
            JSONObject cp = st.optJSONObject("custom_providers");
            if (cp == null) cp = new JSONObject();
            if (del) {
                cp.remove(id);
                st.put("custom_providers", cp);
                JSONObject cm = st.optJSONObject("custom_models");
                if (cm != null) {
                    cm.remove(id);
                    st.put("custom_models", cm);
                }
                JSONObject rm = st.optJSONObject("remote_models");
                if (rm != null) {
                    rm.remove(id);
                    st.put("remote_models", rm);
                }
                if (id.equals(Store.getSetting(ctx, "provider", ""))) {
                    Store.putSetting(ctx, "provider", "dashscope");
                    Store.putSetting(ctx, "model", "qwen3.8-max");
                }
                Store.saveSettings(ctx, st);
                Store.writeConfig(ctx);
                restartCodex();
                sendJson(s, new JSONObject().put("ok", true));
                return;
            }
            String name = b.optString("name", "").trim();
            String base = b.optString("base", "").trim();
            String proto = b.optString("proto", "openai").trim();
            String key = b.optString("key", "");
            if (!id.matches("[A-Za-z0-9_\\-]{1,32}")) {
                sendJson(s, new JSONObject().put("ok", false)
                        .put("error", "供应商 ID 只能用字母/数字/下划线/短横线，且不超过 32 位"));
                return;
            }
            if ("dashscope".equals(id) || "zhipu".equals(id)
                    || "deepseek".equals(id) || "kimi".equals(id)) {
                sendJson(s, new JSONObject().put("ok", false).put("error", "该 ID 已被内置供应商占用"));
                return;
            }
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (!base.startsWith("https://")
                    && !base.startsWith("http://127.0.0.1")
                    && !base.startsWith("http://localhost")) {
                sendJson(s, new JSONObject().put("ok", false)
                        .put("error", "接口地址必须以 https:// 开头（本机地址可用 http://127.0.0.1）"));
                return;
            }
            if (!"anthropic".equals(proto)) proto = "openai";
            JSONObject def = new JSONObject();
            def.put("name", name.length() == 0 ? id : name);
            def.put("base", base);
            def.put("proto", proto);
            cp.put(id, def);
            st.put("custom_providers", cp);
            Store.saveSettings(ctx, st);
            if (key.trim().length() > 0) {
                try {
                    Store.saveKey(ctx, id, key.trim());
                } catch (Exception e) {
                    Log.e(TAG, "saveKey custom", e);
                }
            }
            boolean enabled = false;
            int got = 0;
            if (!"anthropic".equals(proto)) {
                JSONArray ids = fetchModelIds(base + "/models", key.trim());
                if (ids != null && ids.length() > 0) {
                    seedModels(ctx, id, ids);
                    got = ids.length();
                    Store.putSetting(ctx, "provider", id);
                    Store.putSetting(ctx, "model", ids.optString(0, ""));
                    Store.putSetting(ctx, "model_by", "user");
                    enabled = true;
                }
            }
            Store.writeConfig(ctx);
            restartCodex();
            sendJson(s, new JSONObject().put("ok", true)
                    .put("models", got).put("enabled", enabled));
            return;
        }
        if (p.equals("/api/custom-model") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String provider = b.optString("provider", "");
            String model = b.optString("model", "").trim();
            boolean del = b.optBoolean("del", false);
            if (provider.length() == 0) {
                sendJson(s, new JSONObject().put("ok", false));
                return;
            }
            JSONObject st = Store.loadSettings(ctx);
            JSONObject custom = st.optJSONObject("custom_models");
            if (custom == null) custom = new JSONObject();
            JSONArray list = custom.optJSONArray(provider);
            if (list == null) list = new JSONArray();
            if (del) {
                JSONArray nl = new JSONArray();
                for (int i = 0; i < list.length(); i++) {
                    if (!model.equals(list.optString(i))) nl.put(list.optString(i));
                }
                custom.put(provider, nl);
            } else if (model.length() > 0) {
                boolean ex = false;
                for (int i = 0; i < list.length(); i++) {
                    if (model.equals(list.optString(i))) ex = true;
                }
                if (!ex) list.put(model);
                custom.put(provider, list);
            }
            st.put("custom_models", custom);
            Store.saveSettings(ctx, st);
            JSONArray models = null;
            JSONArray ps = Store.providers(ctx);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject pp = ps.optJSONObject(i);
                if (pp != null && provider.equals(pp.optString("id"))) {
                    models = pp.optJSONArray("models");
                    break;
                }
            }
            sendJson(s, new JSONObject().put("ok", true).put("models", models));
            return;
        }
        if (p.equals("/api/mcp") && req.method.equals("GET")) {
            JSONObject st = Store.loadSettings(ctx);
            JSONArray arr = st.optJSONArray("custom_mcp");
            JSONObject out = new JSONObject();
            out.put("servers", arr == null ? new JSONArray() : arr);
            out.put("hook", Store.getSetting(ctx, Hooks.SET_CMD, ""));
            out.put("hookMatch", Store.getSetting(ctx, Hooks.SET_MATCH, ""));
            out.put("hookEnabled", Hooks.enabled(ctx));
            out.put("tools", McpServer.toolNames());
            sendJson(s, out);
            return;
        }
        if (p.equals("/api/mcp") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String id = b.optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9_\\-]{1,32}") || "stereo".equals(id)) {
                sendJson(s, new JSONObject().put("ok", false)
                        .put("error", "ID 只能用字母/数字/下划线/短横线，不超过 32 位，且不能叫 stereo"));
                return;
            }
            JSONObject st = Store.loadSettings(ctx);
            JSONArray arr = st.optJSONArray("custom_mcp");
            if (arr == null) arr = new JSONArray();
            JSONArray nl = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) nl.put(o);
            }
            if (!b.optBoolean("del", false)) {
                String url = b.optString("url", "").trim();
                String command = b.optString("command", "").trim();
                if (url.length() > 0) {
                    if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1")
                            && !url.startsWith("http://localhost")) {
                        sendJson(s, new JSONObject().put("ok", false)
                                .put("error", "远程 MCP 地址必须 https://（本机可用 http://127.0.0.1）"));
                        return;
                    }
                } else if (command.length() > 0) {
                    if (!command.matches("[A-Za-z0-9_./\\-]{1,200}")) {
                        sendJson(s, new JSONObject().put("ok", false)
                                .put("error", "本地命令只能是可执行文件路径，不含空格和特殊字符，参数请填在参数栏"));
                        return;
                    }
                } else {
                    sendJson(s, new JSONObject().put("ok", false)
                            .put("error", "地址和本地命令至少填一个"));
                    return;
                }
                JSONObject o = new JSONObject();
                o.put("id", id);
                if (url.length() > 0) o.put("url", url);
                else {
                    o.put("command", command);
                    JSONArray ar = new JSONArray();
                    String argsRaw = b.optString("args", "").trim();
                    if (argsRaw.length() > 0) {
                        for (String a : argsRaw.split("\\s+")) {
                            if (a.length() > 0) ar.put(a);
                        }
                    }
                    o.put("args", ar);
                }
                JSONObject env = new JSONObject();
                String envRaw = b.optString("env", "").trim();
                if (envRaw.length() > 0) {
                    for (String line : envRaw.split("\n")) {
                        String t = line.trim();
                        int eq = t.indexOf('=');
                        if (eq <= 0) continue;
                        String k = t.substring(0, eq).trim();
                        String v = t.substring(eq + 1).trim();
                        if (k.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") && v.length() > 0) {
                            env.put(k, v);
                        }
                    }
                }
                if (env.length() > 0) o.put("env", env);
                nl.put(o);
            }
            st.put("custom_mcp", nl);
            Store.saveSettings(ctx, st);
            Store.writeConfig(ctx);
            restartCodex();
            sendJson(s, new JSONObject().put("ok", true).put("count", nl.length())
                    .put("restarted", true));
            return;
        }
        if (p.equals("/api/hook") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String cmd = b.optString("cmd", "").trim();
            String match = b.optString("match", "").trim();
            if (cmd.length() > 2000 || match.length() > 200) {
                sendJson(s, new JSONObject().put("ok", false).put("error", "内容过长"));
                return;
            }
            if (match.length() > 0) {
                try {
                    "test".matches(match);
                } catch (Exception badRe) {
                    sendJson(s, new JSONObject().put("ok", false)
                            .put("error", "匹配规则不是合法正则：" + badRe.getMessage()));
                    return;
                }
            }
            Store.putSetting(ctx, Hooks.SET_CMD, cmd);
            Store.putSetting(ctx, Hooks.SET_MATCH, match);
            sendJson(s, new JSONObject().put("ok", true).put("enabled", cmd.length() > 0));
            return;
        }
        if (p.equals("/api/clog") && req.method.equals("POST")) {
            String msg = new String(req.body, "UTF-8");
            Log.i("StereoJs", msg.length() > 500 ? msg.substring(0, 500) : msg);
            sendJson(s, new JSONObject().put("ok", true));
            return;
        }
        if (p.equals("/api/upload") && req.method.equals("POST")) {
            Map<String, String> q = queryParams(req.query);
            String name = q.get("filename");
            if (name == null || name.length() == 0) name = "file.bin";
            if (req.body == null || req.body.length == 0) {
                sendJson(s, new JSONObject().put("err", "empty upload"));
                return;
            }
            String safe = name.replaceAll("[/\\\\]", "_");
            String stored = System.currentTimeMillis() + "_" + safe;
            Log.i(TAG, "upload name=" + safe + " size=" + req.body.length);
            File dst = new File(Store.uploadsDir(ctx), stored);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                out.write(req.body);
            } finally {
                try { out.close(); } catch (Exception ig) { }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("path", "uploads/" + stored);
            o.put("name", safe);
            o.put("size", req.body.length);
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/asr") && req.method.equals("POST")) {
            String text = dashAsr(req.body);
            JSONObject o = new JSONObject();
            o.put("text", text);
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/call/turn") && req.method.equals("POST")) {
            handleCallTurn(s, new String(req.body, "UTF-8"));
            return;
        }
        if (p.equals("/api/call/abort") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String sid = b.optString("sid", "");
            String tid = sid.length() > 0 ? callSids.remove(sid) : null;
            if (tid != null) {
                try { codex.interrupt(tid); } catch (Exception ig) { }
                activeTurns.remove(tid);
                turnEmitters.remove(tid);
            }
            sendJson(s, new JSONObject().put("ok", true));
            return;
        }
        if (p.equals("/api/turn/abort") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String cid = b.optString("conv_id", "");
            String tid = "";
            if (cid.length() > 0) {
                String rt = tipId(rootId(cid));
                if (rt != null && activeTurns.containsKey(rt)) tid = rt;
            }
            if (tid.length() == 0) {
                for (String k : activeTurns.keySet()) { tid = k; break; }
            }
            JSONObject o = new JSONObject();
            if (tid.length() == 0) {
                o.put("ok", false);
                o.put("msg", "当前没有正在执行的任务");
                sendJson(s, o);
                return;
            }
            final String ftid = tid;
            new Thread(new Runnable() {
                public void run() {
                    try { codex.interrupt(ftid); } catch (Exception ig) { }
                }
            }, "turn-abort").start();
            Emitter em = turnEmitters.get(tid);
            if (em != null) {
                try { em.emit("error", new JSONObject().put("msg", "已请求停止本轮任务。")); } catch (Exception ig) { }
            }
            releaseTurn(tid);
            o.put("ok", true);
            o.put("msg", "已停止本轮任务");
            o.put("conv_id", tid);
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/call/end") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String cid = b.optString("conv_id", "");
            if (cid.length() > 0) callTids.remove(cid);
            sendJson(s, new JSONObject().put("ok", true));
            return;
        }
        if (p.equals("/api/tts") && req.method.equals("POST")) {
            JSONObject b = new JSONObject(new String(req.body, "UTF-8"));
            String path = dashTts(b.optString("text", ""), b.optString("voice", "longqiang_v2"));
            JSONObject o = new JSONObject();
            o.put("ok", path != null);
            o.put("path", path == null ? "" : path);
            sendJson(s, o);
            return;
        }
        if (p.equals("/api/audio")) {
            Map<String, String> q = queryParams(req.query);
            serveFile(s, q.get("path"), q.containsKey("download"),
                    req.headers.get("range"));
            return;
        }
        if (p.equals("/api/legacy-convs")) {
            JSONArray arr = new JSONArray();
            try {
                File dir = new File(ctx.getFilesDir(), "legacy_convs");
                File[] fs = dir.listFiles();
                if (fs != null) {
                    java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                        public int compare(File a, File b) {
                            return Long.compare(b.lastModified(), a.lastModified());
                        }
                    });
                    for (File f : fs) {
                        if (f.getName().endsWith(".live.json")) continue;
                        JSONObject o = new JSONObject();
                        o.put("id", f.getName());
                        o.put("ts", f.lastModified());
                        arr.put(o);
                    }
                }
            } catch (Exception e) { }
            sendJson(s, new JSONObject().put("convs", arr));
            return;
        }
        if (p.startsWith("/api/legacy-conv/")) {
            String id = p.substring("/api/legacy-conv/".length());
            byte[] raw = null;
            try {
                File dir = new File(ctx.getFilesDir(), "legacy_convs");
                File f = new File(dir, id);
                String cp = f.getCanonicalPath();
                String dp = dir.getCanonicalPath();
                if (!cp.startsWith(dp + File.separator)) {
                    sendJson(s, new JSONObject().put("turns", new JSONArray()));
                    return;
                }
                if (f.isFile()) {
                    FileInputStream fin = new FileInputStream(f);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fin.read(buf)) > 0) bos.write(buf, 0, n);
                    fin.close();
                    raw = bos.toByteArray();
                }
            } catch (Exception e) { }
            if (raw == null) {
                sendJson(s, new JSONObject().put("turns", new JSONArray()));
                return;
            }
            JSONArray turns = new JSONArray();
            try {
                String content = new String(raw, "UTF-8").trim();
                if (content.startsWith("[")) {
                    JSONArray arr = new JSONArray(content);
                    for (int i = 0; i < arr.length(); i++) turns.put(arr.get(i));
                } else {
                    JSONObject o = new JSONObject(content);
                    JSONArray t = o.optJSONArray("turns");
                    if (t == null) t = o.optJSONArray("messages");
                    if (t != null) {
                        for (int i = 0; i < t.length(); i++) turns.put(t.get(i));
                    }
                }
            } catch (Exception e) { }
            sendJson(s, new JSONObject().put("turns", turns));
            return;
        }
        sendBytes(s, 404, "application/json", "{\"err\":\"not found\"}".getBytes(), null);
    }

    private byte[] asset(String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                try { in.close(); } catch (Exception ig) { }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void noteFork(String childId, String parentId) {
        if (childId == null || parentId == null) return;
        if (childId.length() == 0 || parentId.length() == 0 || childId.equals(parentId)) return;
        synchronized (forkLock) {
            Map<String, String> c = new HashMap<String, String>(forkChildOf);
            Map<String, String> t = new HashMap<String, String>(forkTipOf);
            c.put(childId, parentId);
            t.put(parentId, childId);
            forkChildOf = c;
            forkTipOf = t;
        }
        forkAt.put(parentId, Long.valueOf(System.currentTimeMillis()));
    }

    private void learnForks(JSONArray threads) {
        long now = System.currentTimeMillis();
        synchronized (forkLock) {
            Map<String, String> child = new HashMap<String, String>();
            Map<String, String> tip = new HashMap<String, String>();
            Map<String, Long> tipTs = new HashMap<String, Long>();
            if (threads != null) {
                for (int i = 0; i < threads.length(); i++) {
                    JSONObject t = threads.optJSONObject(i);
                    if (t == null) continue;
                    String id = os(t, "id", "");
                    String pid = os(t, "forkedFromId", "");
                    if (id.length() == 0 || pid.length() == 0 || id.equals(pid)) continue;
                    child.put(id, pid);
                    long ts = t.optLong("updatedAt", 0);
                    Long prev = tipTs.get(pid);
                    if (prev == null || ts > prev.longValue()) {
                        tip.put(pid, id);
                        tipTs.put(pid, Long.valueOf(ts));
                    }
                }
            }
            for (Map.Entry<String, String> e : forkTipOf.entrySet()) {
                if (tip.containsKey(e.getKey())) continue;
                Long at = forkAt.get(e.getKey());
                if (at == null || now - at.longValue() >= 10 * 60 * 1000L) continue;
                tip.put(e.getKey(), e.getValue());
                if (!child.containsKey(e.getValue())) child.put(e.getValue(), e.getKey());
            }
            forkChildOf = child;
            forkTipOf = tip;
        }
        forkLearnAt = now;
    }

    private void ensureForks() {
        long now = System.currentTimeMillis();
        if (now - forkLearnAt < 30000L) return;
        forkLearnAt = now;
        try {
            JSONObject r = codex.listThreads();
            JSONArray threads = r.optJSONArray("threads");
            if (threads == null) {
                JSONObject data = r.optJSONObject("data");
                if (data != null) threads = data.optJSONArray("threads");
            }
            learnForks(threads);
        } catch (Throwable e) {
            Log.e(TAG, "ensureForks", e);
        }
    }

    private String rootId(String id) {
        if (id == null || id.length() == 0) return id;
        String cur = id;
        for (int i = 0; i < 16; i++) {
            String p = forkChildOf.get(cur);
            if (p == null || p.equals(cur)) break;
            cur = p;
        }
        return cur;
    }

    private String tipId(String id) {
        if (id == null || id.length() == 0) return id;
        String cur = id;
        for (int i = 0; i < 16; i++) {
            String c = forkTipOf.get(cur);
            if (c == null || c.equals(cur)) break;
            cur = c;
        }
        return cur;
    }

    private JSONArray mergeTurns(JSONArray base, JSONArray add) {
        JSONArray out = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < base.length(); i++) {
            JSONObject t = base.optJSONObject(i);
            if (t == null) continue;
            seen.add(os(t, "role", "") + "\u0001" + os(t, "content", ""));
            out.put(t);
        }
        for (int i = 0; i < add.length(); i++) {
            JSONObject t = add.optJSONObject(i);
            if (t == null) continue;
            if (seen.contains(os(t, "role", "") + "\u0001" + os(t, "content", ""))) continue;
            out.put(t);
        }
        return out;
    }

    private void handleConvs(Socket s) throws Exception {
        JSONArray arr = new JSONArray();
        java.util.HashSet<String> callOnly = callOnlyTids();
        JSONObject r = codex.listThreads();
        JSONArray threads = r.optJSONArray("threads");
        if (threads == null) {
            JSONObject data = r.optJSONObject("data");
            if (data != null) threads = data.optJSONArray("threads");
        }
        learnForks(threads);
        if (threads != null) {
            Map<String, Integer> slotOf = new HashMap<String, Integer>();
            java.util.ArrayList<String> names = new java.util.ArrayList<String>();
            for (int i = 0; i < threads.length(); i++) {
                JSONObject t = threads.optJSONObject(i);
                if (t == null) continue;
                String id = os(t, "id", "");
                if (id.length() == 0) continue;
                String root = rootId(id);
                if (callOnly.contains(root) && !forkTipOf.containsKey(root)) continue;
                long ts = t.optLong("updatedAt", 0);
                String nm = rolloutNameOf(id);
                if (nm.length() == 0) nm = os(t, "name", "");
                if (nm.length() == 0) nm = os(t, "preview", "");
                JSONObject o;
                Integer slot = slotOf.get(root);
                if (slot == null) {
                    o = new JSONObject();
                    o.put("id", root);
                    o.put("preview", os(t, "preview", ""));
                    o.put("ts", ts);
                    o.put("live", live.get(id) != null);
                    names.add(nm);
                    slotOf.put(root, Integer.valueOf(arr.length()));
                    arr.put(o);
                } else {
                    o = arr.getJSONObject(slot.intValue());
                    boolean newer = ts > o.optLong("ts", 0);
                    if (newer) {
                        o.put("ts", ts);
                        o.put("preview", os(t, "preview", ""));
                        names.set(slot.intValue(), nm);
                    }
                    if (live.get(id) != null) o.put("live", true);
                }
                java.util.List<String> sup = superseded(root);
                if (sup.size() > 0) {
                    JSONArray sa = new JSONArray();
                    for (int k = 0; k < sup.size(); k++) sa.put(sup.get(k));
                    o.put("alias_from", sa);
                }
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.has("name")) continue;
                String nm = names.get(i);
                if (nm.length() == 0) nm = o.optString("preview", "");
                o.put("name", nm);
            }
        }
        boolean hasPerm = false;
        synchronized (permLock) {
            hasPerm = !perms.isEmpty();
        }
        for (int i = 0; i < arr.length(); i++) {
            arr.getJSONObject(i).put("perm", hasPerm);
        }
        if (arr.length() == 0) {
            JSONArray fb = scanRolloutConvs();
            for (int i = 0; i < fb.length(); i++) arr.put(fb.get(i));
        }
        sendJson(s, new JSONObject().put("convs", arr));
    }

    private java.util.List<String> superseded(String root) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String cur = root;
        for (int i = 0; i < 16; i++) {
            String c = forkTipOf.get(cur);
            if (c == null || c.equals(cur)) break;
            out.add(c);
            cur = c;
        }
        return out;
    }

    private void handleConvRead(Socket s, String idIn) throws Exception {
        ensureForks();
        String id = rootId(idIn);
        String tid = tipId(id);
        JSONObject r = codex.readThread(tid);
        JSONArray turns = new JSONArray();
        JSONObject thread = r.optJSONObject("thread");
        if (thread == null && !r.has("error")) thread = r;
        if (thread != null) {
            JSONArray items = thread.optJSONArray("items");
            if (items == null) items = r.optJSONArray("items");
            if (items != null) {
                String curRole = null;
                StringBuilder curText = new StringBuilder();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String type = it.optString("type", "");
                    String text = os(it, "text", "");
                    if (text.length() == 0) {
                        JSONArray cont = it.optJSONArray("content");
                        if (cont != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int k = 0; k < cont.length(); k++) {
                                JSONObject blk = cont.optJSONObject(k);
                                if (blk != null) sb.append(os(blk, "text", ""));
                            }
                            text = sb.toString();
                        }
                    }
                    if (type.equals("userMessage")) {
                        flushTurn(turns, curRole, curText);
                        curRole = "user";
                        curText.setLength(0);
                        curText.append(text);
                    } else if (type.equals("agentMessage")) {
                        flushTurn(turns, curRole, curText);
                        curRole = "assistant";
                        curText.setLength(0);
                        curText.append(text);
                    }
                }
                flushTurn(turns, curRole, curText);
            }
        }
        if (turns.length() == 0) turns = readRolloutTurns(tid);
        if (!tid.equals(id)) {
            JSONArray rootTurns = readRolloutTurns(id);
            if (turns.length() == 0) turns = rootTurns;
            else if (rootTurns.length() > turns.length()) turns = mergeTurns(rootTurns, turns);
        }
        String tm = thread == null ? "" : thread.optString("model", "");
        String tp = thread == null ? "" : thread.optString("modelProvider", "");
        if (tm.length() == 0) {
            String[] cmv = convModel.get(tid);
            if (cmv == null) cmv = convModel.get(id);
            if (cmv != null) {
                tm = cmv[0];
                tp = cmv[1];
            }
        }
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("turns", turns);
        if (tm.length() > 0) o.put("model", tm);
        if (tp.length() > 0) o.put("provider", tp);
        LiveState ls = live.get(tid);
        if (ls == null) ls = live.get(id);
        if (ls != null) {
            JSONObject lv = new JSONObject();
            lv.put("stage", ls.stage);
            lv.put("tool", ls.tool);
            lv.put("text", ls.text.toString());
            o.put("live", lv);
        }
        sendJson(s, o);
    }

    private static void flushTurn(JSONArray turns, String role, StringBuilder text) {
        if (role == null || text.length() == 0) return;
        try {
            JSONObject t = new JSONObject();
            t.put("role", role);
            t.put("content", text.toString());
            turns.put(t);
        } catch (Exception ig) { }
    }

    private void collectRollouts(File dir, java.util.List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectRollouts(f, out);
            else if (f.getName().endsWith(".jsonl")) out.add(f);
        }
    }

    private File findRollout(String id) {
        File base = new File(Store.codexHome(ctx), "sessions");
        if (!base.isDirectory()) return null;
        java.util.List<File> fs = new java.util.ArrayList<File>();
        collectRollouts(base, fs);
        String suf = "-" + id + ".jsonl";
        for (File f : fs) {
            if (f.getName().endsWith(suf)) return f;
        }
        return null;
    }

    private String readTextFile(File f) throws Exception {
        FileInputStream fin = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = fin.read(buf)) > 0) bos.write(buf, 0, n);
        fin.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private String msgText(JSONObject pl) {
        StringBuilder sb = new StringBuilder();
        JSONArray content = pl.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null) continue;
                String t = os(c, "text", "");
                if (t.length() > 0) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    private boolean deleteRollout(String id) {
        File f = findRollout(id);
        return f != null && f.delete();
    }

    private java.util.HashSet<String> callOnlyTids() {
        java.util.HashSet<String> set = new java.util.HashSet<String>();
        JSONArray a = Store.loadSettings(ctx).optJSONArray("call_tids");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) set.add(a.optString(i));
        }
        return set;
    }

    private void markCallTid(String tid) {
        Store.markCallTid(ctx, tid);
    }

    private String rolloutNameOf(String id) {
        try {
            File f = findRollout(id);
            if (f == null) return "";
            for (String line : readTextFile(f).split("\n")) {
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject m;
                try {
                    m = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                if (!m.optString("type", "").equals("response_item")) continue;
                JSONObject pl = m.optJSONObject("payload");
                if (pl == null || !pl.optString("type", "").equals("message")) continue;
                if (!pl.optString("role", "").equals("user")) continue;
                String text = msgText(pl);
                if (!isRealUserText(text)) continue;
                String name = text.replace("\n", " ").trim();
                return name.length() > 24 ? name.substring(0, 24) : name;
            }
        } catch (Exception e) { }
        return "";
    }

    private boolean isRealUserText(String text) {
        return text.length() > 0 && !text.startsWith("<environment_context>")
                && !text.startsWith("<user_instructions>")
                && !text.startsWith("<ENVIRONMENT_CONTEXT>")
                && !text.startsWith("<turn_aborted>")
                && !text.startsWith("# AGENTS.md instructions")
                && !text.startsWith("<INSTRUCTIONS>")
                && !text.startsWith("<permissions");
    }

    private JSONArray readRolloutTurns(String id) {
        JSONArray turns = new JSONArray();
        try {
            File f = findRollout(id);
            if (f == null) return turns;
            String curRole = null;
            StringBuilder curText = new StringBuilder();
            for (String line : readTextFile(f).split("\n")) {
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject m;
                try {
                    m = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                if (!m.optString("type", "").equals("response_item")) continue;
                JSONObject pl = m.optJSONObject("payload");
                if (pl == null || !pl.optString("type", "").equals("message")) continue;
                String role = pl.optString("role", "");
                if (!role.equals("user") && !role.equals("assistant")) continue;
                String text = msgText(pl);
                if (!isRealUserText(text)) continue;
                flushTurn(turns, curRole, curText);
                curRole = role;
                curText.setLength(0);
                curText.append(text);
            }
            flushTurn(turns, curRole, curText);
        } catch (Exception e) { }
        return turns;
    }

    private String[] rolloutTexts(File f) {
        String firstUser = "";
        String lastAgent = "";
        try {
            for (String line : readTextFile(f).split("\n")) {
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject m;
                try {
                    m = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                String type = m.optString("type", "");
                JSONObject pl = m.optJSONObject("payload");
                if (pl == null) continue;
                if (type.equals("response_item") && pl.optString("type", "").equals("message")) {
                    String role = pl.optString("role", "");
                    String text = msgText(pl);
                    if (!isRealUserText(text)) continue;
                    if (role.equals("user") && firstUser.length() == 0) firstUser = text;
                    if (role.equals("assistant")) lastAgent = text;
                } else if (type.equals("event_msg")
                        && pl.optString("type", "").equals("task_complete")) {
                    String lm = os(pl, "last_agent_message", "");
                    if (lm.length() > 0) lastAgent = lm;
                }
            }
        } catch (Exception e) { }
        if (firstUser.length() > 24) firstUser = firstUser.substring(0, 24);
        if (lastAgent.length() > 80) lastAgent = lastAgent.substring(0, 80);
        return new String[]{firstUser.replace("\n", " ").trim(),
                lastAgent.replace("\n", " ").trim()};
    }

    private JSONArray scanRolloutConvs() {
        JSONArray arr = new JSONArray();
        try {
            File base = new File(Store.codexHome(ctx), "sessions");
            if (!base.isDirectory()) return arr;
            java.util.HashSet<String> callOnly = callOnlyTids();
            java.util.List<File> fs = new java.util.ArrayList<File>();
            collectRollouts(base, fs);
            java.util.Collections.sort(fs, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    long x = b.lastModified() - a.lastModified();
                    return x > 0 ? 1 : (x < 0 ? -1 : 0);
                }
            });
            int limit = Math.min(fs.size(), 50);
            for (int i = 0; i < limit; i++) {
                File f = fs.get(i);
                String name = f.getName();
                if (name.length() < 44) continue;
                String id = name.substring(name.length() - 42, name.length() - 6);
                if (id.length() != 36) continue;
                if (callOnly.contains(id)) continue;
                JSONObject o = new JSONObject();
                String[] tx = rolloutTexts(f);
                String nm = tx[0].length() > 0 ? tx[0] : tx[1];
                o.put("id", id);
                o.put("name", nm);
                o.put("preview", tx[1]);
                o.put("ts", f.lastModified());
                o.put("live", live.get(id) != null);
                o.put("perm", false);
                arr.put(o);
            }
        } catch (Exception e) { }
        return arr;
    }

    static JSONArray fetchModelIds(String listUrl, String key) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(listUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (key != null && key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            if (in != null) in.close();
            if (code >= 400) return null;
            JSONObject resp = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray data = resp.optJSONArray("data");
            if (data == null) data = resp.optJSONArray("models");
            if (data == null) return null;
            JSONArray ids = new JSONArray();
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                ids.put(m != null ? os(m, "id", os(m, "name", "")) : data.optString(i));
            }
            return ids;
        } catch (Exception e) {
            return null;
        }
    }

    static void seedModels(Context ctx, String provider, JSONArray ids) {
        try {
            JSONObject st = Store.loadSettings(ctx);
            JSONObject rm = st.optJSONObject("remote_models");
            if (rm == null) rm = new JSONObject();
            rm.put(provider, ids);
            st.put("remote_models", rm);
            Store.saveSettings(ctx, st);
        } catch (Exception ig) { }
    }

    private void handleMapcheck(Socket s, String body) throws Exception {
        try {
            String b = body == null ? "" : body;
            if (b.length() > 8192) b = b.substring(0, 8192);
            File wd = new File(ctx.getFilesDir(), "work");
            wd.mkdirs();
            File f = new File(wd, "mapcheck.log");
            if (f.length() > 65536) f.delete();
            String rec = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + "\t" + b + "\n";
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            try {
                fo.write(rec.getBytes("UTF-8"));
            } finally {
                fo.close();
            }
            sendJson(s, new JSONObject().put("ok", true));
        } catch (Exception e) {
            sendJson(s, new JSONObject().put("ok", false));
        }
    }

    private void handleChat(Socket s, String body) throws Exception {
        JSONObject b = new JSONObject(body);
        String text = b.optString("text", "");
        String audioPath = b.optString("audio", "");
        JSONArray attach = b.optJSONArray("attach");
        String convId = b.optString("conv_id", "");
        if (text.length() == 0 && audioPath.length() == 0) {
            sendJson(s, new JSONObject().put("err", "empty"));
            return;
        }
        String busyKey = (convId.length() > 0) ? tipId(rootId(convId)) : "";
        if (busyKey != null && busyKey.length() > 0 && activeTurns.containsKey(busyKey)) {
            long st = 0;
            Long sta = turnStartedAt.get(busyKey);
            if (sta != null) st = System.currentTimeMillis() - sta.longValue();
            JSONObject e409 = new JSONObject();
            e409.put("err", "该对话正在进行中（已运行 " + (st / 1000) + " 秒）");
            e409.put("busy", true);
            e409.put("conv_id", convId);
            e409.put("hint", "点输入框右侧的方块按钮可以停止本轮任务");
            sendBytes(s, 409, "application/json", e409.toString().getBytes("UTF-8"), null);
            return;
        }
        StringBuilder prompt = new StringBuilder();
        if (text.length() > 0) prompt.append(text);
        if (text.length() == 0 && audioPath.length() > 0) {
            String asrText = "";
            try {
                File af = new File(Store.uploadsDir(ctx), audioPath);
                if (af.isFile() && af.length() < 15 * 1024 * 1024) {
                    FileInputStream fin = new FileInputStream(af);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] abuf = new byte[8192];
                    int an;
                    while ((an = fin.read(abuf)) > 0) bos.write(abuf, 0, an);
                    fin.close();
                    asrText = dashAsr(bos.toByteArray());
                }
            } catch (Exception e) { }
            prompt.append(asrText.length() > 0 ? asrText : "[语音消息]");
        }
        if (attach != null && attach.length() > 0) {
            StringBuilder files = new StringBuilder();
            StringBuilder pics = new StringBuilder();
            int npic = 0;
            Log.i(TAG, "chat attach n=" + attach.length());
            for (int i = 0; i < attach.length(); i++) {
                JSONObject a = attach.optJSONObject(i);
                if (a == null) continue;
                String abs = Store.workDir(ctx).getAbsolutePath() + "/" + a.optString("path", "");
                String low = abs.toLowerCase();
                boolean isPic = low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg")
                        || low.endsWith(".webp") || low.endsWith(".gif") || low.endsWith(".bmp")
                        || low.endsWith(".heic") || low.endsWith(".heif");
                StringBuilder dstb = isPic ? pics : files;
                if (dstb.length() > 0) dstb.append(", ");
                dstb.append(abs);
                if (isPic) npic++;
                Log.i(TAG, "chat attach[" + i + "] " + (isPic ? "pic " : "file ") + abs);
            }
            if (prompt.length() > 0) prompt.append("\n\n");
            if (files.length() > 0) {
                prompt.append("[用户附带了以下文件，请先用工具查看后再处理]：").append(files);
                if (pics.length() > 0) prompt.append("\n");
            }
            if (pics.length() > 0) {
                prompt.append("[用户附带了以下图片（共 ").append(npic)
                        .append(" 张，路径互不相同，逐张处理即可）：必须先用 read_image 工具"
                                + "逐张看清内容再回答，禁止凭空猜测]：").append(pics);
            }
        }
        if (audioPath.length() > 0) {
            String abs = Store.uploadsDir(ctx).getAbsolutePath() + "/" + audioPath;
            if (prompt.length() > 0) prompt.append("\n\n");
            prompt.append("[这是一条语音消息，音频文件在]：").append(abs)
                    .append("。请先用工具转写或听取后再回复。");
        }
        if ("1".equals(Store.getSetting(ctx, "plan_mode", "0"))) {
            if (prompt.length() > 0) prompt.append("\n\n");
            prompt.append("[计划模式已开启] 本轮先不要执行任何改动。"
                    + "把要做的事拆成步骤，用 todo_write 写成清单，并在回复里用列表展示给用户："
                    + "每步要动什么、产出什么、有什么风险。然后停下来等用户确认，"
                    + "用户回复同意后再执行。");
        }
        sseBegin(s);
        final String sid = "sse" + System.nanoTime();
        final Socket fs = s;
        Emitter em = new Emitter() {
            public boolean emit(String event, JSONObject data) {
                boolean ok = sseSend(fs, event, data);
                if (!ok) emitters.remove(sid);
                return ok;
            }
        };
        emitters.put(sid, em);
        boolean voiceMode = audioPath != null && audioPath.length() > 0;
        try {
            runTurn(sid, em, convId, prompt.toString(), voiceMode);
        } finally {
            emitters.remove(sid);
            sseEnd(s);
            try { s.close(); } catch (Exception ig) { }
        }
    }

    private String[] syncConvModel(String threadId, JSONObject thread) {
        try {
            String want = Store.getSetting(ctx, "model", "qwen3.8-max");
            String wantP = Store.getSetting(ctx, "provider", "dashscope");
            String cur = thread == null ? "" : thread.optString("model", "");
            String curP = thread == null ? "" : thread.optString("modelProvider", "");
            if (want.length() == 0) return null;
            boolean needModel = !want.equals(cur);
            boolean needProv = curP.length() > 0 && wantP.length() > 0 && !wantP.equals(curP);
            Log.i(TAG, "conv model check tid=" + threadId + " want=" + want + "/" + wantP
                    + " cur=" + cur + "/" + curP
                    + " needModel=" + needModel + " needProv=" + needProv);
            if (!needModel && !needProv) return null;
            if (needProv) {
                JSONObject fr = codex.forkThread(threadId, want, wantP);
                JSONObject ft = fr.optJSONObject("thread");
                if (ft != null && ft.optString("id", "").length() > 0) {
                    String nid = ft.optString("id");
                    noteFork(nid, threadId);
                    markCallTid(threadId);
                    Log.i(TAG, "model switch fork " + threadId + " -> " + nid + " model=" + want
                            + " provider=" + wantP);
                    return new String[]{nid, want, threadId};
                }
                Log.w(TAG, "model switch fork failed: " + fr);
                return null;
            }
            JSONObject ur = codex.updateThreadModel(threadId, want);
            if (ur.has("error")) {
                Log.w(TAG, "model switch failed: " + ur);
                return null;
            }
            Log.i(TAG, "model switch " + threadId + " -> " + want + " (provider " + curP + ")");
            return new String[]{threadId, want, null};
        } catch (Exception e) {
            Log.e(TAG, "syncConvModel", e);
            return null;
        }
    }

    private void runTurn(final String sid, final Emitter em, String convId, String prompt,
                         boolean voiceMode) {
        long t0 = System.currentTimeMillis();
        ensureForks();
        String clientId = (convId != null && convId.length() > 0) ? rootId(convId) : null;
        String tidHold = (clientId == null) ? convId : tipId(clientId);
        String switchedModel = null;
        String switchedFrom = null;
        try {
            String threadId = (clientId == null) ? null : tipId(clientId);
            if (threadId == null || threadId.length() == 0) {
                String model = Store.getSetting(ctx, "model", "qwen3.8-max");
                String provider = Store.getSetting(ctx, "provider", "dashscope");
                JSONObject r = codex.startThread(model, provider);
                JSONObject thread = r.optJSONObject("thread");
                if (thread == null) {
                    em.emit("error", new JSONObject().put("msg", "建会话失败: " + r));
                    return;
                }
                threadId = thread.optString("id", "");
                convModel.put(threadId, new String[]{model, provider});
            } else {
                if (!codex.isUp()) {
                    em.emit("error", new JSONObject().put("msg", "引擎未就绪，请稍后重试"));
                    return;
                }
                JSONObject rr = codex.resumeThread(threadId);
                if (!rr.has("error")) {
                    JSONObject rt = rr.optJSONObject("thread");
                    if (rt == null) rt = rr;
                    if (rt != null) {
                        String rm = rt.optString("model", "");
                        String rp = rt.optString("modelProvider", "");
                        String[] sy = syncConvModel(threadId, rt);
                        if (sy != null) {
                            threadId = sy[0];
                            switchedModel = sy[1];
                            if (sy[2] != null) switchedFrom = sy[2];
                            rm = sy[1];
                            rp = Store.getSetting(ctx, "provider", rp);
                        }
                        if (rm.length() > 0) convModel.put(threadId, new String[]{rm, rp});
                    }
                }
                if (rr.has("error")) {
                    JSONObject o = new JSONObject();
                    o.put("msg", "无法恢复本对话（引擎没有加载它的历史）。为避免悄悄新建对话，本轮已停止。"
                            + "请稍后重试，或手动新建对话。原始错误: " + rr.opt("error"));
                    em.emit("error", o);
                    return;
                }
            }
            String tid = threadId;
            tidHold = tid;
            String outId = (clientId == null) ? tid : clientId;
            JSONObject st = new JSONObject();
            st.put("conv_id", outId);
            if (switchedModel != null) st.put("model", switchedModel);
            if (switchedFrom != null) st.put("switched_from", switchedFrom);
            em.emit("start", st);
            LiveState ls = new LiveState();
            live.put(tid, ls);
            activeTurns.put(tid, true);
            turnEmitters.put(tid, em);
            turnCallCounts.put(tid, new ConcurrentHashMap<String, Integer>());
            turnLastCmd.remove(tid);
            turnLastMsg.remove(tid);
            turnLastSeen.put(tid, Long.valueOf(System.currentTimeMillis()));
            turnStartedAt.put(tid, Long.valueOf(System.currentTimeMillis()));
            codex.armTurnWait(tid);
            JSONObject r = codex.sendTurn(tid, prompt);
            if (r.has("error") || r.has("timeout")) {
                JSONObject o = new JSONObject();
                o.put("msg", "引擎出错: " + (r.has("timeout") ? "超时" : r.opt("error")));
                em.emit("error", o);
                return;
            }
            String tst = "";
            long tokens = -1;
            int cont = 0;
            while (true) {
                JSONObject wr = codex.waitTurn(tid, 10800);
                if (wr.has("timeout")) {
                    em.emit("error", new JSONObject().put("msg", "本轮任务超时（3小时）"));
                    return;
                }
                if (wr.has("error")) {
                    em.emit("error", new JSONObject().put("msg", "引擎出错: " + wr.opt("error")));
                    return;
                }
                tst = "";
                JSONObject turn = wr.optJSONObject("turn");
                if (turn != null) {
                    JSONObject usage = turn.optJSONObject("usage");
                    if (usage != null) tokens = usage.optLong("total", -1);
                    tst = turn.optString("status", "");
                }
                Map<String, Integer> cc = turnCallCounts.get(tid);
                int nTool = cc == null ? 0 : cc.size();
                String lmsg = turnLastMsg.get(tid);
                boolean finished = lmsg != null && lmsg.indexOf(DONE_MARK) >= 0;
                boolean tasky = nTool > 0 || (prompt != null && prompt.indexOf(ATTACH_MARK) >= 0);
                boolean promise = promisesTool(lmsg);
                int cap = nTool > 0 ? AUTO_CONTINUE_MAX : AUTO_CONTINUE_STALL_MAX;
                Log.i(TAG, "turn finished tid=" + tid + " status=" + tst
                        + " elapsed=" + (System.currentTimeMillis() - t0) + "ms"
                        + " tools=" + nTool + " done=" + finished
                        + " tasky=" + tasky + " promise=" + promise);
                if (tst.equals("completed") && !finished && (tasky || promise)
                        && cont < cap) {
                    cont++;
                    Log.i(TAG, "auto-continue #" + cont + " tid=" + tid);
                    em.emit("stage", new JSONObject().put("stage", "继续中"));
                    codex.armTurnWait(tid);
                    JSONObject cr = codex.sendTurn(tid, AUTO_CONTINUE_PROMPT);
                    if (cr.has("error") || cr.has("timeout")) {
                        Log.i(TAG, "auto-continue sendTurn failed: " + cr);
                        break;
                    }
                    continue;
                }
                break;
            }
            JSONObject done = new JSONObject();
            done.put("conv_id", outId);
            done.put("ms", System.currentTimeMillis() - t0);
            if (tokens >= 0) done.put("tokens", tokens);
            done.put("status", tst);
            if (cont > 0) done.put("auto_continued", cont);
            em.emit("done", done);
            LiveState dls = live.get(tid);
            Notifier.done(ctx, dls == null ? "" : dls.text.toString());
            if (voiceMode) {
                LiveState fls = live.get(tid);
                String reply = fls == null ? "" : fls.text.toString();
                Log.i(TAG, "voice reply len=" + reply.length());
                java.util.List<String> parts = splitSpeech(reply);
                for (int i = 0; i < parts.size(); i++) {
                    String vp = dashTts(parts.get(i), "longqiang_v2");
                    if (vp == null) {
                        Log.i(TAG, "voice tts chunk failed seq=" + i);
                        break;
                    }
                    JSONObject av = new JSONObject();
                    av.put("path", vp);
                    av.put("seq", i);
                    em.emit("avoice", av);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "runTurn", e);
            try {
                em.emit("error", new JSONObject().put("msg", String.valueOf(e)));
            } catch (Exception ig) { }
        } finally {
            if (tidHold != null) {
                live.remove(tidHold);
                activeTurns.remove(tidHold);
                turnEmitters.remove(tidHold);
                turnCallCounts.remove(tidHold);
                turnLastCmd.remove(tidHold);
                turnLastMsg.remove(tidHold);
                turnLastSeen.remove(tidHold);
                turnStartedAt.remove(tidHold);
            }
        }
    }

    private void handleCallTurn(Socket s, String body) {
        String sid = "s" + System.currentTimeMillis();
        boolean aborted = false;
        try {
            JSONObject b = new JSONObject(body);
            String convId = b.optString("conv_id", "");
            boolean greet = b.optBoolean("greet", false);
            String audioB64 = b.optString("audio_b64", "");
            sseBegin(s);
            final Socket fs = s;
            Emitter em = new Emitter() {
                public boolean emit(String event, JSONObject data) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", event);
                        if (event.equals("error")) {
                            o.put("text", data == null ? "" : data.optString("msg", data.optString("text", "")));
                        } else if (data != null) {
                            java.util.Iterator<String> it = data.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                o.put(k, data.opt(k));
                            }
                        }
                        boolean ok = sseSend(fs, event, o);
                        if (!ok) emitters.remove(sid);
                        return ok;
                    } catch (Exception e) {
                        return false;
                    }
                }
            };
            if (convId.length() == 0) {
                em.emit("error", new JSONObject().put("text", "缺少 conv_id"));
                em.emit("end", new JSONObject().put("reason", "error"));
                return;
            }
            emitters.put(sid, em);
            if (greet) {
                JSONObject st = new JSONObject();
                st.put("sid", sid);
                st.put("conv_id", convId);
                em.emit("start", st);
                callSay(em, "在的，请说。");
                em.emit("end", new JSONObject().put("reason", "ok"));
                return;
            }
            String tid = callTids.get(convId);
            if (tid != null && Boolean.TRUE.equals(activeTurns.get(tid))) {
                em.emit("error", new JSONObject().put("text", "上一轮还在进行中"));
                em.emit("end", new JSONObject().put("reason", "busy"));
                return;
            }
            if (tid == null) {
                JSONObject r = codex.startThread(
                        Store.getSetting(ctx, "model", "qwen3.8-max"),
                        Store.getSetting(ctx, "provider", "dashscope"));
                JSONObject thread = r.optJSONObject("thread");
                if (thread == null) {
                    em.emit("error", new JSONObject().put("text", "建会话失败"));
                    em.emit("end", new JSONObject().put("reason", "error"));
                    return;
                }
                tid = thread.optString("id", "");
                callTids.put(convId, tid);
                markCallTid(tid);
            }
            if (!codex.isUp()) {
                em.emit("error", new JSONObject().put("text", "引擎未就绪"));
                em.emit("end", new JSONObject().put("reason", "error"));
                return;
            }
            byte[] audio = android.util.Base64.decode(audioB64, android.util.Base64.NO_WRAP);
            JSONObject st = new JSONObject();
            st.put("sid", sid);
            st.put("conv_id", convId);
            em.emit("start", st);
            JSONObject sg = new JSONObject();
            sg.put("text", "识别中…");
            em.emit("stage", sg);
            long asrT0 = System.currentTimeMillis();
            String asrText = audio.length > 0 ? dashAsr(audio) : "";
            Log.i(TAG, "call asr ms=" + (System.currentTimeMillis() - asrT0)
                    + " len=" + asrText.length());
            JSONObject at = new JSONObject();
            at.put("text", asrText);
            em.emit("asr", at);
            if (asrText.length() == 0) {
                em.emit("end", new JSONObject().put("reason", "empty"));
                return;
            }
            callSids.put(sid, tid);
            live.put(tid, new LiveState());
            activeTurns.put(tid, true);
            turnEmitters.put(tid, em);
            codex.armTurnWait(tid);
            JSONObject r = codex.sendTurn(tid, asrText);
            if (r.has("error")) {
                JSONObject rr = codex.resumeThread(tid);
                if (!rr.has("error")) {
                    codex.armTurnWait(tid);
                    r = codex.sendTurn(tid, asrText);
                }
                if (r.has("error")) {
                    JSONObject r2 = codex.startThread(
                            Store.getSetting(ctx, "model", "qwen3.8-max"),
                            Store.getSetting(ctx, "provider", "dashscope"));
                    JSONObject nt = r2.optJSONObject("thread");
                    if (nt != null && nt.optString("id", "").length() > 0) {
                        String ntid = nt.optString("id");
                        callTids.put(convId, ntid);
                        live.remove(tid);
                        activeTurns.remove(tid);
                        turnEmitters.remove(tid);
                        tid = ntid;
                        markCallTid(tid);
                        JSONObject st3 = new JSONObject();
                        st3.put("sid", sid);
                        st3.put("conv_id", convId);
                        em.emit("start", st3);
                        live.put(tid, new LiveState());
                        activeTurns.put(tid, true);
                        turnEmitters.put(tid, em);
                        codex.armTurnWait(tid);
                        r = codex.sendTurn(tid, asrText);
                    }
                }
            }
            if (r.has("error")) {
                em.emit("error", new JSONObject().put("text", "引擎出错: " + r.opt("error")));
                em.emit("end", new JSONObject().put("reason", "error"));
                return;
            }
            JSONObject wr = codex.waitTurn(tid, 600);
            if (callSids.remove(sid) == null) aborted = true;
            LiveState ls = live.get(tid);
            String full = ls == null ? "" : ls.text.toString();
            live.remove(tid);
            activeTurns.remove(tid);
            turnEmitters.remove(tid);
            if (aborted) {
                em.emit("end", new JSONObject().put("reason", "abort"));
                return;
            }
            if (wr.has("error")) {
                em.emit("error", new JSONObject().put("text", String.valueOf(wr.opt("error"))));
                em.emit("end", new JSONObject().put("reason", "error"));
                return;
            }
            if (wr.has("timeout")) {
                em.emit("error", new JSONObject().put("text", "本轮任务超时"));
                em.emit("end", new JSONObject().put("reason", "error"));
                return;
            }
            if (full.length() == 0) {
                em.emit("end", new JSONObject().put("reason", "empty"));
                return;
            }
            callSay(em, full);
            em.emit("end", new JSONObject().put("reason", "ok"));
        } catch (Exception e) {
            Log.e(TAG, "callTurn", e);
        } finally {
            callSids.remove(sid);
            emitters.remove(sid);
            sseEnd(s);
            try { s.close(); } catch (Exception ig) { }
        }
    }

    private static java.util.List<String> splitSpeech(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null) return out;
        String t = text.replaceAll("```[\\s\\S]*?```", "。")
                .replaceAll("[|`#>*_\\[\\]]", " ")
                .replaceAll("\\s+", " ").trim();
        if (t.length() == 0) return out;
        int max = 140;
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(t.length(), start + max);
            if (end < t.length()) {
                int cut = -1;
                for (int i = end; i > start + 60; i--) {
                    char ch = t.charAt(i - 1);
                    if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == ';'
                            || ch == '.' || ch == '!' || ch == '?') {
                        cut = i;
                        break;
                    }
                }
                if (cut > start) end = cut;
            }
            String seg = t.substring(start, end).trim();
            if (seg.length() > 0) out.add(seg);
            start = end;
        }
        return out;
    }

    private void callSay(Emitter em, String text) {
        try {
            java.util.List<String> parts = splitSpeech(text);
            if (parts.isEmpty()) {
                em.emit("say_err", new JSONObject());
                return;
            }
            for (int i = 0; i < parts.size(); i++) {
                String vp = dashTts(parts.get(i), "longqiang_v2");
                if (vp == null) {
                    em.emit("say_err", new JSONObject());
                    return;
                }
                JSONObject sy = new JSONObject();
                sy.put("seq", i);
                sy.put("url", "api/audio?path=" + java.net.URLEncoder.encode(vp, "UTF-8"));
                em.emit("say", sy);
            }
        } catch (Exception e) {
            try {
                em.emit("say_err", new JSONObject());
            } catch (Exception ig) { }
        }
    }

    private void onCodexEvent(Emitter em, String tid, String method, JSONObject params) {
        try {
            if (params == null) return;
            String type = params.optString("type", "");
            if (method.equals("item/started")) {
                JSONObject item = params.optJSONObject("item");
                if (item != null) {
                    String it = item.optString("type", "");
                    if (it.equals("commandExecution")
                            && loopGuard(tid, item.optString("command", ""), em)) return;
                    if (it.equals("mcpToolCall")
                            && toolLoopGuard(tid, os(item, "tool", os(item, "name", "")),
                                    String.valueOf(item.opt("arguments")), em)) return;
                    LiveState ls = live.get(tid);
                    if (ls != null) {
                        if (it.equals("reasoning")) ls.stage = "思考中";
                        else if (it.equals("commandExecution")) {
                            ls.stage = "执行命令";
                            ls.tool = os(item, "command", os(item, "title", ""));
                        } else if (it.equals("fileChange")) {
                            ls.stage = "改文件";
                            ls.tool = os(item, "title", "");
                        } else if (it.equals("mcpToolCall")) {
                            ls.stage = "调用工具";
                            ls.tool = os(item, "tool", "");
                        } else if (it.equals("agentMessage")) {
                            ls.stage = "回复中";
                            ls.msgId = os(item, "id", "");
                            ls.msgStreamed = false;
                        }
                    }
                    if (it.equals("commandExecution") || it.equals("mcpToolCall") || it.equals("fileChange")) {
                        JSONObject te = new JSONObject();
                        te.put("name", it);
                        te.put("detail", os(item, "command", os(item, "title", os(item, "tool", ""))));
                        em.emit("tool", te);
                    } else if (it.equals("reasoning")) {
                        em.emit("stage", new JSONObject().put("stage", "思考中"));
                    }
                }
            } else if (method.equals("item/completed")) {
                JSONObject item = params.optJSONObject("item");
                if (item != null && item.optString("type", "").equals("agentMessage")) {
                    String text = os(item, "text", "");
                    if (text.length() == 0) {
                        JSONArray cont = item.optJSONArray("content");
                        if (cont != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < cont.length(); i++) {
                                JSONObject blk = cont.optJSONObject(i);
                                if (blk != null) sb.append(os(blk, "text", ""));
                            }
                            text = sb.toString();
                        }
                    }
                    if (text.length() > 0) {
                        String mid = os(item, "id", "");
                        LiveState ls = live.get(tid);
                        boolean streamed = false;
                        if (ls != null) {
                            ls.stage = "回复中";
                            if (!mid.equals(ls.msgId)) {
                                ls.msgId = mid;
                                ls.msgStreamed = false;
                            }
                            if (ls.msgStreamed) {
                                streamed = true;
                            } else {
                                if (ls.text.length() > 0) ls.text.append("\n");
                                ls.text.append(text);
                            }
                        }
                        if (!streamed) {
                            JSONObject o = new JSONObject();
                            o.put("text", text);
                            em.emit("delta", o);
                        }
                        turnLastMsg.put(tid, text);
                    }
                }
            } else if (method.equals("item/agentMessage/delta")) {
                LiveState ls = live.get(tid);
                if (ls != null) {
                    ls.stage = "回复中";
                    String mid = os(params, "itemId", "");
                    if (mid.length() > 0 && !mid.equals(ls.msgId)) {
                        ls.msgId = mid;
                        ls.msgStreamed = false;
                    }
                    ls.msgStreamed = true;
                    ls.text.append(os(params, "delta", ""));
                }
                JSONObject o = new JSONObject();
                o.put("text", os(params, "delta", ""));
                em.emit("delta", o);
            } else if (method.equals("item/reasoning/summaryTextDelta")
                    || method.equals("item/reasoning/textDelta")) {
                JSONObject o = new JSONObject();
                o.put("text", os(params, "delta", ""));
                em.emit("think", o);
            } else if (method.equals("turn/completed")) {
                LiveState ls = live.get(tid);
                if (ls != null) {
                    JSONObject st = new JSONObject();
                    st.put("stage", "已完成");
                    em.emit("stage", st);
                }
            } else if (method.equals("error")) {
                JSONObject o = new JSONObject();
                o.put("msg", os(params, "message", "引擎错误"));
                em.emit("error", o);
            } else if (method.equals("turn/aborted")) {
                JSONObject o = new JSONObject();
                o.put("msg", "本轮已中断");
                em.emit("error", o);
            }
        } catch (Exception e) {
            Log.e(TAG, "onCodexEvent", e);
        }
    }

    private static String os(JSONObject o, String k, String def) {
        if (o == null) return def;
        Object v = o.opt(k);
        return (v instanceof String) ? (String) v : def;
    }

    private boolean loopGuard(String tid, String cmd, Emitter em) {
        if (cmd == null || cmd.length() == 0) return false;
        String k = cmd.length() > 400 ? cmd.substring(0, 400) : cmd;
        Map<String, Integer> m = turnCallCounts.get(tid);
        if (m == null) {
            m = new ConcurrentHashMap<String, Integer>();
            turnCallCounts.put(tid, m);
        }
        String lc = turnLastCmd.get(tid);
        int n;
        if (k.equals(lc)) {
            Integer prev = m.get(k);
            n = (prev == null ? 0 : prev) + 1;
        } else {
            n = 1;
            turnLastCmd.put(tid, k);
        }
        m.put(k, n);
        if (n < LOOP_GUARD_LIMIT) return false;
        final String ftid = tid;
        new Thread(new Runnable() {
            public void run() {
                try { codex.interrupt(ftid); } catch (Exception ig) { }
            }
        }, "loop-guard").start();
        try {
            JSONObject o = new JSONObject();
            o.put("msg", "已自动中断：同一条命令被重复执行 " + n + " 次，结果不会变化。"
                    + "请改成一次到位的做法（如写脚本一次跑完），或直接汇报现有结果。");
            em.emit("error", o);
        } catch (Exception ig) { }
        return true;
    }

    private boolean toolLoopGuard(String tid, String name, String argsJson, Emitter em) {
        if (name == null || name.length() == 0) return false;
        if (McpServer.isReadOnlyTool(name)) return false;
        String a = argsJson == null ? "" : argsJson;
        if (a.length() > 400) a = a.substring(0, 400);
        String k = "T:" + name + ":" + a;
        Map<String, Integer> m = turnCallCounts.get(tid);
        if (m == null) {
            m = new ConcurrentHashMap<String, Integer>();
            turnCallCounts.put(tid, m);
        }
        String lc = turnLastCmd.get(tid);
        int n;
        if (k.equals(lc)) {
            Integer prev = m.get(k);
            n = (prev == null ? 0 : prev) + 1;
        } else {
            n = 1;
            turnLastCmd.put(tid, k);
        }
        m.put(k, n);
        if (n < LOOP_GUARD_TOOL_LIMIT) return false;
        final String ftid = tid;
        new Thread(new Runnable() {
            public void run() {
                try { codex.interrupt(ftid); } catch (Exception ig) { }
            }
        }, "loop-guard-tool").start();
        try {
            em.emit("error", new JSONObject().put("msg",
                    "已自动中断：工具 " + name + " 用同样的参数被连续调用了 " + n
                    + " 次，结果不会变化。请换成一次到位的做法，或直接汇报现有结果。"));
        } catch (Exception ig) { }
        return true;
    }

    private void watchLoop() {
        while (running) {
            try {
                Thread.sleep(30000);
            } catch (Exception e) {
                return;
            }
            try {
                sweepStalledTurns();
            } catch (Exception e) {
                Log.e(TAG, "watchdog", e);
            }
        }
    }

    private void sweepStalledTurns() {
        long now = System.currentTimeMillis();
        for (String tid : activeTurns.keySet()) {
            Long last = turnLastSeen.get(tid);
            if (last == null) continue;
            Long st = turnStartedAt.get(tid);
            boolean idle = now - last.longValue() >= TURN_IDLE_MS;
            boolean over = st != null && now - st.longValue() >= TURN_MAX_MS;
            if (!idle && !over) continue;
            if (hasPendingPerm()) {
                turnLastSeen.put(tid, Long.valueOf(now));
                continue;
            }
            String why = over ? ("本轮已跑满 " + (TURN_MAX_MS / 60000) + " 分钟")
                    : ("本轮已 " + (TURN_IDLE_MS / 60000) + " 分钟没有任何进展");
            Log.w(TAG, "turn stalled, auto-unlock " + tid + " (" + why + ")");
            try { codex.interrupt(tid); } catch (Exception ig) { }
            Emitter em = turnEmitters.get(tid);
            if (em != null) {
                try {
                    em.emit("error", new JSONObject().put("msg",
                            why + "，已自动中断并解锁，可以直接发下一条消息。"));
                } catch (Exception ig) { }
            }
            releaseTurn(tid);
        }
    }

    private boolean hasPendingPerm() {
        for (PermEntry e : perms.values()) {
            if (!e.answered) return true;
        }
        return false;
    }

    private void releaseTurn(String tid) {
        if (tid == null || tid.length() == 0) return;
        live.remove(tid);
        activeTurns.remove(tid);
        turnEmitters.remove(tid);
        turnCallCounts.remove(tid);
        turnLastCmd.remove(tid);
        turnLastMsg.remove(tid);
        turnLastSeen.remove(tid);
        turnStartedAt.remove(tid);
        turnSentFiles.remove(tid);
    }

    private void restartCodex() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    codex.stop();
                    Thread.sleep(800);
                    codex.start();
                    Log.i(TAG, "codex restarted");
                } catch (Exception e) {
                    Log.e(TAG, "restart codex fail", e);
                }
            }
        }, "codex-restart").start();
    }

    private void serveFile(Socket s, String relPath, boolean download, String rangeHeader) throws Exception {
        try {
            if (relPath == null || relPath.contains("..")) {
                sendBytes(s, 403, "text/plain", "forbidden".getBytes(), null);
                return;
            }
            File raw = relPath.startsWith("/")
                    ? new File(relPath)
                    : new File(ctx.getFilesDir(), relPath);
            String canon = raw.getCanonicalPath();
            String filesRoot = ctx.getFilesDir().getCanonicalPath() + File.separator;
            String cacheRoot = ctx.getCacheDir().getCanonicalPath() + File.separator;
            if (!canon.startsWith(filesRoot) && !canon.startsWith(cacheRoot)) {
                sendBytes(s, 403, "text/plain", "forbidden".getBytes(), null);
                return;
            }
            File f = new File(canon);
            if (!f.isFile()) {
                sendBytes(s, 404, "text/plain", "no file".getBytes(), null);
                return;
            }
            String name = f.getName();
            String mime = guessMime(name);
            long len = f.length();
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                OutputStream os = s.getOutputStream();
                String disposition = (download ? "attachment" : "inline")
                        + "; filename=\"" + name + "\"";
                long start = 0;
                long end = len - 1;
                boolean partial = false;
                String rl = rangeHeader;
                if (rl != null && rl.startsWith("bytes=")) {
                    String[] seg = rl.substring(6).split("-");
                    try {
                        start = Long.parseLong(seg[0]);
                        if (seg.length > 1 && seg[1].length() > 0) end = Long.parseLong(seg[1]);
                        partial = true;
                    } catch (Exception ig) { }
                }
                if (end >= len) end = len - 1;
                String status = partial ? "206 Partial Content" : "200 OK";
                StringBuilder h = new StringBuilder();
                h.append("HTTP/1.1 ").append(status).append("\r\n");
                h.append("Content-Type: ").append(mime).append("\r\n");
                h.append("Content-Disposition: ").append(disposition).append("\r\n");
                if (partial) {
                    h.append("Content-Range: bytes ").append(start).append("-").append(end)
                            .append("/").append(len).append("\r\n");
                }
                h.append("Accept-Ranges: bytes\r\n");
                h.append("Content-Length: ").append(end - start + 1).append("\r\n");
                h.append("Connection: close\r\n\r\n");
                os.write(h.toString().getBytes("UTF-8"));
                raf.seek(start);
                byte[] buf = new byte[16384];
                long remaining = end - start + 1;
                while (remaining > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    os.write(buf, 0, n);
                    remaining -= n;
                }
                os.flush();
                s.close();
            } finally {
                try { raf.close(); } catch (Exception ig) { }
            }
        } catch (Exception e) {
            try { s.close(); } catch (Exception ig) { }
        }
    }

    private String singleActiveTurn() {
        String only = null;
        int n = 0;
        try {
            for (String cid : activeTurns.keySet()) {
                n++;
                only = cid;
            }
        } catch (Exception ig) { }
        return n == 1 ? only : null;
    }

    public synchronized boolean pushFile(String path, String name, long sizeBytes) {
        try {
            long now = System.currentTimeMillis();
            if (path != null && path.equals(lastPushPath) && now - lastPushAt < DUP_PUSH_MS) {
                return false;
            }
            String tid = singleActiveTurn();
            if (tid != null && path != null) {
                java.util.Set<String> set = turnSentFiles.get(tid);
                if (set == null) {
                    set = java.util.Collections.newSetFromMap(
                            new ConcurrentHashMap<String, Boolean>());
                    turnSentFiles.put(tid, set);
                }
                if (!set.add(path)) return false;
            }
            lastPushPath = path;
            lastPushAt = now;
            JSONObject fe = new JSONObject();
            fe.put("id", "f" + now + "x" + permSeq.incrementAndGet());
            fe.put("path", path);
            fe.put("name", name);
            fe.put("size", sizeBytes);
            fe.put("ts", now);
            if (tid != null && tid.length() > 0) fe.put("conv", rootId(tid));
            fileMsgs.add(fe);
            while (fileMsgs.size() > 200) fileMsgs.remove(0);
            for (Emitter em : emitters.values()) {
                em.emit("file", fe);
            }
            return true;
        } catch (Exception ig) {
            return true;
        }
    }

    public JSONArray filesSince(long ts, String conv) {
        JSONArray arr = new JSONArray();
        for (JSONObject f : fileMsgs) {
            if (f.optLong("ts", 0) <= ts) continue;
            String fc = f.optString("conv", "");
            if (conv != null && conv.length() > 0 && fc.length() > 0 && !conv.equals(fc)) continue;
            arr.put(f);
        }
        return arr;
    }

    public String addPerm(String tool, String args) {
        PermEntry e = new PermEntry();
        e.id = "pm" + System.currentTimeMillis() + "x" + permSeq.incrementAndGet();
        e.tool = tool;
        e.args = args;
        e.ts = System.currentTimeMillis();
        synchronized (permLock) {
            perms.put(e.id, e);
        }
        try {
            JSONObject pe = new JSONObject();
            pe.put("id", e.id);
            pe.put("tool", tool);
            pe.put("args", args);
            pe.put("ts", e.ts);
            for (Emitter em : emitters.values()) {
                em.emit("perm", pe);
            }
        } catch (Exception ig) { }
        return e.id;
    }

    public boolean waitPerm(String id, long timeoutMs) {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0L;
        synchronized (permLock) {
            while (deadline == 0 || System.currentTimeMillis() < deadline) {
                PermEntry e = perms.get(id);
                if (e == null) return false;
                if (e.answered) {
                    perms.remove(id);
                    return e.approved;
                }
                try {
                    permLock.wait(1000);
                } catch (InterruptedException ig) {
                    return false;
                }
            }
            PermEntry e = perms.get(id);
            if (e != null && !e.answered) perms.remove(id);
        }
        return false;
    }

    private static String guessMime(String name) {
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase() : "";
        if (ext.equals("mp3")) return "audio/mpeg";
        if (ext.equals("m4a")) return "audio/mp4";
        if (ext.equals("wav")) return "audio/wav";
        if (ext.equals("mp4")) return "video/mp4";
        if (ext.equals("webm")) return "video/webm";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("html") || ext.equals("htm")) return "text/html; charset=utf-8";
        if (ext.equals("css")) return "text/css; charset=utf-8";
        if (ext.equals("js")) return "application/javascript; charset=utf-8";
        if (ext.equals("json")) return "application/json; charset=utf-8";
        if (ext.equals("csv")) return "text/csv; charset=utf-8";
        if (ext.equals("txt") || ext.equals("md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private String dashKey() {
        return Store.loadKeys(ctx).optString("dashscope", "");
    }

    private String dashAsr(byte[] audio) {
        String mime = "audio/mp4";
        if (audio.length > 4) {
            if (audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') {
                mime = "audio/mp3";
            } else if ((audio[0] & 0xFF) == 0xFF && (audio[1] & 0xFF) == 0xFB) {
                mime = "audio/mp3";
            } else if (audio[4] == 'f' && audio[5] == 't' && audio[6] == 'y'
                    && audio[7] == 'p') {
                mime = "audio/mp4";
            } else if (audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F'
                    && audio[3] == 'F') {
                mime = "audio/wav";
            }
        }
        try {
            String key = dashKey();
            if (key.length() == 0) return "";
            String b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP);
            JSONObject req = new JSONObject();
            req.put("model", "qwen3-asr-flash");
            JSONArray msgs = new JSONArray();
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            JSONArray content = new JSONArray();
            JSONObject au = new JSONObject();
            au.put("audio", "data:" + mime + ";base64," + b64);
            content.put(au);
            msg.put("content", content);
            msgs.put(msg);
            req.put("messages", msgs);
            byte[] body = req.toString().getBytes("UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            String resp = readHttp(c);
            try {
                JSONObject o = new JSONObject(resp);
                if (o.has("error")) {
                    Log.e(TAG, "asr api: " + resp.substring(0,
                            Math.min(resp.length(), 300)));
                    return "";
                }
                JSONArray choices = o.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject m0 = choices.getJSONObject(0).optJSONObject("message");
                    if (m0 != null) {
                        String txt = os(m0, "content", "");
                        if (txt.length() > 0) return txt;
                        JSONArray ct = m0.optJSONArray("content");
                        if (ct != null && ct.length() > 0) {
                            JSONObject t0 = ct.optJSONObject(0);
                            if (t0 != null) return os(t0, "text", "");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "asr parse", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "asr", e);
        }
        return "";
    }

    private String dashTts(String text, String voice) {
        long tts0 = System.currentTimeMillis();
        try {
            String key = dashKey();
            if (key.length() == 0 || text.length() == 0) return null;
            if (text.length() > 2000) text = text.substring(0, 2000);
            JSONObject req = new JSONObject();
            req.put("model", "cosyvoice-v2");
            JSONObject input = new JSONObject();
            input.put("text", text);
            input.put("voice", voice);
            input.put("format", "mp3");
            req.put("input", input);
            byte[] body = req.toString().getBytes("UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "tts code=" + code + " " + readHttp(c));
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject resp = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject outObj = resp.optJSONObject("output");
            JSONObject audObj = outObj == null ? null : outObj.optJSONObject("audio");
            String url = os(audObj, "url", "");
            if (url.startsWith("http://")) url = "https://" + url.substring(7);
            if (url.length() == 0) {
                Log.e(TAG, "tts no url: " + resp);
                return null;
            }
            HttpURLConnection d = (HttpURLConnection) new URL(url).openConnection();
            d.setConnectTimeout(8000);
            d.setReadTimeout(60000);
            InputStream din = d.getInputStream();
            String name = UUID.randomUUID().toString() + ".mp3";
            File dst = new File(Store.audioDir(ctx), name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] dbuf = new byte[8192];
            int dn;
            while ((dn = din.read(dbuf)) > 0) out.write(dbuf, 0, dn);
            din.close();
            out.close();
            Log.i(TAG, "tts ok chars=" + text.length()
                    + " ms=" + (System.currentTimeMillis() - tts0));
            return "audio/" + name;
        } catch (Exception e) {
            Log.e(TAG, "tts", e);
            return null;
        }
    }

    private String readHttp(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            if (in != null) in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
