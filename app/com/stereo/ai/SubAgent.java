package com.stereo.ai;

import android.content.Context;
import android.util.Log;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SubAgent {
    private static final String TAG = "StereoSubAgent";
    private static final int MAX_LIVE = 4;
    private static final int MAX_KEEP = 40;
    private static final int TURN_TIMEOUT_SEC = 1800;

    public static class Task {
        public String id = "";
        public String threadId = "";
        public String prompt = "";
        public final StringBuilder text = new StringBuilder();
        public volatile String status = "running";
        public volatile String error = "";
        public volatile long startedAt = System.currentTimeMillis();
        public volatile long endedAt = 0L;
        public volatile int nTools = 0;
        public volatile String lastMsg = "";
        volatile String msgId = "";
        volatile boolean msgStreamed = false;
        final CountDownLatch latch = new CountDownLatch(1);
    }

    private static final ConcurrentHashMap<String, Task> byId =
            new ConcurrentHashMap<String, Task>();
    private static final ConcurrentHashMap<String, Task> byThread =
            new ConcurrentHashMap<String, Task>();
    private static final AtomicLong seq = new AtomicLong(1);
    private static volatile CodexClient codex;
    private static volatile Context appCtx;

    public static void init(Context c, CodexClient cc) {
        appCtx = c;
        codex = cc;
    }

    public static int liveCount() {
        int n = 0;
        for (Task t : byId.values()) if ("running".equals(t.status)) n++;
        return n;
    }

    public static synchronized String spawn(String prompt, String model, String provider) {
        try {
            CodexClient cc = codex;
            if (cc == null || !cc.isUp()) return null;
            String p = prompt == null ? "" : prompt.trim();
            if (p.length() == 0) return null;
            if (p.length() > 20000) p = p.substring(0, 20000);
            if (liveCount() >= MAX_LIVE) return null;
            JSONObject r = cc.startThread(model, provider);
            JSONObject thread = r.optJSONObject("thread");
            if (thread == null) return null;
            String tid = storeStr(thread, "id");
            if (tid.length() == 0) return null;
            Store.markCallTid(appCtx, tid);
            Task t = new Task();
            t.id = "a" + seq.getAndIncrement();
            t.threadId = tid;
            t.prompt = p;
            byId.put(t.id, t);
            byThread.put(tid, t);
            cc.armTurnWait(tid);
            JSONObject sr = cc.sendTurn(tid, p);
            if (sr.has("error") || sr.has("timeout")) {
                t.status = "error";
                t.error = sr.has("timeout") ? "启动超时" : storeStr(sr, "error");
                t.endedAt = System.currentTimeMillis();
                t.latch.countDown();
                return t.id;
            }
            Thread w = new Thread(new Runnable() {
                public void run() { finish(t); }
            }, "subagent-" + t.id);
            w.setDaemon(true);
            w.start();
            prune();
            return t.id;
        } catch (Exception e) {
            Log.e(TAG, "spawn", e);
            return null;
        }
    }

    private static void finish(Task t) {
        try {
            int cont = 0;
            while (true) {
                JSONObject wr = codex == null ? new JSONObject()
                        : codex.waitTurn(t.threadId, TURN_TIMEOUT_SEC);
                if (wr.has("timeout")) {
                    t.status = "error";
                    t.error = "子任务超过 " + (TURN_TIMEOUT_SEC / 60) + " 分钟未完成，已放弃";
                    break;
                }
                if (wr.has("error")) {
                    t.status = "error";
                    t.error = String.valueOf(wr.opt("error"));
                    break;
                }
                JSONObject turn = wr.optJSONObject("turn");
                String tst = turn == null ? "" : turn.optString("status", "");
                String lmsg = t.lastMsg;
                boolean promise = lmsg.indexOf(HttpServer.DONE_MARK) < 0
                        && HttpServer.promisesTool(lmsg);
                int cap = t.nTools > 0 ? HttpServer.AUTO_CONTINUE_MAX
                        : HttpServer.AUTO_CONTINUE_STALL_MAX;
                Log.i(TAG, "subagent " + t.id + " turn done status=" + tst
                        + " tools=" + t.nTools + " promise=" + promise + " cont=" + cont);
                if (tst.equals("completed") && promise && cont < cap) {
                    cont++;
                    codex.armTurnWait(t.threadId);
                    JSONObject cr = codex.sendTurn(t.threadId, HttpServer.AUTO_CONTINUE_PROMPT);
                    if (cr.has("error") || cr.has("timeout")) {
                        t.status = "error";
                        t.error = "续跑下发失败";
                        break;
                    }
                    continue;
                }
                if (tst.equals("completed")) {
                    t.status = "done";
                } else {
                    t.status = "error";
                    t.error = "turn " + tst;
                }
                break;
            }
        } catch (Exception e) {
            t.status = "error";
            t.error = String.valueOf(e);
        } finally {
            t.endedAt = System.currentTimeMillis();
            byThread.remove(t.threadId);
            deleteRollout(t.threadId);
            t.latch.countDown();
        }
    }

    private static void deleteRollout(String tid) {
        try {
            if (tid == null || tid.length() != 36 || appCtx == null) return;
            File base = new File(Store.codexHome(appCtx), "sessions");
            deleteRolloutIn(base, tid);
        } catch (Exception e) {
            Log.e(TAG, "deleteRollout", e);
        }
    }

    private static void deleteRolloutIn(File dir, String tid) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                deleteRolloutIn(f, tid);
            } else if (f.getName().contains(tid)) {
                f.delete();
            }
        }
    }

    public static void onEvent(String threadId, String method, JSONObject params) {
        try {
            if (threadId == null || threadId.length() == 0 || params == null) return;
            Task t = byThread.get(threadId);
            if (t == null) return;
            if (method.equals("item/started")) {
                JSONObject item = params.optJSONObject("item");
                if (item != null) {
                    String it = storeStr(item, "type");
                    if ("agentMessage".equals(it)) {
                        t.msgId = storeStr(item, "id");
                        t.msgStreamed = false;
                    } else if ("commandExecution".equals(it) || "mcpToolCall".equals(it)
                            || "fileChange".equals(it)) {
                        t.nTools++;
                    }
                }
            } else if (method.equals("item/agentMessage/delta")) {
                String mid = storeStr(params, "itemId");
                if (mid.length() > 0 && !mid.equals(t.msgId)) {
                    t.msgId = mid;
                    t.msgStreamed = false;
                }
                t.msgStreamed = true;
                synchronized (t.text) {
                    t.text.append(storeStr(params, "delta"));
                }
            } else if (method.equals("item/completed")) {
                JSONObject item = params.optJSONObject("item");
                if (item != null && "agentMessage".equals(storeStr(item, "type"))) {
                    String mid = storeStr(item, "id");
                    String full = storeStr(item, "text");
                    if (full.length() == 0) {
                        JSONArray cont = item.optJSONArray("content");
                        if (cont != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < cont.length(); i++) {
                                JSONObject blk = cont.optJSONObject(i);
                                if (blk != null) sb.append(storeStr(blk, "text"));
                            }
                            full = sb.toString();
                        }
                    }
                    if (full.length() > 0) {
                        t.lastMsg = full;
                        boolean streamed = mid.equals(t.msgId) && t.msgStreamed;
                        if (!streamed) {
                            synchronized (t.text) {
                                if (t.text.length() > 0) t.text.append('\n');
                                t.text.append(full);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onEvent", e);
        }
    }

    public static Task get(String id) {
        return id == null ? null : byId.get(id);
    }

    public static String textOf(Task t) {
        if (t == null) return "";
        synchronized (t.text) {
            String s = t.text.toString().trim();
            if (s.length() > 30000) s = s.substring(0, 30000) + "\n…(过长截断)";
            return s;
        }
    }

    public static String waitText(String id, long timeoutSec) {
        Task t = byId.get(id);
        if (t == null) return null;
        try {
            t.latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ig) { }
        return textOf(t);
    }

    public static String statusOf(String id) {
        Task t = byId.get(id);
        return t == null ? null : t.status;
    }

    public static String report(Task t, boolean withText) {
        if (t == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("id", t.id);
            o.put("status", t.status);
            o.put("prompt", t.prompt.length() > 300 ? t.prompt.substring(0, 300) + "…" : t.prompt);
            o.put("sec", Math.max(0, (t.endedAt > 0 ? t.endedAt : System.currentTimeMillis())
                    - t.startedAt) / 1000);
            if (t.error.length() > 0) o.put("error", t.error);
            if (withText) o.put("result", textOf(t));
        } catch (Exception ig) { }
        return o.toString();
    }

    public static String listAll() {
        JSONArray arr = new JSONArray();
        List<Task> all = new ArrayList<Task>(byId.values());
        java.util.Collections.sort(all, new java.util.Comparator<Task>() {
            public int compare(Task a, Task b) {
                return Long.compare(b.startedAt, a.startedAt);
            }
        });
        for (Task t : all) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("status", t.status);
                o.put("prompt", t.prompt.length() > 120 ? t.prompt.substring(0, 120) + "…" : t.prompt);
                if (t.error.length() > 0) o.put("error", t.error);
                arr.put(o);
            } catch (Exception ig) { }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("running", liveCount());
            out.put("maxLive", MAX_LIVE);
            out.put("tasks", arr);
        } catch (Exception ig) { }
        return out.toString();
    }

    public static boolean interrupt(String id) {
        Task t = byId.get(id);
        if (t == null || codex == null) return false;
        try {
            codex.interrupt(t.threadId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void prune() {
        try {
            List<Task> done = new ArrayList<Task>();
            for (Task t : byId.values()) if (!"running".equals(t.status)) done.add(t);
            if (done.size() <= MAX_KEEP) return;
            java.util.Collections.sort(done, new java.util.Comparator<Task>() {
                public int compare(Task a, Task b) {
                    return Long.compare(a.endedAt, b.endedAt);
                }
            });
            for (int i = 0; i < done.size() - MAX_KEEP; i++) byId.remove(done.get(i).id);
        } catch (Exception ig) { }
    }

    private static String storeStr(JSONObject o, String k) {
        if (o == null) return "";
        Object v = o.opt(k);
        return (v instanceof String) ? (String) v : "";
    }
}
