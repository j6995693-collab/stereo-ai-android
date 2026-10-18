package com.stereo.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MemoryStore {
    private static final int MAX_FILE = 40000;
    private static final int MAX_INDEX = 200;

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "memory");
    }

    private static File indexFile(Context c) {
        return new File(dir(c), "MEMORY.md");
    }

    public static synchronized void seed(Context c) {
        try {
            File d = dir(c);
            if (d.isDirectory()) return;
            File src = new File(Store.bundleDir(c), "memory");
            if (!src.isDirectory()) return;
            if (!d.mkdirs() && !d.isDirectory()) return;
            copyDir(src, d);
        } catch (Exception e) {
            android.util.Log.e("StereoMemory", "seed", e);
        }
    }

    private static void copyDir(File src, File dst) throws Exception {
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) {
                if (t.mkdirs() || t.isDirectory()) copyDir(f, t);
            } else {
                FileInputStream in = new FileInputStream(f);
                FileOutputStream out = new FileOutputStream(t, false);
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close();
                out.close();
            }
        }
    }

    private static String readText(File f, int max) {
        try {
            FileInputStream in = new FileInputStream(f);
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
            return "";
        }
    }

    private static void writeText(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        FileOutputStream out = new FileOutputStream(f, false);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }

    public static String slug(String name) {
        String s = name == null ? "" : name.trim();
        if (s.endsWith(".md")) s = s.substring(0, s.length() - 3);
        s = s.replaceAll("\\s+", "-");
        return validName(s) ? s : null;
    }

    public static boolean validName(String n) {
        return n != null && n.matches("[\\p{L}\\p{N}_\\-]{1,60}");
    }

    private static File fileOf(Context c, String name) {
        String s = slug(name);
        if (s == null) return null;
        return new File(dir(c), s + ".md");
    }

    public static String list(Context c) {
        try {
            seed(c);
            File idx = indexFile(c);
            String body = idx.isFile() ? readText(idx, MAX_INDEX * 400).trim() : "";
            File[] fs = dir(c).listFiles();
            JSONArray files = new JSONArray();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    if (!f.isFile() || !f.getName().endsWith(".md")) continue;
                    if (f.getName().equals("MEMORY.md")) continue;
                    files.put(f.getName());
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("记忆索引（memory_list 只给索引；需要细节再用 memory_read 取正文）\n");
            sb.append("共 ").append(files.length()).append(" 条记忆\n\n");
            sb.append(body.length() == 0 ? "（尚无索引条目）" : body);
            if (files.length() > 0) {
                sb.append("\n\n磁盘上的记忆文件：");
                for (int i = 0; i < files.length(); i++) sb.append(files.optString(i)).append(' ');
            }
            sb.append("\n路径：").append(dir(c).getAbsolutePath());
            return sb.toString();
        } catch (Exception e) {
            return "读取记忆索引失败: " + e;
        }
    }

    public static String indexText(Context c) {
        try {
            seed(c);
            File idx = indexFile(c);
            if (!idx.isFile()) return "";
            String body = readText(idx, MAX_INDEX * 400).trim();
            if (body.length() > 6000) body = body.substring(0, 6000) + "\n…（索引过长已截断）";
            return body;
        } catch (Exception e) {
            return "";
        }
    }

    public static String read(Context c, String name) {
        seed(c);
        File f = fileOf(c, name);
        if (f == null) return "记忆名不合法（只能用中英文、数字、下划线、短横线）。";
        if (!f.isFile()) {
            if (new File(dir(c), "MEMORY.md").isFile()) {
                return "没有名为「" + name + "」的记忆。当前索引：\n" + list(c);
            }
            return "没有名为「" + name + "」的记忆。";
        }
        return readText(f, MAX_FILE);
    }

    public static String write(Context c, String name, String title, String hook,
                               String type, String content) {
        try {
            seed(c);
            File f = fileOf(c, name);
            if (f == null) return "记忆名不合法（只能用中英文、数字、下划线、短横线，1-60 字）。";
            String body = content == null ? "" : content.trim();
            if (body.length() == 0) return "内容为空，未写入。";
            if (body.length() > MAX_FILE) body = body.substring(0, MAX_FILE);
            String slug = f.getName().substring(0, f.getName().length() - 3);
            String t = title == null || title.trim().length() == 0 ? slug : title.trim();
            String ty = type == null || type.trim().length() == 0 ? "project" : type.trim();
            String hk = hook == null ? "" : hook.trim();
            if (hk.length() == 0) hk = firstLine(body);

            String oldFile = f.isFile() ? readText(f, 8000) : "";
            String oldHook = indexHookFor(c, slug);
            if (oldHook != null && hk.length() == 0) hk = oldHook;
            if (oldFile.length() == 0) {
                // new memory
            }

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(slug).append('\n');
            sb.append("description: ").append(hk).append('\n');
            sb.append("metadata:\n  type: ").append(ty).append('\n');
            sb.append("---\n\n");
            sb.append(body).append('\n');
            writeText(f, sb.toString());

            upsertIndex(c, t, slug + ".md", hk);
            return (oldFile.length() == 0 ? "已新建记忆「" : "已更新记忆「") + t + "」→ "
                    + f.getAbsolutePath() + "\n索引 MEMORY.md 已同步。";
        } catch (Exception e) {
            return "写入记忆失败: " + e;
        }
    }

    public static String delete(Context c, String name) {
        try {
            File f = fileOf(c, name);
            if (f == null) return "记忆名不合法。";
            if (!f.isFile()) return "记忆不存在：" + name;
            String slug = f.getName().substring(0, f.getName().length() - 3);
            f.delete();
            removeIndex(c, slug + ".md");
            return "已删除记忆「" + name + "」并更新索引。";
        } catch (Exception e) {
            return "删除记忆失败: " + e;
        }
    }

    private static String firstLine(String body) {
        for (String l : body.split("\n")) {
            String s = l.trim();
            if (s.length() == 0) continue;
            s = s.replaceFirst("^#+\\s*", "");
            s = s.replaceFirst("^[-*]\\s*", "");
            return s.length() > 140 ? s.substring(0, 140) + "…" : s;
        }
        return "";
    }

    private static String indexHookFor(Context c, String slug) {
        try {
            String s = readText(indexFile(c), MAX_INDEX * 400);
            String needle = "](" + slug + ".md)";
            for (String l : s.split("\n")) {
                int i = l.indexOf(needle);
                if (i < 0) continue;
                int d = l.indexOf(" — ");
                return d > i ? l.substring(d + 3).trim() : l.trim();
            }
        } catch (Exception ig) { }
        return null;
    }

    private static void upsertIndex(Context c, String title, String file, String hook) throws Exception {
        List<String> keep = new ArrayList<String>();
        String cur = indexFile(c).isFile() ? readText(indexFile(c), MAX_INDEX * 400) : "";
        String needle = "](" + file + ")";
        for (String l : cur.split("\n")) {
            if (l.indexOf(needle) >= 0) continue;
            if (l.trim().length() == 0) continue;
            keep.add(l);
        }
        keep.add("- [" + title + "](" + file + ") — " + hook);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(keep.get(i));
        }
        sb.append('\n');
        writeText(indexFile(c), sb.toString());
    }

    private static void removeIndex(Context c, String file) throws Exception {
        if (!indexFile(c).isFile()) return;
        String cur = readText(indexFile(c), MAX_INDEX * 400);
        String needle = "](" + file + ")";
        StringBuilder sb = new StringBuilder();
        for (String l : cur.split("\n")) {
            if (l.indexOf(needle) >= 0 || l.trim().length() == 0) continue;
            sb.append(l).append('\n');
        }
        writeText(indexFile(c), sb.toString());
    }
}
