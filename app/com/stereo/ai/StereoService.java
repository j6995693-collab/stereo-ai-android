package com.stereo.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

public class StereoService extends Service {
    private static final String TAG = "StereoSvc";
    private static final String CH = "stereo_svc";
    static final String EXTRA_PROMPT = "run_prompt";
    static final String EXTRA_MARK = "mark_run";
    private CodexClient codex;
    private HttpServer http;

    private static volatile String scanTid = null;
    private static volatile String scanLastMsg = null;
    private static final java.util.Map<String, Boolean> scanToolIds =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    public static void onCodexEvent(String tid, String method, JSONObject params) {
        try {
            if (tid == null || method == null || !tid.equals(scanTid)) return;
            JSONObject item = params == null ? null : params.optJSONObject("item");
            if (item == null) return;
            String it = item.optString("type", "");
            if (method.equals("item/completed") && it.equals("agentMessage")) {
                String t = item.optString("text", "");
                if (t.length() > 0) scanLastMsg = t;
            } else if (method.equals("item/started")
                    && (it.equals("mcpToolCall") || it.equals("commandExecution")
                    || it.equals("fileChange"))) {
                scanToolIds.put(item.optString("id", it + ":" + System.nanoTime()), Boolean.TRUE);
            }
        } catch (Exception ig) { }
    }

