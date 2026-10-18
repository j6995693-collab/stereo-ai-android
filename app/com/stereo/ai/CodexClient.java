package com.stereo.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class CodexClient {
    public interface Listener {
        void onNotification(String method, JSONObject params);

        void onDead();
    }

    private static final String TAG = "CodexClient";
    private final Context ctx;
    private Process proc;
    private OutputStream stdin;
    private final AtomicLong seq = new AtomicLong(1);
    private final Map<Long, Pending> pending = new HashMap<Long, Pending>();
    private final Object lock = new Object();
    private volatile Listener listener;
    private volatile boolean running = false;
    private volatile boolean initialized = false;

    private static class Pending {
        JSONObject result;
        JSONObject error;
        boolean done;
    }

    public CodexClient(Context c) {
        ctx = c;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        Store.writeConfig(ctx);
        String lib = ctx.getApplicationInfo().nativeLibraryDir;
        File bin = new File(lib, "libcodex.so");
        if (!bin.isFile()) throw new Exception("codex binary missing: " + bin);
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "app-server");
        Map<String, String> env = pb.environment();
        env.put("CODEX_HOME", Store.codexHome(ctx).getAbsolutePath());
        env.put("HOME", ctx.getFilesDir().getAbsolutePath());
        env.put("SHELL", "/system/bin/sh");
        env.put("PATH", "/system/bin:/system/xbin");
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        env.put("STEREO_BUNDLE_DIR", Store.bundleDir(ctx).getAbsolutePath());
        env.put("CODEX_DISABLE_UPDATER", "1");
        env.put("RUST_LOG", "codex_mcp=debug");
        JSONObject keys = Store.loadKeys(ctx);
        Iterator<String> it = keys.keys();
        while (it.hasNext()) {
            String p = it.next();
            env.put(Store.envForKey(p), keys.optString(p));
        }
        pb.directory(Store.workDir(ctx));
        pb.redirectErrorStream(false);
        proc = pb.start();
        running = true;
        initialized = false;
        stdin = proc.getOutputStream();
        Thread rt = new Thread(new Runnable() {
            public void run() { readerLoop(); }
        }, "codex-reader");
        rt.setDaemon(true);
        rt.start();
        Thread wt = new Thread(new Runnable() {
            public void run() { waitLoop(); }
        }, "codex-wait");
        wt.setDaemon(true);
        wt.start();
        Thread et = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader er = new BufferedReader(
                            new InputStreamReader(proc.getErrorStream(), "UTF-8"));
                    String el;
                    while (running && (el = er.readLine()) != null) {
                        if (el.length() > 0) {
                            Log.w("CodexErr", el.length() > 400
                                    ? el.substring(0, 400) : el);
                        }
                    }
                } catch (Exception e) { }
            }
        }, "codex-stderr");
        et.setDaemon(true);
        et.start();
        JSONObject init = new JSONObject();
        JSONObject ci = new JSONObject();
        ci.put("name", "stereo-ai");
        ci.put("title", "Stereo AI");
        ci.put("version", "3.0");
        init.put("clientInfo", ci);
        JSONObject caps = new JSONObject();
        caps.put("experimentalApi", true);
        init.put("capabilities", caps);
        JSONObject r = call("initialize", init, 30);
        if (r.has("error")) throw new Exception("codex initialize failed: " + r);
        initialized = true;
        Log.i(TAG, "codex app-server up");
    }

    public boolean isUp() {
        return running && initialized && proc != null;
    }

    public synchronized void stop() {
        running = false;
        initialized = false;
        try {
            if (stdin != null) stdin.close();
        } catch (Exception e) { }
        try {
            if (proc != null) proc.destroy();
        } catch (Exception e) { }
        proc = null;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void readerLoop() {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"), 1 << 16);
            String line;
            while (running && (line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject m;
                try {
                    m = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                handleMessage(m);
            }
        } catch (Exception e) {
            Log.i(TAG, "reader exit: " + e);
        }
    }

    private void handleMessage(JSONObject m) {
        String method = m.optString("method", null);
        boolean hasId = m.has("id");
        if (method == null && hasId) {
            long id = m.optLong("id", -1);
            Pending p;
            synchronized (pending) {
                p = pending.remove(id);
            }
            if (p != null) {
                p.result = m.optJSONObject("result");
                p.error = m.optJSONObject("error");
                p.done = true;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
            return;
        }
        if (method != null && hasId) {
            handleServerRequest(m);
            return;
        }
        if (method != null) {
            JSONObject params = m.optJSONObject("params");
            if (method.equals("turn/completed")) {
                onTurnSignal(params, false);
            } else if (method.equals("turn/failed")) {
                onTurnSignal(params, true);
            } else if (method.equals("error")) {
                if (params != null && !params.optBoolean("willRetry", false)) {
                    onTurnSignal(params, true);
                }
            }
            Listener l = listener;
            if (l != null) {
                try {
                    l.onNotification(method, params);
                } catch (Exception e) {
                    Log.e(TAG, "notif dispatch", e);
                }
            }
        }
    }

    private final java.util.Map<String, Object[]> turnWaits =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>();

    public void armTurnWait(String threadId) {
        turnWaits.put(threadId, new Object[]{
                new java.util.concurrent.CountDownLatch(1), null});
    }

    public JSONObject waitTurn(String threadId, long timeoutSec) {
        Object[] w = turnWaits.get(threadId);
        if (w == null) return errObj(new Exception("no armed wait"));
        try {
            if (!((java.util.concurrent.CountDownLatch) w[0]).await(
                    timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                turnWaits.remove(threadId);
                JSONObject t = new JSONObject();
                t.put("timeout", true);
                return t;
            }
        } catch (org.json.JSONException e) {
            return errObj(e);
        } catch (InterruptedException e) {
            turnWaits.remove(threadId);
            return errObj(e);
        }
        turnWaits.remove(threadId);
        JSONObject r = (JSONObject) w[1];
        return r == null ? errObj(new Exception("empty turn result")) : r;
    }

    private void onTurnSignal(JSONObject params, boolean failed) {
        if (params == null) return;
        String tid = params.optString("threadId", params.optString("turnId", ""));
        if (tid.length() == 0) return;
        Object[] w = turnWaits.get(tid);
        if (w == null || w[1] != null) return;
        try {
            JSONObject r = new JSONObject();
            if (failed) {
                JSONObject err = params.optJSONObject("error");
                r.put("error", err != null ? err.optString("message", "turn failed")
                        : params.optString("message", "turn failed"));
            } else {
                r.put("turn", params.optJSONObject("turn"));
            }
            w[1] = r;
        } catch (Exception e) { }
        ((java.util.concurrent.CountDownLatch) w[0]).countDown();
    }

    private void handleServerRequest(JSONObject m) {
        String method = m.optString("method", "");
        long id = m.optLong("id", -1);
        Log.i(TAG, "server request: " + method);
        JSONObject res = new JSONObject();
        try {
            if (method.endsWith("requestApproval")) {
                res.put("decision", "decline");
            } else if (method.contains("elicitation")) {
                res.put("action", "decline");
            } else if (method.contains("requestUserInput")) {
                JSONArray qs = m.optJSONObject("params") == null ? new JSONArray()
                        : m.optJSONObject("params").optJSONArray("questions");
                JSONArray answers = new JSONArray();
                if (qs != null) {
                    for (int i = 0; i < qs.length(); i++) {
                        answers.put(new JSONObject().put("id", qs.getJSONObject(i).optString("id"))
                                .put("answer", "Deny"));
                    }
                }
                res.put("answers", answers);
            } else {
                res.put("ok", false);
            }
        } catch (Exception e) { }
        try {
            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("result", res);
            sendRaw(resp);
        } catch (Exception e) {
            Log.e(TAG, "server req reply fail", e);
        }
    }

    private void waitLoop() {
        try {
            proc.waitFor();
        } catch (Exception e) { }
        if (!running) return;
        running = false;
        initialized = false;
        Log.w(TAG, "codex process died");
        Listener l = listener;
        if (l != null) l.onDead();
    }

    private void sendRaw(JSONObject o) throws Exception {
        synchronized (lock) {
            if (stdin == null) throw new Exception("codex not running");
            stdin.write((o.toString() + "\n").getBytes("UTF-8"));
            stdin.flush();
        }
    }

    public JSONObject call(String method, JSONObject params, int timeoutSec) {
        long id = seq.getAndIncrement();
        Pending p = new Pending();
        synchronized (pending) {
            pending.put(id, p);
        }
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            req.put("params", params == null ? new JSONObject() : params);
            sendRaw(req);
        } catch (Exception e) {
            synchronized (pending) {
                pending.remove(id);
            }
            try {
                JSONObject err = new JSONObject();
                err.put("sendError", String.valueOf(e));
                return err;
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        synchronized (lock) {
            while (!p.done && System.currentTimeMillis() < deadline && running) {
                try {
                    lock.wait(500);
                } catch (InterruptedException ig) {
                    break;
                }
            }
        }
        if (!p.done) {
            synchronized (pending) {
                pending.remove(id);
            }
            try {
                JSONObject err = new JSONObject();
                err.put("timeout", true);
                return err;
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
        JSONObject out = new JSONObject();
        try {
            if (p.result != null) out = p.result;
            if (p.error != null) {
                out.put("error", p.error);
            }
        } catch (Exception e) { }
        return out;
    }

    public JSONObject startThread(String model, String provider) {
        try {
            JSONObject p = new JSONObject();
            p.put("cwd", Store.workDir(ctx).getAbsolutePath());
            if (model != null && model.length() > 0) p.put("model", model);
            if (provider != null && provider.length() > 0) p.put("modelProvider", provider);
            p.put("approvalPolicy", "never");
            p.put("sessionStartSource", "startup");
            return call("thread/start", p, 30);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject resumeThread(String threadId) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            p.put("cwd", Store.workDir(ctx).getAbsolutePath());
            p.put("approvalPolicy", "never");
            return call("thread/resume", p, 30);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject sendTurn(String threadId, String text) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            JSONArray input = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("type", "text");
            item.put("text", text);
            input.put(item);
            p.put("input", input);
            p.put("approvalPolicy", "never");
            return call("turn/start", p, 25);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject updateThreadModel(String threadId, String model) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            p.put("model", model);
            return call("thread/settings/update", p, 20);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject forkThread(String threadId, String model, String provider) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            if (model != null && model.length() > 0) p.put("model", model);
            if (provider != null && provider.length() > 0) p.put("modelProvider", provider);
            p.put("cwd", Store.workDir(ctx).getAbsolutePath());
            p.put("approvalPolicy", "never");
            return call("thread/fork", p, 30);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject interrupt(String threadId) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            return call("turn/interrupt", p, 15);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject listThreads() {
        try {
            JSONObject p = new JSONObject();
            return call("thread/list", p, 20);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject readThread(String threadId) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            p.put("itemsView", "loaded");
            return call("thread/read", p, 25);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    public JSONObject archiveThread(String threadId) {
        try {
            JSONObject p = new JSONObject();
            p.put("threadId", threadId);
            return call("thread/archive", p, 15);
        } catch (Exception e) {
            return errObj(e);
        }
    }

    private static JSONObject errObj(Exception e) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", String.valueOf(e));
        } catch (Exception ig) { }
        return o;
    }
}
