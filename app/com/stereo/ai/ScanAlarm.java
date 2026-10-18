package com.stereo.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class ScanAlarm extends BroadcastReceiver {
    private static final String TAG = "ScanAlarm";
    static final String ACTION = "com.stereo.ai.SCAN_ALARM";
    static final String ACTION_TEST = "com.stereo.ai.SCAN_SELFTEST";
    private static final int HOUR = 9;
    private static final int MINUTE = 0;
    private static final int REQ = 100, REQ_SHOW = 101, REQ_TEST = 102;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null || i.getAction() == null ? "" : i.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean test = ACTION_TEST.equals(a);

        schedule(c);
        if (boot) {
            Log.i(TAG, "boot event " + a + " -> rescheduled");
            return;
        }
        if (test) {
            Log.i(TAG, "selftest fired");
            Notifier.scanDone(c, "定时通道自检：闹钟已按时触发并唤醒 APP");
            StereoService.startScan(c, "定时通道自检。请只回复一句：自检成功。", false);
            return;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (today.equals(Store.getSetting(c, "scan_last_run", ""))) {
            Log.i(TAG, "already ran today, skip");
            return;
        }
        Log.i(TAG, "daily scan fired at " + new Date());
        StereoService.startScan(c, scanPrompt(c), true);
    }

    static void armRetry(Context c) {
        try {
            int n = 0;
            try {
                n = Integer.parseInt(Store.getSetting(c, "scan_retry", "0"));
            } catch (Throwable ig) { }
            if (n >= 3) {
                Log.i(TAG, "retry exhausted (" + n + "), giving up today");
                Notifier.scanDone(c, "每日扫描：连续 4 次都没能启动，今天已放弃");
                return;
            }
            Store.putSetting(c, "scan_retry", String.valueOf(n + 1));
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + 15 * 60000L;
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, REQ, ACTION));
            Log.i(TAG, "retry #" + (n + 1) + " armed: " + new SimpleDateFormat(
                    "HH:mm", Locale.US).format(new Date(at)));
        } catch (Throwable t) {
            Log.e(TAG, "armRetry", t);
        }
    }

    static String scanPrompt(Context c) {
        String bundle = Store.bundleDir(c).getAbsolutePath();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        java.io.File task = new java.io.File(Store.workDir(c), "scan_task.md");
        if (!task.isFile()) copyTemplate(new java.io.File(bundle, "scan/TASK_TEMPLATE.md"), task);
        String body = readText(task, 12000);
        if (body == null || body.trim().length() == 0 || body.contains("<!-- UNCONFIGURED -->")) {
            return null;
        }
        return body + "\n\n---\n今天日期：" + today + "\n"
                + "这是定时任务，无人值守：禁止向用户提问、禁止等待确认。\n"
                + "严格按上面的任务说明执行；门禁未通过必须立刻停下，并说明卡在哪一道门、原始报错是什么。\n"
                + "完成后最后一条消息必须以单独一行【任务完成】结尾。";
    }

    private static String readText(java.io.File f, int max) {
        if (f == null || !f.isFile()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), max)];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyTemplate(java.io.File src, java.io.File dst) {
        if (src == null || !src.isFile()) return;
        try {
            java.io.File p = dst.getParentFile();
            if (p != null && !p.isDirectory()) p.mkdirs();
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst, false);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "copyTemplate", e);
        }
    }

    static long next(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, HOUR);
        c.set(Calendar.MINUTE, MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now + 2000L) c.add(Calendar.DAY_OF_MONTH, 1);
        while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
                || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = next(System.currentTimeMillis());
            PendingIntent pi = pi(c, REQ, ACTION);
            PendingIntent show = PendingIntent.getActivity(c, REQ_SHOW,
                    new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            } catch (Throwable t) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            Log.i(TAG, "next scan scheduled: " + new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", Locale.US).format(new Date(at)));
        } catch (Throwable t) {
            Log.e(TAG, "schedule", t);
        }
    }

    static void armSelftest(Context c) {
        try {
            if ("1".equals(Store.getSetting(c, "scan_selftest", ""))) return;
            Store.putSetting(c, "scan_selftest", "1");
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + 90000L;
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, REQ_TEST, ACTION_TEST));
            Log.i(TAG, "selftest armed: " + new SimpleDateFormat(
                    "HH:mm:ss", Locale.US).format(new Date(at)));
            logNext7(c);
        } catch (Throwable t) {
            Log.e(TAG, "armSelftest", t);
        }
    }

    static void logNext7(Context c) {
        try {
            if ("1".equals(Store.getSetting(c, "scan_next7", ""))) return;
            Store.putSetting(c, "scan_next7", "1");
            StringBuilder sb = new StringBuilder("next7");
            long base = System.currentTimeMillis();
            SimpleDateFormat f = new SimpleDateFormat("MM-dd EEE HH:mm", Locale.US);
            for (int d = 0; d < 7; d++) {
                sb.append(' ').append(f.format(new Date(next(base + d * 86400000L))));
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "logNext7", t);
        }
    }

    private static PendingIntent pi(Context c, int req, String action) {
        Intent i = new Intent(c, ScanAlarm.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
