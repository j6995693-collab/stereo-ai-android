package com.stereo.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class Notifier {
    private static final String CH = "stereo_done";
    private static volatile long lastAt = 0;

    private static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CH) != null) return;
            NotificationChannel ch = new NotificationChannel(CH, "任务完成提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("AI 在后台完成任务后提醒你");
            nm.createNotificationChannel(ch);
        } catch (Exception e) { }
    }

    static void done(Context c, String text) {
        try {
            if (MainActivity.foreground) return;
            long now = System.currentTimeMillis();
            if (now - lastAt < 3000) return;
            lastAt = now;
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) return;
            channel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String body = text == null ? "" : text.replace("\n", " ").trim();
            if (body.length() == 0) body = "AI 已完成本轮任务";
            if (body.length() > 120) body = body.substring(0, 120) + "…";
            Intent i = new Intent(c, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(c, 1, i, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Stereo AI · 回复完成")
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(1001, b.build());
        } catch (Exception e) { }
    }

    static void scanDone(Context c, String text) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastAt < 3000) return;
            lastAt = now;
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) return;
            channel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String body = text == null ? "" : text.replace("\n", " ").trim();
            if (body.length() == 0) body = "定时任务已执行";
            if (body.length() > 120) body = body.substring(0, 120) + "…";
            Intent i = new Intent(c, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(c, 2, i, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Stereo AI · 定时任务")
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(2001, b.build());
        } catch (Exception e) { }
    }
}
