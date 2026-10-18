package com.stereo.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class McpServer {
    private static final String TAG = "StereoMcp";
    private static volatile boolean running = false;
    private static ServerSocket server;
    public static ToolGate gate;

    public interface ToolGate {
        boolean approve(String tool, String argsJson, long timeoutMs);

        void notify(String tool, String argsJson, String permId);
    }

    public interface FilePusher {
        boolean push(String path, String name, long sizeBytes);
    }

    public static volatile FilePusher pusher;

    public static final long PERM_WAIT_MS = 3 * 60 * 1000L;

    public static boolean needsApproval(String name) {
        return name.equals("calendar_add") || name.equals("calendar_delete")
                || name.equals("calendar_update") || name.startsWith("obsidian_")
                || name.equals("skill_write")
                || name.equals("skill_delete") || name.equals("memory_write")
                || name.equals("memory_delete");
    }

    public static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        final Context c = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() { loop(c); }
        }, "mcp-server");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception e) { }
    }

    private static void loop(Context ctx) {
        try {
            server = new ServerSocket(8767, 16, java.net.InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "mcp up on 8767");
            while (running) {
                final Socket s = server.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(ctx, s); }
                }, "mcp-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "mcp loop", e);
        }
    }

    static String tokenOk(String real, String path, String headerTok) {
        try {
            if (real == null || real.length() == 0) return null;
            if (real.equals(headerTok)) return path;
            if (path.startsWith("/t/")) {
                String rest = path.substring(3);
                int sl = rest.indexOf('/');
                String tok = sl < 0 ? rest : rest.substring(0, sl);
                if (real.equals(tok)) return sl < 0 ? "/" : rest.substring(sl);
            }
            int qi = path.indexOf('?');
            if (qi >= 0) {
                for (String kv : path.substring(qi + 1).split("&")) {
                    int i = kv.indexOf('=');
                    if (i > 0 && "tk".equals(kv.substring(0, i))) {
                        String v = java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                        if (real.equals(v)) return path.substring(0, qi);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLine(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') break;
            if (prev != -1) bos.write(prev);
            prev = b;
        }
        if (prev == '\r') {
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
        if (prev == -1 && bos.size() == 0) return null;
        if (prev != -1) bos.write(prev);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void handle(Context ctx, Socket s) {
        try {
            s.setSoTimeout(30000);
            java.io.InputStream in = s.getInputStream();
            String reqLine = readLine(in);
            if (reqLine == null) {
                s.close();
                return;
            }
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            int contentLen = 0;
            String hTok = "";
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try {
                        contentLen = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ig) { }
                } else if (low.startsWith("x-stereo-token:")) {
                    hTok = line.substring(15).trim();
                }
            }
            String norm = tokenOk(Store.getToken(ctx), path, hTok);
            if (norm == null) {
                OutputStream os = s.getOutputStream();
                byte[] fb = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
                os.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + fb.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.write(fb);
                os.flush();
                s.close();
                return;
            }
            path = norm;
            if (contentLen > 150 * 1024 * 1024) {
                s.close();
                return;
            }
            if ("POST".equals(method) && (path.startsWith("/mcp"))) {
                byte[] body = new byte[contentLen];
                int off = 0;
                while (off < contentLen) {
                    int n = in.read(body, off, contentLen - off);
                    if (n < 0) break;
                    off += n;
                }
                String bodyStr = new String(body, 0, off, StandardCharsets.UTF_8);
                String resp = dispatch(ctx, bodyStr);
                OutputStream os = s.getOutputStream();
                if (resp == null) {
                    os.write(("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n"
                            + "mcp-session-id: stereo-session-1\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] json = resp.getBytes(StandardCharsets.UTF_8);
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + json.length + "\r\nmcp-session-id: stereo-session-1\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    os.write(json);
                }
                os.flush();
            } else if ("DELETE".equals(method) && path.startsWith("/mcp")) {
                OutputStream os = s.getOutputStream();
                os.write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
                        + "mcp-session-id: stereo-session-1\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            } else {
                OutputStream os = s.getOutputStream();
                os.write(("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n"
                        + "mcp-session-id: stereo-session-1\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ig) { }
        }
    }

    private static String dispatch(Context ctx, String body) {
        try {
            JSONObject req = new JSONObject(body);
            String method = req.optString("method", "");
            String lname = "";
            if ("tools/call".equals(method)) {
                JSONObject lp = req.optJSONObject("params");
                if (lp != null) lname = " " + lp.optString("name", "");
            }
            Log.i(TAG, "mcp req: " + method + lname);
            Object idO = req.has("id") ? req.get("id") : null;
            if (idO == null) return null;
            JSONObject result = new JSONObject();
            if ("initialize".equals(method)) {
                result.put("protocolVersion", req.optString("protocolVersion", "2025-03-26"));
                result.put("capabilities", new JSONObject().put("tools", new JSONObject()));
                result.put("serverInfo", new JSONObject().put("name", "stereo-tools").put("version", "3.0"));
            } else if ("tools/list".equals(method)) {
                result.put("tools", toolDefs());
            } else if ("tools/call".equals(method)) {
                JSONObject params = req.optJSONObject("params");
                String name = params == null ? "" : params.optString("name", "");
                JSONObject args = params == null ? new JSONObject()
                        : params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();
                result = callTool(ctx, name, args);
            } else if ("resources/list".equals(method)) {
                result.put("resources", new JSONArray());
            } else if ("resources/templates/list".equals(method)) {
                result.put("resourceTemplates", new JSONArray());
            } else if ("prompts/list".equals(method)) {
                result.put("prompts", new JSONArray());
            } else if ("ping".equals(method)) {
            } else {
                return jsonRpcError(idO, -32601, "method not found: " + method);
            }
            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", idO);
            resp.put("result", result);
            return resp.toString();
        } catch (Exception e) {
            return jsonRpcError("0", -32700, "parse error: " + e);
        }
    }

    private static String jsonRpcError(Object id, int code, String msg) {
        try {
            JSONObject e = new JSONObject().put("code", code).put("message", msg);
            return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", e).toString();
        } catch (Exception ig) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"err\"}}";
        }
    }

    private static JSONObject tool(String name, String desc, JSONObject schema) {
        try {
            return new JSONObject().put("name", name).put("description", desc)
                    .put("inputSchema", schema)
                    .put("annotations", new JSONObject().put("readOnlyHint", true));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONArray toolDefs() {
        JSONArray arr = new JSONArray();
        try {
            JSONObject empty = new JSONObject().put("type", "object").put("properties", new JSONObject());
            JSONObject days = new JSONObject().put("type", "object");
            days.put("properties", new JSONObject().put("days",
                    new JSONObject().put("type", "integer").put("description", "往后查多少天，默认14")));
            arr.put(tool("calendar_list", "列出未来 N 天的日历日程", days));
            JSONObject add = new JSONObject().put("type", "object");
            add.put("properties", new JSONObject()
                    .put("title", new JSONObject().put("type", "string"))
                    .put("start", new JSONObject().put("type", "string")
                            .put("description", "开始时间 yyyy-MM-dd HH:mm"))
                    .put("end", new JSONObject().put("type", "string"))
                    .put("allDay", new JSONObject().put("type", "boolean"))
                    .put("loc", new JSONObject().put("type", "string"))
                    .put("desc", new JSONObject().put("type", "string"))
                    .put("remindMin", new JSONObject().put("type", "integer")
                            .put("description", "提前提醒分钟数，不填则不提醒")));
            add.put("required", new JSONArray().put("title").put("start"));
            arr.put(tool("calendar_add", "新建日历日程（需用户授权）", add));
            JSONObject del = new JSONObject().put("type", "object");
            del.put("properties", new JSONObject().put("id", new JSONObject().put("type", "integer")));
            del.put("required", new JSONArray().put("id"));
            arr.put(tool("calendar_delete", "删除日历日程（需用户授权）", del));
            JSONObject upd = new JSONObject().put("type", "object");
            upd.put("properties", new JSONObject()
                    .put("id", new JSONObject().put("type", "integer"))
                    .put("title", new JSONObject().put("type", "string"))
                    .put("start", new JSONObject().put("type", "string"))
                    .put("end", new JSONObject().put("type", "string"))
                    .put("loc", new JSONObject().put("type", "string"))
                    .put("desc", new JSONObject().put("type", "string")));
            upd.put("required", new JSONArray().put("id"));
            arr.put(tool("calendar_update", "修改日历日程（需用户授权）", upd));
            arr.put(tool("location_get", "获取当前定位（经纬度）", empty));
            JSONObject list = new JSONObject().put("type", "object");
            list.put("properties", new JSONObject().put("dir",
                    new JSONObject().put("type", "string").put("description", "库内相对目录，空为根目录")));
            arr.put(tool("obsidian_list", "列出知识库目录下的笔记和文件夹", list));
            JSONObject rd = new JSONObject().put("type", "object");
            rd.put("properties", new JSONObject().put("path",
                    new JSONObject().put("type", "string").put("description", "库内相对路径")));
            rd.put("required", new JSONArray().put("path"));
            arr.put(tool("obsidian_read", "读取一篇笔记内容", rd));
            JSONObject wr = new JSONObject().put("type", "object");
            wr.put("properties", new JSONObject()
                    .put("path", new JSONObject().put("type", "string"))
                    .put("content", new JSONObject().put("type", "string"))
                    .put("append", new JSONObject().put("type", "boolean")
                            .put("description", "true 为追加到文末，默认覆盖")));
            wr.put("required", new JSONArray().put("path").put("content"));
            arr.put(tool("obsidian_write", "写入/新建笔记（需用户授权）", wr));
            arr.put(tool("obsidian_delete", "删除笔记（需用户授权）", rd));
            JSONObject se = new JSONObject().put("type", "object");
            se.put("properties", new JSONObject().put("query",
                    new JSONObject().put("type", "string")));
            se.put("required", new JSONArray().put("query"));
            arr.put(tool("obsidian_search", "按关键词搜索知识库笔记", se));
            JSONObject sf = new JSONObject().put("type", "object");
            sf.put("properties", new JSONObject()
                    .put("path", new JSONObject().put("type", "string")
                            .put("description", "文件在手机上的绝对路径"))
                    .put("name", new JSONObject().put("type", "string")
                            .put("description", "展示给用户的文件名，可选，默认取路径末段")));
            sf.put("required", new JSONArray().put("path"));
            arr.put(tool("send_file", "把文件发送给用户：聊天界面会出现可点开的文件卡片"
                    + "（像微信发文件）。生成报告/表格/压缩包等任何文件后，"
                    + "必须用这个工具把文件发给用户，而不是只报路径", sf));

            JSONObject ri = new JSONObject().put("type", "object");
            ri.put("properties", new JSONObject()
                    .put("path", new JSONObject().put("type", "string")
                            .put("description", "图片在手机上的绝对路径"))
                    .put("question", new JSONObject().put("type", "string")
                            .put("description", "要问图片的问题。不填则做全面描述+提取文字")));
            ri.put("required", new JSONArray().put("path"));
            arr.put(tool("read_image", "读懂一张图片（调用视觉模型）。"
                    + "用户发来图片、截图、表格照片、发票、白板、名片、PDF 转图时，"
                    + "必须先用这个工具看清内容再回答，不要凭空猜测图片内容。", ri));

            JSONObject rp = new JSONObject().put("type", "object");
            rp.put("properties", new JSONObject()
                    .put("path", new JSONObject().put("type", "string")
                            .put("description", "PDF 在手机上的绝对路径"))
                    .put("pages", new JSONObject().put("type", "integer")
                            .put("description", "解析前多少页，默认 3，最多 8"))
                    .put("question", new JSONObject().put("type", "string")
                            .put("description", "想从 PDF 里问什么。不填则逐页转录摘要")));
            rp.put("required", new JSONArray().put("path"));
            arr.put(tool("read_pdf", "读懂 PDF（把页面渲染成图后交给视觉模型解析）。"
                    + "处理长文档、合同、报表 PDF 时用它，不要跳过。", rp));

            JSONObject ed = new JSONObject().put("type", "object");
            ed.put("properties", new JSONObject()
                    .put("path", new JSONObject().put("type", "string").put("description", "文件绝对路径"))
                    .put("old", new JSONObject().put("type", "string")
                            .put("description", "要被替换的原文，必须与文件内容逐字一致（含缩进）"))
                    .put("new", new JSONObject().put("type", "string").put("description", "替换成的新文本"))
                    .put("replaceAll", new JSONObject().put("type", "boolean")
                            .put("description", "true 表示全部替换；默认 false，要求 old 唯一")));
            ed.put("required", new JSONArray().put("path").put("old").put("new"));
            arr.put(tool("edit_file", "精确修改文件：把 old 替换成 new。"
                    + "改文件优先用它，比整篇重写安全。old 不唯一时会自动取消。", ed));

            JSONObject gl = new JSONObject().put("type", "object");
            gl.put("properties", new JSONObject()
                    .put("pattern", new JSONObject().put("type", "string")
                            .put("description", "文件名通配，如 *.csv、报表*.xlsx"))
                    .put("dir", new JSONObject().put("type", "string")
                            .put("description", "起始目录，默认工作目录"))
                    .put("limit", new JSONObject().put("type", "integer")));
            gl.put("required", new JSONArray().put("pattern"));
            arr.put(tool("file_glob", "按文件名模式递归查找文件", gl));

            JSONObject gr = new JSONObject().put("type", "object");
            gr.put("properties", new JSONObject()
                    .put("pattern", new JSONObject().put("type", "string")
                            .put("description", "正则表达式，如 金额|编号"))
                    .put("path", new JSONObject().put("type", "string")
                            .put("description", "文件或目录，默认工作目录"))
                    .put("glob", new JSONObject().put("type", "string")
                            .put("description", "只看这些文件，如 *.csv"))
                    .put("limit", new JSONObject().put("type", "integer")));
            gr.put("required", new JSONArray().put("pattern"));
            arr.put(tool("file_grep", "在文件内容里按正则搜索，返回 文件:行号:内容", gr));

            JSONObject wf = new JSONObject().put("type", "object");
            wf.put("properties", new JSONObject()
                    .put("url", new JSONObject().put("type", "string"))
                    .put("maxChars", new JSONObject().put("type", "integer")
                            .put("description", "最多返回多少字符，默认 60000")));
            wf.put("required", new JSONArray().put("url"));
            arr.put(tool("web_fetch", "抓取网页正文（已剥离 HTML 标签）", wf));

            JSONObject ws = new JSONObject().put("type", "object");
            ws.put("properties", new JSONObject()
                    .put("query", new JSONObject().put("type", "string"))
                    .put("limit", new JSONObject().put("type", "integer")
                            .put("description", "要点条数，默认 6")));
            ws.put("required", new JSONArray().put("query"));
            arr.put(tool("web_search", "联网检索最新信息并给出要点（需已配置阿里百炼 Key）", ws));

            JSONObject tw = new JSONObject().put("type", "object");
            JSONObject item = new JSONObject().put("type", "object");
            item.put("properties", new JSONObject()
                    .put("text", new JSONObject().put("type", "string"))
                    .put("status", new JSONObject().put("type", "string")
                            .put("description", "pending / in_progress / completed")));
            tw.put("properties", new JSONObject()
                    .put("todos", new JSONObject().put("type", "array").put("items", item)
                            .put("description", "完整清单，每次提交全量（覆盖写入）")));
            tw.put("required", new JSONArray().put("todos"));
            arr.put(tool("todo_write", "维护本次任务的待办清单。"
                    + "任务超过 3 步时先建清单，每完成一步就更新一次，让用户看到进度。", tw));
            arr.put(tool("todo_read", "读回当前待办清单", empty));

            JSONObject asp = new JSONObject().put("type", "object");
            asp.put("properties", new JSONObject()
                    .put("prompt", new JSONObject().put("type", "string")
                            .put("description", "交给子 Agent 的完整任务描述，要自包含：背景、目标、产出要求"))
                    .put("model", new JSONObject().put("type", "string")
                            .put("description", "可选，指定子 Agent 用哪个模型，默认与当前一致"))
                    .put("provider", new JSONObject().put("type", "string")
                            .put("description", "可选，模型所属供应商 id：dashscope/zhipu/deepseek/kimi/p<id>")));
            asp.put("required", new JSONArray().put("prompt"));
            arr.put(tool("agent_spawn", "派生一个子 Agent 并行干活（互不干扰的独立子任务才用）。"
                    + "会立刻返回任务号 a1/a2…，你要用 agent_wait 取结果。最多同时 4 个。"
                    + "子 Agent 的过程不会出现在用户的对话列表里；它的成果由你消化后在回复或结果文件里转述。"
                    + "适合：多份文件分别解析、多路检索、一个写一个查。", asp));

            JSONObject aw = new JSONObject().put("type", "object");
            aw.put("properties", new JSONObject()
                    .put("id", new JSONObject().put("type", "string")
                            .put("description", "agent_spawn 返回的任务号，如 a1"))
                    .put("timeoutSec", new JSONObject().put("type", "integer")
                            .put("description", "最多等多少秒，默认 300，上限 600")));
            aw.put("required", new JSONArray().put("id"));
            arr.put(tool("agent_wait", "等子 Agent 跑完并取回它的结论（阻塞直到完成或超时）", aw));

            arr.put(tool("agent_list", "列出所有子 Agent 任务及状态。"
                    + "派生多个后同时用，可以先 agent_list 看谁先跑完", empty));

            JSONObject as = new JSONObject().put("type", "object");
            as.put("properties", new JSONObject().put("id",
                    new JSONObject().put("type", "string").put("description", "任务号")));
            as.put("required", new JSONArray().put("id"));
            arr.put(tool("agent_stop", "中止一个还在跑的子 Agent", as));

            arr.put(tool("skill_list", "列出可用技能（只给名字和一句话说明）。"
                    + "任何任务开始前先看一眼有没有对口技能。", empty));

            JSONObject su = new JSONObject().put("type", "object");
            su.put("properties", new JSONObject().put("name",
                    new JSONObject().put("type", "string").put("description", "技能名，来自 skill_list")));
            su.put("required", new JSONArray().put("name"));
            arr.put(tool("skill_use", "读取技能全文，然后严格按里面的步骤执行。"
                    + "技能是用户沉淀的标准作业流程，优先级高于你的默认做法。", su));

            JSONObject sw = new JSONObject().put("type", "object");
            sw.put("properties", new JSONObject()
                    .put("name", new JSONObject().put("type", "string")
                            .put("description", "技能名，字母数字下划线短横线"))
                    .put("content", new JSONObject().put("type", "string")
                            .put("description", "SKILL.md 全文。开头建议写 description: 一句话触发说明")));
            sw.put("required", new JSONArray().put("name").put("content"));
            arr.put(tool("skill_write", "把你的做法固化成新技能，下次同类任务可直接调用（需用户授权）", sw));

            JSONObject sd = new JSONObject().put("type", "object");
            sd.put("properties", new JSONObject().put("name", new JSONObject().put("type", "string")));
            sd.put("required", new JSONArray().put("name"));
            arr.put(tool("skill_delete", "删除一个自建技能（需用户授权）", sd));

            arr.put(tool("memory_list", "读取长期记忆索引（用户偏好、项目背景、踩过的坑）。"
                    + "每轮对话先看索引，命中相关条目再 memory_read 取正文。", empty));

            JSONObject mr = new JSONObject().put("type", "object");
            mr.put("properties", new JSONObject().put("name",
                    new JSONObject().put("type", "string").put("description", "记忆名，来自 memory_list")));
            mr.put("required", new JSONArray().put("name"));
            arr.put(tool("memory_read", "读取一条长期记忆的正文", mr));

            JSONObject mw = new JSONObject().put("type", "object");
            mw.put("properties", new JSONObject()
                    .put("name", new JSONObject().put("type", "string")
                            .put("description", "记忆名，中英文皆可，短横线分词"))
                    .put("title", new JSONObject().put("type", "string").put("description", "索引标题"))
                    .put("hook", new JSONObject().put("type", "string")
                            .put("description", "一句话摘要，写进索引供后续检索"))
                    .put("type", new JSONObject().put("type", "string")
                            .put("description", "user / feedback / project / reference，默认 project"))
                    .put("content", new JSONObject().put("type", "string").put("description", "记忆正文")));
            mw.put("required", new JSONArray().put("name").put("content"));
            arr.put(tool("memory_write", "写入/更新一条长期记忆并自动维护索引（需用户授权）。"
                    + "用户说了「记住」、或出现了以后还用得上的偏好/背景/教训时写。"
                    + "仓库或本次对话已有的信息不要重复记。", mw));

            JSONObject md = new JSONObject().put("type", "object");
            md.put("properties", new JSONObject().put("name", new JSONObject().put("type", "string")));
            md.put("required", new JSONArray().put("name"));
            arr.put(tool("memory_delete", "删除一条过时或错误的长期记忆（需用户授权）", md));
        } catch (Exception e) { }
        return arr;
    }

    public static JSONArray toolNames() {
        JSONArray out = new JSONArray();
        try {
            JSONArray defs = toolDefs();
            for (int i = 0; i < defs.length(); i++) {
                JSONObject d = defs.optJSONObject(i);
                if (d != null) out.put(d.optString("name"));
            }
        } catch (Exception ig) { }
        return out;
    }

    private static JSONObject textResult(String text, boolean isError) {
        try {
            JSONObject o = new JSONObject();
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", text));
            o.put("content", content);
            o.put("isError", isError);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static boolean blockedPath(Context ctx, String p) {
        if (p == null || p.length() == 0) return false;
        if (!new File(p).isAbsolute()) return false;
        try {
            String ch = Store.codexHome(ctx).getCanonicalPath();
            String cp = new File(p).getCanonicalPath();
            return cp.equals(ch) || cp.startsWith(ch + File.separator);
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isReadOnlyTool(String name) {
        return isReadOnly(name);
    }

    private static boolean isReadOnly(String name) {
        return name.equals("read_image") || name.equals("read_pdf")
                || name.equals("file_glob") || name.equals("file_grep")
                || name.equals("web_fetch") || name.equals("web_search")
                || name.equals("todo_read") || name.equals("todo_write")
                || name.equals("calendar_list") || name.equals("location_get")
                || name.equals("obsidian_list") || name.equals("obsidian_read")
                || name.equals("obsidian_search")
                || name.equals("agent_spawn") || name.equals("agent_wait")
                || name.equals("agent_list") || name.equals("agent_stop")
                || name.equals("skill_list") || name.equals("skill_use")
                || name.equals("memory_list") || name.equals("memory_read");
    }

    private static JSONObject callTool(Context ctx, String name, JSONObject args) {
        String ap = args.optString("path", "").trim();
        if (ap.length() == 0) ap = args.optString("dir", "").trim();
        if (blockedPath(ctx, ap)) {
            return textResult("该路径属于引擎私有配置目录（keys/config/sessions），禁止读取或发送。", true);
        }
        String hookBlock = Hooks.preTool(ctx, name, args.toString());
        if (hookBlock != null) return textResult(hookBlock, true);
        boolean needApproval = needsApproval(name);
        if (!needApproval && !isReadOnly(name)) {
            String vp = args.optString("path", "").trim();
            if (vp.startsWith("/")
                    && !vp.startsWith(ctx.getFilesDir().getAbsolutePath() + "/")) {
                needApproval = true;
            }
        }
        String argsStr = args.toString();
        if (needApproval) {
            if (gate != null) {
                gate.notify(name, argsStr, "m" + System.currentTimeMillis());
                boolean ok = gate.approve(name, argsStr, PERM_WAIT_MS);
                if (!ok) {
                    return textResult("该操作（" + name + "）等了 3 分钟未获用户确认，已自动取消。"
                            + "用户可能没看到授权弹窗。不要反复重试同一操作；"
                            + "改用沙盒内替代方案继续任务，并在回复里说明。", true);
                }
            }
        }
        try {
            if (name.equals("calendar_list")) {
                return textResult(DeviceTools.calList(ctx, args.optInt("days", 14)), false);
            }
            if (name.equals("calendar_add")) {
                return textResult(DeviceTools.calAdd(ctx, args), false);
            }
            if (name.equals("calendar_delete")) {
                return textResult(DeviceTools.calDel(ctx, args.optLong("id", -1)), false);
            }
            if (name.equals("calendar_update")) {
                return textResult(DeviceTools.calUpd(ctx, args), false);
            }
            if (name.equals("location_get")) {
                return textResult(DeviceTools.getLoc(ctx), false);
            }
            if (name.startsWith("obsidian_")) {
                return obsidian(ctx, name, args);
            }
            if (name.equals("send_file")) {
                String path = args.optString("path", "").trim();
                String disp = args.optString("name", "").trim();
                if (path.length() == 0) return textResult("缺少 path 参数", true);
                File f = new File(path);
                if (!f.isAbsolute()) return textResult("path 必须是绝对路径: " + path, true);
                if (!f.isFile()) return textResult("文件不存在: " + path, true);
                long sz = f.length();
                if (sz <= 0) return textResult("文件是空的: " + path, true);
                if (sz > 290L * 1024 * 1024) {
                    return textResult("文件超过 290MB，无法发送: " + path, true);
                }
                if (!f.canRead()) return textResult("文件不可读: " + path, true);
                if (disp.length() == 0) disp = f.getName();
                if (pusher != null) {
                    try {
                        if (!pusher.push(f.getAbsolutePath(), disp, sz)) {
                            return textResult("「" + disp + "」本轮已经发过了，已自动跳过"
                                    + "（同一轮里同一个文件只发一次）。不要在回复里再重复路径。", false);
                        }
                    } catch (Exception pe) {
                        return textResult("发送失败: " + pe, true);
                    }
                }
                return textResult("已发送给用户：「" + disp + "」("
                        + (sz / 1024) + "KB)。用户会在聊天界面看到文件卡片，可直接点开。"
                        + "不需要再在回复里重复路径。**本轮不要再发这个文件了。**", false);
            }
            if (name.equals("read_image")) {
                return textResult(AgentTools.readImage(ctx,
                        args.optString("path", "").trim(),
                        args.optString("question", "")), false);
            }
            if (name.equals("read_pdf")) {
                return textResult(AgentTools.readPdf(ctx,
                        args.optString("path", "").trim(),
                        args.optInt("pages", 3),
                        args.optString("question", "")), false);
            }
            if (name.equals("edit_file")) {
                return textResult(AgentTools.editFile(ctx,
                        args.optString("path", "").trim(),
                        args.optString("old", ""),
                        args.optString("new", ""),
                        args.optBoolean("replaceAll", false)), false);
            }
            if (name.equals("file_glob")) {
                return textResult(AgentTools.fileGlob(ctx,
                        args.optString("dir", ""),
                        args.optString("pattern", ""),
                        args.optInt("limit", 200)), false);
            }
            if (name.equals("file_grep")) {
                return textResult(AgentTools.fileGrep(ctx,
                        args.optString("path", "").trim().length() == 0
                                ? Store.workDir(ctx).getAbsolutePath() : args.optString("path", "").trim(),
                        args.optString("pattern", ""),
                        args.optString("glob", ""),
                        args.optInt("limit", 200)), false);
            }
            if (name.equals("web_fetch")) {
                return textResult(AgentTools.webFetch(args.optString("url", ""),
                        args.optInt("maxChars", 60000)), false);
            }
            if (name.equals("web_search")) {
                return textResult(AgentTools.webSearch(ctx, args.optString("query", ""),
                        args.optInt("limit", 6)), false);
            }
            if (name.equals("todo_write")) {
                JSONArray todos = args.optJSONArray("todos");
                return textResult(AgentTools.todoWrite(ctx,
                        todos == null ? new JSONArray() : todos), false);
            }
            if (name.equals("todo_read")) {
                return textResult(AgentTools.todoRead(ctx), false);
            }
            if (name.equals("agent_spawn")) {
                String p = args.optString("prompt", "").trim();
                if (p.length() == 0) return textResult("缺少 prompt 参数", true);
                String mdl = args.optString("model", "").trim();
                String pv = args.optString("provider", "").trim();
                if (mdl.length() == 0) mdl = Store.getSetting(ctx, "model", "qwen3.8-max");
                if (pv.length() == 0) pv = Store.getSetting(ctx, "provider", "dashscope");
                if (SubAgent.liveCount() >= 4) {
                    return textResult("同时最多 4 个子 Agent，请先 agent_wait 收掉一些再派。", true);
                }
                String id = SubAgent.spawn(p, mdl, pv);
                if (id == null) return textResult("子 Agent 启动失败（引擎未就绪或参数为空）。", true);
                return textResult("已派生子 Agent " + id + "（模型 " + mdl + " / " + pv
                        + "）。继续做你的事，需要它的结论时用 agent_wait id=\"" + id + "\" 取。", false);
            }
            if (name.equals("agent_wait")) {
                String id = args.optString("id", "").trim();
                SubAgent.Task t = SubAgent.get(id);
                if (t == null) return textResult("没有任务号 " + id + "。先用 agent_list 看有哪些。", true);
                int ts = args.optInt("timeoutSec", 300);
                if (ts < 5) ts = 5;
                if (ts > 600) ts = 600;
                String out = SubAgent.waitText(id, ts);
                String st = SubAgent.statusOf(id);
                if ("running".equals(st)) {
                    return textResult("子 Agent " + id + " 仍在运行（已等 " + ts + " 秒）。"
                            + "可稍后再 wait，或先做别的。\n已完成部分：\n" + (out == null ? "" : out), false);
                }
                JSONObject r = new JSONObject(SubAgent.report(t, true));
                return textResult("子 Agent " + id + " 状态 " + st + "：\n" + r.toString(), false);
            }
            if (name.equals("agent_list")) {
                return textResult(SubAgent.listAll(), false);
            }
            if (name.equals("agent_stop")) {
                String id = args.optString("id", "").trim();
                if (SubAgent.get(id) == null) return textResult("没有任务号 " + id + "。", true);
                return textResult(SubAgent.interrupt(id)
                        ? "已中止子 Agent " + id + "。" : "中止失败（可能已结束）。", false);
            }
            if (name.equals("skill_list")) {
                return textResult(Skills.list(ctx), false);
            }
            if (name.equals("skill_use")) {
                return textResult(Skills.use(ctx, args.optString("name", "").trim()), false);
            }
            if (name.equals("skill_write")) {
                return textResult(Skills.write(ctx, args.optString("name", "").trim(),
                        args.optString("content", "")), false);
            }
            if (name.equals("skill_delete")) {
                return textResult(Skills.delete(ctx, args.optString("name", "").trim()), false);
            }
            if (name.equals("memory_list")) {
                return textResult(MemoryStore.list(ctx), false);
            }
            if (name.equals("memory_read")) {
                return textResult(MemoryStore.read(ctx, args.optString("name", "").trim()), false);
            }
            if (name.equals("memory_write")) {
                return textResult(MemoryStore.write(ctx,
                        args.optString("name", "").trim(),
                        args.optString("title", ""),
                        args.optString("hook", ""),
                        args.optString("type", ""),
                        args.optString("content", "")), false);
            }
            if (name.equals("memory_delete")) {
                return textResult(MemoryStore.delete(ctx, args.optString("name", "").trim()), false);
            }
            return textResult("unknown tool: " + name, true);
        } catch (Exception e) {
            return textResult("工具执行失败: " + e, true);
        }
    }

    private static File vaultRoot(Context ctx) {
        String root = Store.getSetting(ctx, "obsidian_root", "");
        if (root != null && root.trim().length() > 0) {
            File f = new File(root.trim());
            if (f.isDirectory()) return f;
        }
        String det = Store.detectObsidianRoot(ctx);
        if (det != null) return new File(det);
        return new File(root == null || root.trim().length() == 0
                ? "/storage/emulated/0/Documents/Obsidian" : root.trim());
    }

    private static File resolveVault(Context ctx, String rel) throws Exception {
        File root = vaultRoot(ctx);
        File f = new File(root, rel == null ? "" : rel);
        String cp = f.getCanonicalPath();
        String rp = root.getCanonicalPath();
        if (!cp.equals(rp) && !cp.startsWith(rp + File.separator)) {
            throw new Exception("路径越界：只能在知识库目录内操作");
        }
        return f;
    }

    private static JSONObject obsidian(Context ctx, String name, JSONObject args) {
        try {
            if (!vaultRoot(ctx).isDirectory()) {
                return textResult("知识库目录不存在：" + vaultRoot(ctx)
                        + "。可能是 App 还没拿到「所有文件访问」权限（设置 → 应用 → Stereo AI →"
                        + " 权限 → 文件和媒体 → 允许管理所有文件），或在设置里填的目录不对。"
                        + "地址栏里点「文件访问」按钮可一键跳转授权。", true);
            }
            if (name.equals("obsidian_list")) {
                File d = resolveVault(ctx, args.optString("dir", ""));
                JSONArray files = new JSONArray();
                JSONArray dirs = new JSONArray();
                File[] fs = d.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        if (f.isHidden()) continue;
                        if (f.isDirectory()) dirs.put(f.getName());
                        else files.put(f.getName() + " (" + (f.length() / 1024) + "KB)");
                    }
                }
                JSONObject out = new JSONObject();
                out.put("dir", args.optString("dir", ""));
                out.put("dirs", dirs);
                out.put("files", files);
                return textResult(out.toString(), false);
            }
            if (name.equals("obsidian_read")) {
                File f = resolveVault(ctx, args.optString("path", ""));
                if (!f.isFile()) return textResult("文件不存在: " + args.optString("path"), true);
                FileInputStream in = new FileInputStream(f);
                Store.ByteArrayOutputStream2 bos = new Store.ByteArrayOutputStream2();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                String content = new String(bos.toByteArray(), "UTF-8");
                if (content.length() > 60000) {
                    content = content.substring(0, 60000) + "\n…(过长截断)";
                }
                return textResult(content, false);
            }
            if (name.equals("obsidian_write")) {
                File f = resolveVault(ctx, args.optString("path", ""));
                String content = args.optString("content", "");
                boolean append = args.optBoolean("append", false);
                if (append && f.isFile()) {
                    content = readFileUtf8(f) + "\n" + content;
                }
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                FileOutputStream out = new FileOutputStream(f, false);
                out.write(content.getBytes("UTF-8"));
                out.close();
                return textResult("已写入: " + args.optString("path") + " (" + content.length() + " 字符)", false);
            }
            if (name.equals("obsidian_delete")) {
                File f = resolveVault(ctx, args.optString("path", ""));
                if (!f.isFile()) return textResult("文件不存在: " + args.optString("path"), true);
                boolean ok = f.delete();
                return textResult(ok ? "已删除: " + args.optString("path") : "删除失败", ok);
            }
            if (name.equals("obsidian_search")) {
                String q = args.optString("query", "").toLowerCase();
                if (q.length() == 0) return textResult("缺少关键词", true);
                List<String> hits = new ArrayList<String>();
                searchDir(vaultRoot(ctx), q, hits, 0);
                JSONObject out = new JSONObject();
                JSONArray ja = new JSONArray();
                for (String h : hits) ja.put(h);
                out.put("hits", ja);
                return textResult(out.toString(), false);
            }
            return textResult("unknown: " + name, true);
        } catch (Exception e) {
            return textResult("操作失败: " + e, true);
        }
    }

    private static String readFileUtf8(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            Store.ByteArrayOutputStream2 bos = new Store.ByteArrayOutputStream2();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void searchDir(File dir, String q, List<String> hits, int depth) {
        if (hits.size() >= 30 || depth > 6) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (hits.size() >= 30) return;
            if (f.isHidden()) continue;
            if (f.isDirectory()) {
                searchDir(f, q, hits, depth + 1);
            } else if (f.getName().toLowerCase().endsWith(".md")) {
                boolean nameHit = f.getName().toLowerCase().contains(q);
                boolean contentHit = false;
                if (!nameHit && f.length() < 2 * 1024 * 1024) {
                    try {
                        FileInputStream in = new FileInputStream(f);
                        byte[] b = new byte[(int) Math.min(f.length(), 300000)];
                        int n = in.read(b);
                        in.close();
                        if (n > 0) {
                            contentHit = new String(b, 0, n, "UTF-8").toLowerCase().contains(q);
                        }
                    } catch (Exception ig) { }
                }
                if (nameHit || contentHit) {
                    String rel;
                    try {
                        rel = f.getCanonicalPath();
                    } catch (Exception ig) {
                        rel = f.getAbsolutePath();
                    }
                    hits.add((nameHit ? "[文件名] " : "[内容] ") + rel);
                }
            }
        }
    }
}
