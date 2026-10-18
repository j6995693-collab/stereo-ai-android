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

public class Skills {
    private static final int MAX_BODY = 60000;

    public static File userSkillsDir(Context c) {
        return new File(c.getFilesDir(), "skills");
    }

    private static List<File> roots(Context c) {
        List<File> rs = new ArrayList<File>();
        rs.add(new File(Store.bundleDir(c), "skills"));
        rs.add(userSkillsDir(c));
        return rs;
    }

    public static File dirOf(Context c, String name) {
        String s = slug(name);
        if (s == null) return null;
        for (File r : roots(c)) {
            File d = new File(r, s);
            if (d.isDirectory()) return d;
        }
        return null;
    }

    public static String slug(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("\\s+", "-");
        return validName(s) ? s : null;
    }

    public static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_\\-]{1,40}");
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

    private static String inlineField(String s, String key) {
        if (s == null) return "";
        for (String line : s.split("\n", 40)) {
            String t = line.trim();
            if (t.startsWith(key + ":")) {
                String v = t.substring(key.length() + 1).trim();
                if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
            if (t.startsWith("#") && t.length() > 2) return t.replaceFirst("^#+\\s*", "");
        }
        return "";
    }

    private static String descriptionOf(File md) {
        String head = readText(md, 4000);
        String d = inlineField(head, "description");
        if (d.length() == 0) d = firstPlainLine(head);
        if (d.length() > 200) d = d.substring(0, 200) + "…";
        return d;
    }

    private static String firstPlainLine(String head) {
        try {
            String[] lines = head.replaceFirst("^---[\\s\\S]*?---\\s*", "").split("\n");
            for (String l : lines) {
                String t = l.trim();
                if (t.length() == 0) continue;
                if (t.startsWith("#")) return t.replaceFirst("^#+\\s*", "");
                return t;
            }
        } catch (Exception ig) { }
        return "";
    }

    public static String list(Context c) {
        JSONArray arr = new JSONArray();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        try {
            int idx = 0;
            for (File root : roots(c)) {
                idx++;
                File[] ds = root.listFiles();
                if (ds == null) continue;
                Arrays.sort(ds);
                for (File d : ds) {
                    if (!d.isDirectory() || d.isHidden() || !validName(d.getName())) continue;
                    if (seen.contains(d.getName())) continue;
                    File md = new File(d, "SKILL.md");
                    if (!md.isFile()) continue;
                    seen.add(d.getName());
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName());
                    o.put("description", descriptionOf(md));
                    o.put("user", idx == 2);
                    arr.put(o);
                }
            }
            JSONObject out = new JSONObject();
            out.put("skills", arr);
            out.put("hint", "命中触发词时先用 skill_use 读取对应技能全文，再严格按其步骤执行。");
            return out.toString();
        } catch (Exception e) {
            return "{\"skills\":[],\"error\":\"" + e + "\"}";
        }
    }

    public static String indexText(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
            int idx = 0;
            for (File root : roots(c)) {
                idx++;
                File[] ds = root.listFiles();
                if (ds == null) continue;
                Arrays.sort(ds);
                for (File d : ds) {
                    if (!d.isDirectory() || d.isHidden() || !validName(d.getName())) continue;
                    if (seen.contains(d.getName())) continue;
                    File md = new File(d, "SKILL.md");
                    if (!md.isFile()) continue;
                    seen.add(d.getName());
                    String desc = descriptionOf(md);
                    if (desc.length() > 160) desc = desc.substring(0, 160) + "…";
                    sb.append("- ").append(d.getName());
                    if (idx == 2) sb.append("（自建）");
                    sb.append("：").append(desc).append('\n');
                    if (sb.length() > 4000) return sb.toString();
                }
            }
        } catch (Exception ig) { }
        return sb.toString();
    }

    public static String use(Context c, String name) {
        File d = dirOf(c, name);
        if (d == null) return "没有名为「" + name + "」的技能。先用 skill_list 查看可用技能。";
        File md = new File(d, "SKILL.md");
        if (!md.isFile()) return "技能 " + name + " 缺少 SKILL.md。";
        StringBuilder sb = new StringBuilder();
        sb.append("# 技能：").append(name).append("\n");
        sb.append("目录：").append(d.getAbsolutePath()).append("\n\n");
        sb.append(readText(md, MAX_BODY));
        sb.append("\n\n---\n该技能目录下的其他文件（需要时直接读取）：\n");
        File[] fs = d.listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            boolean any = false;
            for (File f : fs) {
                if (f.getName().equals("SKILL.md")) continue;
                sb.append("- ").append(f.getName()).append(f.isDirectory() ? "/" : "").append('\n');
                any = true;
                if (sb.length() > MAX_BODY + 4000) break;
            }
            if (!any) sb.append("（无）\n");
        }
        return sb.toString();
    }

    public static String write(Context c, String name, String content) {
        try {
            String sn = slug(name);
            if (sn == null) return "技能名只能用字母、数字、下划线、短横线（1-40 字符）。";
            if (content == null || content.trim().length() == 0) return "内容为空，未写入。";
            File d = new File(userSkillsDir(c), sn);
            if (!d.isDirectory() && !d.mkdirs()) return "无法创建技能目录。";
            File md = new File(d, "SKILL.md");
            FileOutputStream out = new FileOutputStream(md, false);
            out.write(content.getBytes("UTF-8"));
            out.close();
            return "已保存技能「" + name + "」（" + content.length() + " 字符）→ " + md.getAbsolutePath();
        } catch (Exception e) {
            return "保存技能失败: " + e;
        }
    }

    public static String delete(Context c, String name) {
        try {
            String sn = slug(name);
            if (sn == null) return "技能名不合法。";
            File d = new File(userSkillsDir(c), sn);
            if (!d.isDirectory()) {
                if (dirOf(c, sn) != null) return "内置技能不可删除。";
                return "技能不存在：" + name;
            }
            rdelete(d);
            return "已删除技能「" + name + "」。";
        } catch (Exception e) {
            return "删除技能失败: " + e;
        }
    }

    private static void rdelete(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) rdelete(x);
        f.delete();
    }
}
