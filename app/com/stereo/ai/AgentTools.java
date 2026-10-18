package com.stereo.ai;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AgentTools {
    private static final String DASH = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final int MAX_READ = 400000;
    private static final int MAX_FETCH = 60000;

    private static String key(Context c) {
        return Store.loadKeys(c).optString("dashscope", "");
    }

    private static String post(String url, String key, JSONObject body, int readMs) {
        try {
            byte[] payload = body.toString().getBytes("UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "application/json");
            if (key != null && key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.close();
            int code = conn.getResponseCode();
            InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return "HTTP " + code;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "网络错误: " + e;
        }
    }

    private static String pick(String resp, String fallback) {
        try {
            JSONObject o = new JSONObject(resp);
            JSONObject err = o.optJSONObject("error");
            if (err != null) return "模型接口报错: " + err.optString("message", err.toString());
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return fallback;
            JSONObject m = ch.optJSONObject(0).optJSONObject("message");
            if (m == null) return fallback;
            String s = m.optString("content", "");
            if (s.length() == 0) s = m.optString("reasoning_content", "");
            return s.length() == 0 ? fallback : s;
        } catch (Exception e) {
            return resp;
        }
    }

    private static String mimeOf(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".bmp")) return "image/bmp";
        if (l.endsWith(".heic") || l.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    static String readImage(Context c, String path, String question) {
        try {
            File f = new File(path);
            if (!f.isFile()) return "图片不存在: " + path;
            if (f.length() > 12L * 1024 * 1024) return "图片超过 12MB，请先压缩后再读: " + path;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String k = key(c);
            if (k.length() == 0) return "未配置 DashScope(阿里百炼) API Key，无法识图。请在设置里填入 Key。";
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            String q = question == null || question.trim().length() == 0
                    ? "详细描述这张图片：主体、场景、可见文字（原文照抄）、数字与关键细节。中文回答。"
                    : question;
            JSONObject req = new JSONObject();
            req.put("model", "qwen3-vl-plus");
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject()
                            .put("url", "data:" + mimeOf(f.getName()) + ";base64," + b64)));
            content.put(new JSONObject().put("type", "text").put("text", q));
            m.put("content", content);
            msgs.put(m);
            req.put("messages", msgs);
            String resp = post(DASH, k, req, 180000);
            return pick(resp, "识图未返回内容: " + resp);
        } catch (Exception e) {
            return "识图失败: " + e;
        }
    }

    static String readPdf(Context c, String path, int maxPages, String question) {
        android.os.ParcelFileDescriptor pfd = null;
        android.graphics.pdf.PdfRenderer r = null;
        try {
            File f = new File(path);
            if (!f.isFile()) return "PDF 不存在: " + path;
            String k = key(c);
            if (k.length() == 0) return "未配置 DashScope(阿里百炼) API Key，无法解析 PDF。";
            pfd = android.os.ParcelFileDescriptor.open(f,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            r = new android.graphics.pdf.PdfRenderer(pfd);
            int total = r.getPageCount();
            if (total == 0) return "这个 PDF 没有页面";
            int n = Math.min(total, Math.max(1, Math.min(maxPages, 8)));
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text",
                    (question == null || question.trim().length() == 0
                            ? "逐页转录并总结这份 PDF：先给每页要点，再给整体结论。"
                            : question)
                            + "（共 " + total + " 页，这里给出前 " + n + " 页）"));
            for (int i = 0; i < n; i++) {
                android.graphics.pdf.PdfRenderer.Page pg = r.openPage(i);
                int pw = pg.getWidth(), ph = pg.getHeight();
                int scale = Math.max(1, 1600 / Math.max(1, pw));
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        pw * scale, ph * scale, android.graphics.Bitmap.Config.ARGB_8888);
                bmp.eraseColor(android.graphics.Color.WHITE);
                pg.render(bmp, null, null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pg.close();
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, jpg);
                byte[] px = jpg.toByteArray();
                if (px.length > 4 * 1024 * 1024) {
                    ByteArrayOutputStream jpg2 = new ByteArrayOutputStream();
                    android.graphics.Bitmap b2 = android.graphics.Bitmap.createScaledBitmap(
                            bmp, Math.max(1, bmp.getWidth() / 2),
                            Math.max(1, bmp.getHeight() / 2), true);
                    b2.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, jpg2);
                    b2.recycle();
                    px = jpg2.toByteArray();
                }
                bmp.recycle();
                if (px.length > 4 * 1024 * 1024) {
                    content.put(new JSONObject().put("type", "text")
                            .put("text", "（第 " + (i + 1) + " 页图像过大，已跳过）"));
                    continue;
                }
                content.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url",
                                "data:image/jpeg;base64,"
                                        + Base64.encodeToString(px, Base64.NO_WRAP))));
            }
            JSONObject req = new JSONObject();
            req.put("model", "qwen3-vl-plus");
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", content));
            req.put("messages", msgs);
            String resp = post(DASH, k, req, 300000);
            String out = pick(resp, "解析未返回内容: " + resp);
            if (out.length() > 200000) out = out.substring(0, 200000) + "\n…（已截断）";
            return out;
        } catch (Exception e) {
            return "解析 PDF 失败: " + e;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ig) { }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ig) { }
        }
    }

    static String webSearch(Context c, String query, int n) {
        try {
            String k = key(c);
            if (k.length() == 0) return "未配置 DashScope(阿里百炼) API Key，无法联网检索。";
            JSONObject req = new JSONObject();
            req.put("model", "qwen-plus");
            req.put("enable_search", true);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user")
                    .put("content", "联网检索并回答（要求给出信息来源站点名，不要编造）：" + query
                            + "\n最多列出 " + n + " 条要点。"));
            req.put("messages", msgs);
            String resp = post(DASH, k, req, 120000);
            return pick(resp, "检索未返回内容: " + resp);
        } catch (Exception e) {
            return "检索失败: " + e;
        }
    }

    static String webFetch(String url, int maxChars) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "只支持 http/https 网址";
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(40000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return "HTTP " + code;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            int cap = Math.min(MAX_FETCH, Math.max(4096, maxChars)) * 4;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > cap) break;
            }
            in.close();
            String body = new String(bos.toByteArray(), "UTF-8");
            String text = stripHtml(body);
            int lim = Math.min(MAX_FETCH, Math.max(1024, maxChars));
            if (text.length() > lim) text = text.substring(0, lim) + "\n…（已截断）";
            return "HTTP " + code + "\n" + text;
        } catch (Exception e) {
            return "抓取失败: " + e;
        }
    }

    private static String stripHtml(String h) {
        StringBuilder sb = new StringBuilder(h.length() / 2);
        boolean inTag = false, inScript = false;
        String low = h.toLowerCase();
        for (int i = 0; i < h.length(); i++) {
            char ch = h.charAt(i);
            if (!inTag && ch == '<') {
                if (low.startsWith("<script", i) || low.startsWith("<style", i)) inScript = true;
                if (low.startsWith("</script", i) || low.startsWith("</style", i)) inScript = false;
                inTag = true;
                continue;
            }
            if (inTag) {
                if (ch == '>') {
                    inTag = false;
                    if (!inScript) sb.append(' ');
                }
                continue;
            }
            if (inScript) continue;
            sb.append(ch);
        }
        String s = sb.toString()
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        String[] lines = s.split("\n");
        StringBuilder out = new StringBuilder();
        for (String ln : lines) {
            String t = ln.trim().replaceAll("[ \\t\\u00a0]{2,}", " ");
            if (t.length() == 0) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    static File safeResolve(Context c, String path) throws Exception {
        File f = new File(path);
        if (!f.isAbsolute()) f = new File(Store.workDir(c), path);
        String cp = f.getCanonicalPath();
        String home = c.getFilesDir().getCanonicalPath();
        if (!cp.startsWith(home + File.separator) && !cp.startsWith("/storage/")) {
            throw new Exception("路径越界: " + path);
        }
        return f;
    }

    static String editFile(Context c, String path, String oldS, String newS, boolean all) {
        try {
            if (oldS == null || oldS.length() == 0) return "old 不能为空";
            if (oldS.equals(newS)) return "old 与 new 相同，无需修改";
            File f = safeResolve(c, path);
            if (!f.isFile()) return "文件不存在: " + path;
            if (f.length() > MAX_READ) return "文件过大（>" + (MAX_READ / 1024) + "KB），请用 shell 处理: " + path;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String src = new String(bos.toByteArray(), "UTF-8");
            int count = 0, idx = 0;
            while (true) {
                int p = src.indexOf(oldS, idx);
                if (p < 0) break;
                count++;
                idx = p + oldS.length();
                if (!all) break;
            }
            if (count == 0) return "未找到要替换的内容（old 必须与文件中的文本逐字一致，含缩进）";
            if (count > 1 && !all) {
                return "old 在文件中出现 " + count + " 次，不唯一，编辑已取消。"
                        + "请扩大上下文使其唯一，或设置 replaceAll=true 全部替换。";
            }
            String outS = all ? src.replace(oldS, newS) : replaceOnce(src, oldS, newS);
            FileOutputStream out = new FileOutputStream(f);
            out.write(outS.getBytes("UTF-8"));
            out.close();
            return "已修改 " + f.getAbsolutePath() + "（替换 " + count + " 处）";
        } catch (Exception e) {
            return "编辑失败: " + e;
        }
    }

    private static String replaceOnce(String src, String oldS, String newS) {
        int p = src.indexOf(oldS);
        if (p < 0) return src;
        return src.substring(0, p) + newS + src.substring(p + oldS.length());
    }

    static String fileGlob(Context c, String dir, String pattern, int limit) {
        try {
            File root = (dir == null || dir.trim().length() == 0)
                    ? Store.workDir(c) : safeResolve(c, dir);
            if (!root.isDirectory()) return "目录不存在: " + root.getAbsolutePath();
            List<String> hits = new ArrayList<String>();
            globWalk(root, root, pattern == null || pattern.length() == 0 ? "*" : pattern, hits, limit);
            if (hits.isEmpty()) return "没有匹配 " + pattern + " 的文件（目录 " + root.getAbsolutePath() + "）";
            StringBuilder sb = new StringBuilder();
            for (String h : hits) sb.append(h).append('\n');
            return sb.toString().trim();
        } catch (Exception e) {
            return "查找失败: " + e;
        }
    }

    private static void globWalk(File root, File d, String pattern, List<String> out, int limit) {
        if (out.size() >= limit) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        String regex = globToRegex(pattern);
        for (File f : fs) {
            if (out.size() >= limit) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                globWalk(root, f, pattern, out, limit);
            } else if (name.matches(regex)) {
                String rel;
                try {
                    rel = root.toURI().relativize(f.toURI()).getPath();
                } catch (Exception e) {
                    rel = f.getAbsolutePath();
                }
                out.add(rel + "  (" + (f.length() / 1024) + "KB)");
            }
        }
    }

    static String globToRegex(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char ch = g.charAt(i);
            if (ch == '*') sb.append("[^/]*");
            else if (ch == '?') sb.append("[^/]");
            else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) sb.append('\\').append(ch);
            else sb.append(ch);
        }
        return sb.toString();
    }

    static String fileGrep(Context c, String path, String pattern, String nameGlob, int limit) {
        try {
            if (pattern == null || pattern.length() == 0) return "pattern 不能为空";
            File root = safeResolve(c, path);
            if (!root.exists()) return "路径不存在: " + path;
            java.util.regex.Pattern re;
            try {
                re = java.util.regex.Pattern.compile(pattern);
            } catch (Exception pe) {
                return "正则不合法: " + pe;
            }
            String cre = nameGlob == null || nameGlob.length() == 0 ? null : globToRegex(nameGlob);
            List<String> hits = new ArrayList<String>();
            List<File> stack = new ArrayList<File>();
            stack.add(root);
            while (!stack.isEmpty() && hits.size() < limit) {
                File d = stack.remove(stack.size() - 1);
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (hits.size() >= limit) break;
                    String nm = f.getName();
                    if (nm.startsWith(".")) continue;
                    if (f.isDirectory()) {
                        stack.add(f);
                        continue;
                    }
                    if (!f.isFile() || f.length() > MAX_READ) continue;
                    if (cre != null && !nm.matches(cre)) continue;
                    String txt = readText(f);
                    if (txt == null) continue;
                    String[] lines = txt.split("\n", -1);
                    for (int i = 0; i < lines.length && hits.size() < limit; i++) {
                        if (re.matcher(lines[i]).find()) {
                            String t = lines[i].trim();
                            if (t.length() > 200) t = t.substring(0, 200) + "…";
                            hits.add(f.getAbsolutePath() + ":" + (i + 1) + ": " + t);
                        }
                    }
                }
            }
            if (hits.isEmpty()) return "没有匹配 " + pattern + " 的内容";
            StringBuilder sb = new StringBuilder();
            for (String h : hits) sb.append(h).append('\n');
            return sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败: " + e;
        }
    }

    private static String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            byte[] probe = new byte[Math.min(512, b.length)];
            System.arraycopy(b, 0, probe, 0, probe.length);
            for (byte x : probe) {
                if (x == 0) return null;
            }
            return new String(b, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static File todoFile(Context c) {
        return new File(Store.workDir(c), "TODO.md");
    }

    static String todoWrite(Context c, JSONArray items) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# 当前任务清单\n\n");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String st = it.optString("status", "pending");
                String mark = "completed".equals(st) ? "[x]"
                        : "in_progress".equals(st) ? "[~]" : "[ ]";
                sb.append("- ").append(mark).append(' ')
                        .append(it.optString("text", "")).append('\n');
            }
            File f = todoFile(c);
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            return "已更新任务清单（共 " + items.length() + " 项）：\n" + sb;
        } catch (Exception e) {
            return "写入任务清单失败: " + e;
        }
    }

    static String todoRead(Context c) {
        try {
            File f = todoFile(c);
            if (!f.isFile()) return "（当前没有任务清单）";
            String t = readText(f);
            return t == null ? "（任务清单不可读）" : t;
        } catch (Exception e) {
            return "读取失败: " + e;
        }
    }
}
