package com.stereo.ai;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class Hooks {
    private static final String TAG = "StereoHooks";
    private static final int TIMEOUT_SEC = 8;
    private static final int MAX_ARGS = 8000;

    public static final String SET_CMD = "hook_pre_tool";
    public static final String SET_MATCH = "hook_pre_tool_match";

    public static boolean enabled(Context c) {
        String cmd = Store.getSetting(c, SET_CMD, "");
        return cmd != null && cmd.trim().length() > 0;
    }

    public static String preTool(Context c, String tool, String argsJson) {
        try {
            String cmd = Store.getSetting(c, SET_CMD, "");
            if (cmd == null || cmd.trim().length() == 0) return null;
            String match = Store.getSetting(c, SET_MATCH, "");
            if (match != null && match.trim().length() > 0) {
                try {
                    if (!tool.matches(match.trim())) return null;
                } catch (Exception badRe) {
                    Log.e(TAG, "bad match regex: " + badRe);
                }
            }
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.redirectErrorStream(false);
            java.util.Map<String, String> env = pb.environment();
            env.put("STEREO_TOOL", tool == null ? "" : tool);
            String a = argsJson == null ? "" : argsJson;
            env.put("STEREO_ARGS", a.length() > MAX_ARGS ? a.substring(0, MAX_ARGS) : a);
            env.put("STEREO_WORK_DIR", Store.workDir(c).getAbsolutePath());
            env.put("STEREO_BUNDLE_DIR", Store.bundleDir(c).getAbsolutePath());
            env.put("STEREO_MEMORY_DIR", MemoryStore.dir(c).getAbsolutePath());
            env.put("STEREO_SKILLS_DIR", Skills.userSkillsDir(c).getAbsolutePath());
            env.put("STEREO_FILES_DIR", c.getFilesDir().getAbsolutePath());
            Process p = pb.start();
            p.getOutputStream().close();
            boolean done = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                try {
                    p.destroy();
                } catch (Exception ig) { }
                return "拦截：钩子脚本超过 " + TIMEOUT_SEC + " 秒未返回，为保证安全已取消本次调用（" + tool + "）。";
            }
            int code = p.exitValue();
            if (code == 0) return null;
            String err = drain(p.getErrorStream());
            String out = drain(p.getInputStream());
            String msg = err.trim().length() > 0 ? err.trim() : out.trim();
            if (msg.length() == 0) msg = "（脚本无输出）";
            if (msg.length() > 2000) msg = msg.substring(0, 2000);
            return "拦截：前置钩子以退出码 " + code + " 拒绝了 " + tool + "。\n" + msg;
        } catch (Exception e) {
            Log.e(TAG, "preTool", e);
            return "拦截：前置钩子执行异常，已取消本次调用（" + tool + "）：" + e;
        }
    }

    private static String drain(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            long total = 0;
            while ((n = in.read(b)) > 0) {
                if (total < 200000) bos.write(b, 0, n);
                total += n;
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String describe(Context c) {
        if (!enabled(c)) return "未启用";
        File f = new File(Store.getSetting(c, SET_CMD, ""));
        return Store.getSetting(c, SET_CMD, "") + (f.isFile() ? " (存在)" : "");
    }
}