    public static void start(Context c) {
        Intent i = new Intent(c, StereoService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void startScan(Context c, String prompt, boolean markRun) {
        if (prompt == null) {
            Notifier.scanDone(c, "定时任务未配置：请让 AI 把任务写进 files/work/scan_task.md"
                    + "（模板见 bundle/scan/TASK_TEMPLATE.md），配置前不会自动执行");
            return;
        }
        try {
            Intent i = new Intent(c, StereoService.class);
            i.putExtra(EXTRA_PROMPT, prompt);
            i.putExtra(EXTRA_MARK, markRun);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "startScan", t);
            Notifier.scanDone(c, "定时任务：后台服务启动被系统拦截（" + t.getClass().getSimpleName() + "）");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startNotif();
        ScanAlarm.schedule(this);
        ScanAlarm.logNext7(this);
        ScanAlarm.armSelftest(this);
        cleanupLegacyLocal();
        Store.importLegacyKeys(this);
        Store.importLegacyConvs(this);
        Store.writeConfig(this);
        MemoryStore.seed(this);
        codex = new CodexClient(this);
        SubAgent.init(this, codex);
        http = new HttpServer(this, codex);
        McpServer.gate = new McpServer.ToolGate() {
            public boolean approve(String tool, String argsJson, long timeoutMs) {
                String id = http.addPerm(tool, argsJson);
                return http.waitPerm(id, timeoutMs);
            }

            public void notify(String tool, String argsJson, String permId) {
            }
        };
        McpServer.pusher = new McpServer.FilePusher() {
            public boolean push(String path, String name, long sizeBytes) {
                return http.pushFile(path, name, sizeBytes);
            }
        };
        McpServer.start(this);
        ResponseProxy.start(this);
        try {
            http.start();
        } catch (Exception e) {
            Log.e(TAG, "http start fail", e);
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    codex.start();
                } catch (Exception e) {
                    Log.e(TAG, "codex start fail", e);
                }
            }
        }, "codex-boot").start();
        Log.i(TAG, "service up");
    }

    private void startNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Stereo AI 服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Stereo AI")
                .setContentText("AI 服务运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1001, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureScheduler();
        ScanAlarm.schedule(this);
        catchUpScan();
        String p = intent == null ? null : intent.getStringExtra(EXTRA_PROMPT);
        boolean mark = intent != null && intent.getBooleanExtra(EXTRA_MARK, false);
        if (p != null && p.length() > 0) runScanTurn(p, mark);
        return START_STICKY;
    }

    private void catchUpScan() {
        try {
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            if (today.equals(Store.getSetting(this, "scan_last_run", ""))) return;
            java.util.Calendar c = java.util.Calendar.getInstance();
            int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
            if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return;
            int hm = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            if (hm < 9 * 60) return;
            int retry = 0;
            try { retry = Integer.parseInt(Store.getSetting(this, "scan_retry", "0")); } catch (Throwable ig) { }
            if (retry >= 3) return;
            if (today.equals(Store.getSetting(this, "scan_last_catchup", ""))) return;
            Store.putSetting(this, "scan_last_catchup", today);
            Log.i(TAG, "catch-up scan (missed 09:00 alarm)");
            Notifier.scanDone(this, "定时任务：已错过 09:00 定时触发，现在补跑今日任务");
            startScan(this, ScanAlarm.scanPrompt(this), true);
        } catch (Throwable t) {
            Log.e(TAG, "catchUpScan", t);
        }
    }

    private void cleanupLegacyLocal() {
        try {
            java.io.File legacy = new java.io.File(
                    new java.io.File(getFilesDir(), "work"), "local/data");
            if (legacy.isDirectory()) {
                deleteTree(legacy);
                Log.i(TAG, "removed legacy work/local/data tree");
            }
        } catch (Throwable t) {
            Log.e(TAG, "cleanupLegacyLocal", t);
        }
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) deleteTree(k);
        }
        f.delete();
    }

    private void runScanTurn(final String prompt, final boolean mark) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                String tid = null;
                try {
                    for (int i = 0; i < 60 && (codex == null || !codex.isUp()); i++) {
                        Thread.sleep(2000);
                    }
                    if (codex == null || !codex.isUp()) {
                        Notifier.scanDone(StereoService.this, "定时任务：引擎未就绪，15 分钟后重试");
                        if (mark) ScanAlarm.armRetry(StereoService.this);
                        return;
                    }
                    String model = Store.getSetting(StereoService.this, "model", "qwen3.8-max");
                    String provider = Store.getSetting(StereoService.this, "provider", "dashscope");
                    JSONObject r = codex.startThread(model, provider);
                    JSONObject thread = r.optJSONObject("thread");
                    if (thread == null) {
                        Notifier.scanDone(StereoService.this, "定时任务：建会话失败 " + r);
                        if (mark) ScanAlarm.armRetry(StereoService.this);
                        return;
                    }
                    tid = thread.optString("id", "");
                    Store.markCallTid(StereoService.this, tid);
                    scanTid = tid;
                    scanLastMsg = null;
                    scanToolIds.clear();
                    codex.armTurnWait(tid);
                    JSONObject sr = codex.sendTurn(tid, prompt);
                    if (sr.has("error") || sr.has("timeout")) {
                        Notifier.scanDone(StereoService.this, "定时任务：下发失败 "
                                + (sr.has("timeout") ? "超时" : String.valueOf(sr.opt("error"))));
                        if (mark) ScanAlarm.armRetry(StereoService.this);
                        return;
                    }
                    if (mark) {
                        Store.putSetting(StereoService.this, "scan_last_run",
                                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                        .format(new java.util.Date()));
                        Store.putSetting(StereoService.this, "scan_retry", "0");
                    }
                    int cont = 0;
                    boolean finished = false;
                    String endNote = null;
                    while (true) {
                        JSONObject wr = codex.waitTurn(tid, 7200);
                        if (wr.has("timeout")) {
                            endNote = "定时任务：超过 2 小时未完成，已放弃";
                            break;
                        }
                        if (wr.has("error")) {
                            endNote = "定时任务：引擎出错 " + wr.opt("error");
                            break;
                        }
                        JSONObject turn = wr.optJSONObject("turn");
                        String tst = turn == null ? "" : turn.optString("status", "");
                        String lmsg = scanLastMsg;
                        int nTool = scanToolIds.size();
                        boolean done = lmsg != null && lmsg.indexOf(HttpServer.DONE_MARK) >= 0;
                        boolean promise = HttpServer.promisesTool(lmsg);
                        int cap = nTool > 0 ? HttpServer.AUTO_CONTINUE_MAX
                                : HttpServer.AUTO_CONTINUE_STALL_MAX;
                        Log.i(TAG, "scan turn finished tid=" + tid + " status=" + tst
                                + " tools=" + nTool + " done=" + done + " promise=" + promise
                                + " cont=" + cont);
                        if (tst.equals("completed") && !done && (nTool > 0 || promise)
                                && cont < cap) {
                            cont++;
                            Log.i(TAG, "scan auto-continue #" + cont + " tid=" + tid);
                            codex.armTurnWait(tid);
                            JSONObject cr = codex.sendTurn(tid, HttpServer.AUTO_CONTINUE_PROMPT);
                            if (cr.has("error") || cr.has("timeout")) {
                                endNote = "定时任务：续跑下发失败，请打开 APP 查看";
                                break;
                            }
                            continue;
                        }
                        finished = done;
                        if (!done) {
                            endNote = "定时任务已结束，但没有收到完成标记（可能中途停了），"
                                    + "请打开 APP 查看对话并追问";
                        }
                        break;
                    }
                    scanTid = null;
                    if (endNote != null) {
                        Notifier.scanDone(StereoService.this, endNote);
                    } else if (cont > 0) {
                        Notifier.scanDone(StereoService.this, "定时任务已完成（自动续跑 " + cont
                                + " 次），可打开 APP 查看结果");
                    } else {
                        Notifier.scanDone(StereoService.this, "定时任务已完成，可打开 APP 查看结果");
                    }
                    if (finished && mark) {
                        Log.i(TAG, "scan completed with done mark tid=" + tid);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "runScanTurn", e);
                    Notifier.scanDone(StereoService.this, "定时任务异常：" + e);
                    if (mark) ScanAlarm.armRetry(StereoService.this);
                }
            }
        }, "scan-turn");
        t.setDaemon(true);
        t.start();
    }

    private void ensureScheduler() {
        try {
            java.io.File bf = new java.io.File(new java.io.File(getFilesDir(), "work"), "schedule_boot.sh");
            if (bf.isFile()) {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", bf.getAbsolutePath());
                pb.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "schedule boot fail", e);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (http != null) http.stop();
        } catch (Exception e) { }
        try {
            if (codex != null) codex.stop();
        } catch (Exception e) { }
        McpServer.stop();
        Log.i(TAG, "service down");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
