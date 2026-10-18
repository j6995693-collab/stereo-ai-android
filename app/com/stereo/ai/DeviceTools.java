package com.stereo.ai;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class DeviceTools {

    private static boolean hasPerms(Context ctx) {
        return ctx.checkSelfPermission("android.permission.READ_CALENDAR") == 0
                && ctx.checkSelfPermission("android.permission.WRITE_CALENDAR") == 0
                && ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == 0;
    }

    private static String needPerm() {
        return "{\"ok\":false,\"err\":\"缺少日历/定位权限，请在 APP 首页授权后重试\"}";
    }

    private static long calId(Context ctx) {
        Cursor cur = null;
        try {
            cur = ctx.getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI,
                    new String[]{CalendarContract.Calendars._ID},
                    CalendarContract.Calendars.VISIBLE + "=1", null,
                    CalendarContract.Calendars._ID + " ASC");
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (Exception e) {
        } finally {
            if (cur != null) cur.close();
        }
        return 1;
    }

    public static String calList(Context ctx, int days) {
        if (!hasPerms(ctx)) return needPerm();
        if (days <= 0 || days > 60) days = 14;
        long now = System.currentTimeMillis();
        long end = now + days * 86400000L;
        JSONObject out = new JSONObject();
        JSONArray evs = new JSONArray();
        Cursor cur = null;
        try {
            out.put("ok", true);
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, now - 3600000L);
            ContentUris.appendId(b, end);
            cur = ctx.getContentResolver().query(b.build(), new String[]{
                            CalendarContract.Instances._ID,
                            CalendarContract.Instances.TITLE,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.END,
                            CalendarContract.Instances.ALL_DAY,
                            CalendarContract.Instances.EVENT_LOCATION,
                            CalendarContract.Instances.DESCRIPTION},
                    null, null, CalendarContract.Instances.BEGIN + " ASC");
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            SimpleDateFormat dd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            while (cur != null && cur.moveToNext()) {
                JSONObject e = new JSONObject();
                boolean allDay = cur.getInt(4) == 1;
                e.put("id", cur.getLong(0));
                e.put("title", nz(cur, 1));
                e.put("start", allDay ? dd.format(new Date(cur.getLong(2))) : df.format(new Date(cur.getLong(2))));
                e.put("end", allDay ? dd.format(new Date(cur.getLong(3))) : df.format(new Date(cur.getLong(3))));
                e.put("allDay", allDay);
                e.put("loc", nz(cur, 5));
                e.put("desc", nz(cur, 6));
                evs.put(e);
            }
            out.put("events", evs);
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(e)) + "\"}";
        } finally {
            if (cur != null) cur.close();
        }
        return out.toString();
    }

    public static String calAdd(Context ctx, JSONObject a) {
        if (!hasPerms(ctx)) return needPerm();
        try {
            JSONObject out = new JSONObject();
            String title = a.optString("title", "");
            if (title.length() == 0) return "{\"ok\":false,\"err\":\"缺少 title\"}";
            String s = a.optString("start", "");
            if (s.length() == 0) return "{\"ok\":false,\"err\":\"缺少 start（格式 yyyy-MM-dd HH:mm）\"}";
            long start = parseT(s);
            if (start < 0) return "{\"ok\":false,\"err\":\"start 时间格式应为 yyyy-MM-dd HH:mm\"}";
            boolean allDay = a.optBoolean("allDay", false);
            long end;
            String es = a.optString("end", "");
            if (es.length() > 0) {
                end = parseT(es);
                if (end < 0) return "{\"ok\":false,\"err\":\"end 时间格式应为 yyyy-MM-dd HH:mm\"}";
            } else {
                end = start + a.optInt("durationMin", 60) * 60000L;
            }
            ContentValues cv = new ContentValues();
            cv.put(CalendarContract.Events.CALENDAR_ID, calId(ctx));
            cv.put(CalendarContract.Events.TITLE, title);
            cv.put(CalendarContract.Events.DTSTART, start);
            cv.put(CalendarContract.Events.DTEND, end);
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            if (allDay) cv.put(CalendarContract.Events.ALL_DAY, 1);
            if (a.has("loc")) cv.put(CalendarContract.Events.EVENT_LOCATION, a.optString("loc"));
            if (a.has("desc")) cv.put(CalendarContract.Events.DESCRIPTION, a.optString("desc"));
            int remind = a.has("remindMin") ? a.optInt("remindMin", -1) : -1;
            if (remind >= 0) cv.put(CalendarContract.Events.HAS_ALARM, 1);
            Uri u = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, cv);
            long id = u == null ? -1 : ContentUris.parseId(u);
            if (remind >= 0 && id > 0) {
                ContentValues rv = new ContentValues();
                rv.put(CalendarContract.Reminders.EVENT_ID, id);
                rv.put(CalendarContract.Reminders.MINUTES, remind);
                rv.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                ctx.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, rv);
            }
            out.put("ok", true);
            out.put("id", id);
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String calDel(Context ctx, long id) {
        if (!hasPerms(ctx)) return needPerm();
        try {
            if (id <= 0) return "{\"ok\":false,\"err\":\"缺少 id\"}";
            Uri u = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
            int r = ctx.getContentResolver().delete(u, null, null);
            return new JSONObject().put("ok", true).put("deleted", r).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String calUpd(Context ctx, JSONObject a) {
        if (!hasPerms(ctx)) return needPerm();
        try {
            long id = a.optLong("id", -1);
            if (id <= 0) return "{\"ok\":false,\"err\":\"缺少 id\"}";
            ContentValues cv = new ContentValues();
            if (a.has("title")) cv.put(CalendarContract.Events.TITLE, a.optString("title"));
            if (a.has("start") && a.optString("start").length() > 0) {
                long v = parseT(a.optString("start"));
                if (v < 0) return "{\"ok\":false,\"err\":\"start 时间格式应为 yyyy-MM-dd HH:mm\"}";
                cv.put(CalendarContract.Events.DTSTART, v);
            }
            if (a.has("end") && a.optString("end").length() > 0) {
                long v = parseT(a.optString("end"));
                if (v < 0) return "{\"ok\":false,\"err\":\"end 时间格式应为 yyyy-MM-dd HH:mm\"}";
                cv.put(CalendarContract.Events.DTEND, v);
            }
            if (a.has("loc")) cv.put(CalendarContract.Events.EVENT_LOCATION, a.optString("loc"));
            if (a.has("desc")) cv.put(CalendarContract.Events.DESCRIPTION, a.optString("desc"));
            if (cv.size() == 0) return "{\"ok\":false,\"err\":\"没有要更新的字段\"}";
            Uri u = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
            int r = ctx.getContentResolver().update(u, cv, null, null);
            return new JSONObject().put("ok", true).put("updated", r).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String getLoc(Context ctx) {
        if (!hasPerms(ctx)) return needPerm();
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getAllProviders()) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ig) {
                }
            }
            final List<Location> fresh = new ArrayList<Location>();
            LocationListener lis = new LocationListener() {
                public void onLocationChanged(Location l) {
                    synchronized (fresh) {
                        fresh.add(l);
                        fresh.notifyAll();
                    }
                }

                public void onStatusChanged(String p, int s, Bundle x) {
                }

                public void onProviderEnabled(String p) {
                }

                public void onProviderDisabled(String p) {
                }
            };
            for (String p : new String[]{"network", "gps"}) {
                try {
                    if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 0, 0, lis, Looper.getMainLooper());
                } catch (SecurityException ig) {
                }
            }
            long deadline = System.currentTimeMillis() + 8000;
            synchronized (fresh) {
                while (fresh.size() == 0 && System.currentTimeMillis() < deadline) {
                    try {
                        fresh.wait(1000);
                    } catch (InterruptedException ig) {
                    }
                }
            }
            try {
                lm.removeUpdates(lis);
            } catch (Exception ig) {
            }
            if (fresh.size() > 0) {
                Location f = fresh.get(0);
                if (best == null || f.getTime() > best.getTime()) best = f;
            }
            if (best == null) return "{\"ok\":false,\"err\":\"无法获取定位（检查系统定位开关）\"}";
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("lat", best.getLatitude());
            out.put("lon", best.getLongitude());
            out.put("acc", (int) best.getAccuracy());
            out.put("ts", best.getTime());
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    private static String nz(Cursor cur, int i) {
        String v = cur.getString(i);
        return v == null ? "" : v;
    }

    private static long parseT(String s) {
        s = s.trim();
        try {
            SimpleDateFormat df = new SimpleDateFormat(
                    s.length() == 10 ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm", Locale.US);
            df.setTimeZone(TimeZone.getDefault());
            Date d = df.parse(s);
            return d == null ? -1 : d.getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
