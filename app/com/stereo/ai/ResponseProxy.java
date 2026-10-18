package com.stereo.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 本地协议转换代理：codex(Responses API) ↔ 上游(Chat Completions API)。
 * codex 0.156 只支持 wire_api=responses；DashScope 的 responses 网关把
 * function_call_output 误映射成 role=tool 的 Message 导致间歇性 400，
 * DeepSeek 则根本没有 responses 端点。这里把 codex 的 Responses 请求
 * 转成 chat/completions 发给上游，再把 chat SSE 流转回 Responses SSE
 * 事件(response.created / output_item.done / response.completed)。
 * codex config 的 base_url 指向 http://127.0.0.1:8768/<ds|zp|dp>
 */
public class ResponseProxy {
    private static final String TAG = "StereoProxy";
    private static volatile boolean running = false;
    private static ServerSocket server;

    public static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        final Context c = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() { loop(c); }
        }, "resp-proxy");
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
            int port = Integer.parseInt(System.getProperty("px.port", "8768"));
            server = new ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"));
            System.out.println("[PX] proxy up on " + port);
            Log.i(TAG, "proxy up on " + port);
            while (running) {
                final Socket s = server.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(ctx, s); }
                }, "proxy-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "proxy loop", e);
        }
    }

    private static void handle(Context ctx, Socket s) {
        try {
            s.setSoTimeout(300000);
            System.out.println("[PX] conn from " + s.getRemoteSocketAddress());
            java.io.InputStream in = s.getInputStream();
            String reqLine = readLine(in);
            if (reqLine == null) {
                s.close();
                return;
            }
            System.out.println("[PX] reqLine: " + redactTk(reqLine));
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            String authPath = stripToken(Store.getToken(ctx), path);
            if (authPath == null) {
                byte[] msg = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
                OutputStream os = s.getOutputStream();
                os.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: "
                        + msg.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(msg);
                os.flush();
                s.close();
                return;
            }
            path = authPath;
            String customId = null;
            if (path.startsWith("/p/")) {
                String rest = path.substring(3);
                int sl = rest.indexOf('/');
                customId = sl < 0 ? rest : rest.substring(0, sl);
                path = sl < 0 ? "/" : rest.substring(sl);
            }
            int contentLen = 0;
            boolean chunked = false;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try {
                        contentLen = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ig) { }
                } else if (low.startsWith("transfer-encoding:") && low.indexOf("chunked") >= 0) {
                    chunked = true;
                }
            }
            if (!"POST".equals(method)) {
                byte[] msg = "{\"error\":\"POST only\"}".getBytes(StandardCharsets.UTF_8);
                OutputStream os = s.getOutputStream();
                os.write(("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: " + msg.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(msg);
                os.flush();
                s.close();
                return;
            }
            byte[] body;
            int off = 0;
            if (chunked) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                while (true) {
                    String sz = readLine(in);
                    if (sz == null) break;
                    int semi = sz.indexOf(';');
                    if (semi >= 0) sz = sz.substring(0, semi);
                    sz = sz.trim();
                    if (sz.length() == 0) continue;
                    int n;
                    try {
                        n = Integer.parseInt(sz, 16);
                    } catch (Exception e) {
                        break;
                    }
                    if (n <= 0) break;
                    byte[] buf = new byte[n];
                    int r = 0;
                    while (r < n) {
                        int k = in.read(buf, r, n - r);
                        if (k < 0) break;
                        r += k;
                    }
                    bos.write(buf, 0, r);
                    readLine(in);
                }
                body = bos.toByteArray();
                off = body.length;
            } else {
                body = new byte[contentLen];
                while (off < contentLen) {
                    int n = in.read(body, off, contentLen - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            System.out.println("[PX] got " + method + " " + path + " len=" + off);
            String bodyStr = new String(body, 0, off, StandardCharsets.UTF_8);
            String pathProvider = providerFor(path);
            String converted = responsesToChat(bodyStr,
                    "deepseek".equals(pathProvider) || "dashscope".equals(pathProvider));
            String dumpDir = System.getProperty("px.dump", "");
            if (dumpDir.length() > 0) {
                try {
                    new java.io.File(dumpDir).mkdirs();
                    java.io.FileOutputStream df = new java.io.FileOutputStream(
                            dumpDir + "/chat_" + System.currentTimeMillis() + ".json");
                    df.write(converted.getBytes(StandardCharsets.UTF_8));
                    df.close();
                } catch (Exception ig) { }
            }
            System.out.println("[PX] converted len=" + converted.length());
            String upstream = upstreamFor(path);
            String provider = providerFor(path);
            String proto = null;
            if (customId != null) {
                String cbase = Store.providerBase(ctx, customId);
                if (cbase != null && cbase.length() > 0) {
                    cbase = cbase.endsWith("/")
                            ? cbase.substring(0, cbase.length() - 1) : cbase;
                    proto = Store.providerProto(ctx, customId);
                    provider = customId;
                    upstream = "anthropic".equals(proto)
                            ? cbase + "/v1/messages"
                            : cbase + "/chat/completions";
                }
            }
            if (upstream == null) {
                byte[] msg = ("{\"error\":\"unknown route: " + path + "\"}").getBytes(StandardCharsets.UTF_8);
                OutputStream os = s.getOutputStream();
                os.write(("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: "
                        + msg.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(msg);
                os.flush();
                s.close();
                return;
            }
            System.out.println("[PX] pipe -> " + upstream);
            String key = Store.loadKeys(ctx).optString(provider, "");
            if ("zhipu".equals(provider)) {
                pipeAnthropic(responsesToAnthropic(bodyStr), key, s);
            } else if ("anthropic".equals(proto)) {
                pipeAnthropic(responsesToAnthropic(bodyStr), key, s, upstream);
            } else {
                pipeChat(upstream, converted, key, s);
            }
            System.out.println("[PX] pipe done");
        } catch (Exception e) {
            Log.e(TAG, "proxy conn", e);
            try { s.close(); } catch (Exception ig) { }
        }
    }

    static String readLine(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
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

    static String textOfContent(Object c) {
        if (c == null) return "";
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            JSONArray arr = (JSONArray) c;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject blk = arr.optJSONObject(i);
                if (blk == null) continue;
                String bt = optStr(blk, "type", "");
                if ("input_image".equals(bt) || "image_url".equals(bt) || "input_image_url".equals(bt)) {
                    sb.append("[附带图片]");
                    continue;
                }
                if ("input_audio".equals(bt)) {
                    sb.append("[附带音频]");
                    continue;
                }
                if ("input_file".equals(bt)) {
                    sb.append("[附带文件]");
                    continue;
                }
                sb.append(optStr(blk, "text", ""));
            }
            return sb.toString();
        }
        return String.valueOf(c);
    }

    static String optStr(JSONObject o, String k, String def) {
        if (o == null) return def;
        Object v = o.opt(k);
        return (v instanceof String) ? (String) v : def;
    }

    static String reasoningText(JSONObject it) {
        StringBuilder sb = new StringBuilder();
        JSONArray sum = it.optJSONArray("summary");
        if (sum != null) {
            for (int i = 0; i < sum.length(); i++) {
                JSONObject s = sum.optJSONObject(i);
                if (s != null) sb.append(optStr(s, "text", ""));
            }
        }
        if (sb.length() == 0) {
            JSONArray c = it.optJSONArray("content");
            if (c != null) {
                for (int i = 0; i < c.length(); i++) {
                    JSONObject s = c.optJSONObject(i);
                    if (s != null) sb.append(optStr(s, "text", ""));
                }
            }
        }
        return sb.toString();
    }

    static long estTokens(String s) {
        if (s == null || s.length() == 0) return 0;
        long cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x2E80) cjk++;
            else other++;
        }
        return cjk + other / 4;
    }

    static String responsesToChat(String bodyStr) {
        return responsesToChat(bodyStr, false);
    }

    static String responsesToChat(String bodyStr, boolean fillMissingReasoning) {
        try {
            JSONObject j = new JSONObject(bodyStr);
            JSONObject out = new JSONObject();
            out.put("model", j.optString("model", "qwen3.8-max"));
            out.put("stream", true);
            if (j.has("temperature") && !j.isNull("temperature")) {
                out.put("temperature", j.optDouble("temperature"));
            }
            JSONArray messages = new JSONArray();
            String instr = j.optString("instructions", "");
            if (instr.length() > 0) {
                messages.put(new JSONObject().put("role", "system").put("content", instr));
            }
            JSONArray input = j.optJSONArray("input");
            if (input != null) {
                boolean lastWasTools = false;
                JSONObject openAssistant = null;
                StringBuilder pendReason = new StringBuilder();
                for (int i = 0; i < input.length(); i++) {
                    JSONObject item = input.optJSONObject(i);
                    if (item == null) continue;
                    String t = item.optString("type");
                    if ("reasoning".equals(t)) {
                        String th = reasoningText(item);
                        if (th.length() > 0) pendReason.append(th);
                    } else if ("message".equals(t)) {
                        String role = item.optString("role", "user");
                        if ("developer".equals(role)) role = "system";
                        String text = textOfContent(item.opt("content"));
                        JSONObject m = new JSONObject().put("role", role).put("content", text);
                        if ("assistant".equals(role)) {
                            if (pendReason.length() > 0) {
                                m.put("reasoning_content", pendReason.toString());
                                pendReason.setLength(0);
                            } else if (fillMissingReasoning) {
                                m.put("reasoning_content", "");
                            }
                        } else {
                            pendReason.setLength(0);
                        }
                        messages.put(m);
                        lastWasTools = false;
                        openAssistant = null;
                    } else if ("function_call".equals(t)) {
                        String callId = item.optString("call_id");
                        String name = item.optString("name");
                        String ns = item.optString("namespace", "");
                        String fq = ns.length() > 0 ? ns + "__" + name : name;
                        String args = item.optString("arguments", "{}");
                        if (!lastWasTools || openAssistant == null) {
                            openAssistant = new JSONObject();
                            openAssistant.put("role", "assistant");
                            openAssistant.put("content", JSONObject.NULL);
                            openAssistant.put("tool_calls", new JSONArray());
                            if (pendReason.length() > 0) {
                                openAssistant.put("reasoning_content", pendReason.toString());
                                pendReason.setLength(0);
                            } else if (fillMissingReasoning) {
                                openAssistant.put("reasoning_content", "");
                            }
                            messages.put(openAssistant);
                        }
                        JSONObject tc = new JSONObject();
                        tc.put("id", callId);
                        tc.put("type", "function");
                        tc.put("function", new JSONObject().put("name", fq).put("arguments", args));
                        openAssistant.getJSONArray("tool_calls").put(tc);
                        lastWasTools = true;
                    } else if ("function_call_output".equals(t)) {
                        String callId = item.optString("call_id");
                        String output = textOfContent(item.opt("output"));
                        JSONObject m = new JSONObject();
                        m.put("role", "tool");
                        m.put("tool_call_id", callId);
                        m.put("content", output);
                        messages.put(m);
                        lastWasTools = false;
                        openAssistant = null;
                        pendReason.setLength(0);
                    }
                }
            }
            out.put("messages", messages);
            JSONArray tools = j.optJSONArray("tools");
            if (tools != null && tools.length() > 0) {
                JSONArray ct = new JSONArray();
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject tt = tools.optJSONObject(i);
                    if (tt == null) continue;
                    String type = tt.optString("type");
                    if ("function".equals(type)) {
                        addChatTool(ct, seen, tt, "");
                    } else if ("namespace".equals(type)) {
                        JSONArray subs = tt.optJSONArray("tools");
                        if (subs != null) {
                            for (int k = 0; k < subs.length(); k++) {
                                JSONObject sub = subs.optJSONObject(k);
                                if (sub != null && "function".equals(sub.optString("type"))) {
                                    addChatTool(ct, seen, sub, tt.optString("name"));
                                }
                            }
                        }
                    }
                }
                if (ct.length() > 0) {
                    out.put("tools", ct);
                    Object tch = mapToolChoice(j.opt("tool_choice"));
                    out.put("tool_choice", tch != null ? tch : "auto");
                }
            }
            out.put("stream_options", new JSONObject().put("include_usage", true));
            return out.toString();
        } catch (Exception e) {
            System.out.println("[PX] responsesToChat err " + e);
            return bodyStr;
        }
    }

    static void addChatTool(JSONArray ct, java.util.HashSet<String> seen, JSONObject fn, String ns)
            throws org.json.JSONException {
        String base = fn.optString("name");
        if (base.length() == 0) return;
        String name = ns != null && ns.length() > 0 ? ns + "__" + base : base;
        if (seen.contains(name)) return;
        seen.add(name);
        JSONObject cf = new JSONObject();
        cf.put("name", name);
        String desc = fn.optString("description", "");
        if (desc.length() > 0) cf.put("description", desc);
        Object params = fn.opt("parameters");
        if (params instanceof JSONObject) cf.put("parameters", params);
        ct.put(new JSONObject().put("type", "function").put("function", cf));
    }

    static String responsesToAnthropic(String bodyStr) {
        try {
            JSONObject in = new JSONObject(bodyStr);
            JSONObject out = new JSONObject();
            out.put("model", in.optString("model", "glm-5.3-flash"));
            String sys = in.optString("instructions", "");
            if (sys.length() > 0) out.put("system", sys);
            out.put("max_tokens", in.optInt("max_output_tokens", 8192));
            out.put("stream", true);
            if (in.has("temperature") && !in.isNull("temperature")) {
                out.put("temperature", in.optDouble("temperature"));
            }
            JSONArray msgs = new JSONArray();
            JSONArray input = in.optJSONArray("input");
            if (input == null) input = new JSONArray();
            StringBuilder pendCallText = new StringBuilder();
            JSONArray pendCalls = new JSONArray();
            JSONArray pendThinks = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                JSONObject it = input.optJSONObject(i);
                if (it == null) continue;
                String type = it.optString("type", "message");
                if (type.equals("reasoning")) {
                    String th = reasoningText(it);
                    if (th.length() > 0) {
                        pendThinks.put(new JSONObject().put("thinking", th)
                                .put("signature", optStr(it, "encrypted_content", "")));
                    }
                } else if (type.equals("message")) {
                    String role = it.optString("role", "user");
                    if (role.equals("system") || role.equals("developer")) role = "user";
                    String content = textOfContent(it.opt("content"));
                    if (pendCalls.length() > 0) {
                        JSONObject am = new JSONObject().put("role", "assistant");
                        JSONArray ct = new JSONArray();
                        putThinking(ct, pendThinks);
                        pendThinks = new JSONArray();
                        if (pendCallText.length() > 0) {
                            ct.put(new JSONObject().put("type", "text").put("text", pendCallText.toString()));
                        }
                        for (int k = 0; k < pendCalls.length(); k++) ct.put(pendCalls.get(k));
                        am.put("content", ct);
                        msgs.put(am);
                        pendCallText.setLength(0);
                        pendCalls = new JSONArray();
                    }
                    if (content.length() > 0 || (role.equals("assistant") && pendThinks.length() > 0)) {
                        if (role.equals("assistant") && pendThinks.length() > 0) {
                            JSONArray ct = new JSONArray();
                            putThinking(ct, pendThinks);
                            pendThinks = new JSONArray();
                            if (content.length() > 0) {
                                ct.put(new JSONObject().put("type", "text").put("text", content));
                            }
                            msgs.put(new JSONObject().put("role", "assistant").put("content", ct));
                        } else {
                            msgs.put(new JSONObject().put("role", role)
                                    .put("content", content));
                        }
                    }
                } else if (type.equals("function_call")) {
                    String ns = it.optString("namespace", "");
                    String fq = ns.length() > 0 ? ns + "__" + it.optString("name", "") : it.optString("name", "");
                    JSONObject tc = new JSONObject().put("type", "tool_use")
                            .put("id", it.optString("call_id", ""))
                            .put("name", fq);
                    String args = it.optString("arguments", "{}");
                    JSONObject parsed;
                    try {
                        parsed = new JSONObject(args);
                    } catch (Exception e) {
                        parsed = new JSONObject();
                    }
                    tc.put("input", parsed);
                    pendCalls.put(tc);
                } else if (type.equals("function_call_output")) {
                    if (pendCalls.length() > 0) {
                        JSONObject am = new JSONObject().put("role", "assistant");
                        JSONArray ct = new JSONArray();
                        putThinking(ct, pendThinks);
                        pendThinks = new JSONArray();
                        if (pendCallText.length() > 0) {
                            ct.put(new JSONObject().put("type", "text").put("text", pendCallText.toString()));
                        }
                        for (int k = 0; k < pendCalls.length(); k++) ct.put(pendCalls.get(k));
                        am.put("content", ct);
                        msgs.put(am);
                        pendCallText.setLength(0);
                        pendCalls = new JSONArray();
                    }
                    String outStr = optStr(it, "output", "");
                    try {
                        JSONObject oo = new JSONObject(outStr);
                        JSONArray oc = oo.optJSONArray("content");
                        if (oc != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int k = 0; k < oc.length(); k++) {
                                JSONObject ob = oc.optJSONObject(k);
                                if (ob != null) sb.append(optStr(ob, "text", ""));
                            }
                            outStr = sb.toString();
                        }
                    } catch (Exception ig) { }
                    JSONArray ct = new JSONArray().put(new JSONObject().put("type", "tool_result")
                            .put("tool_use_id", it.optString("call_id", ""))
                            .put("content", outStr));
                    msgs.put(new JSONObject().put("role", "user").put("content", ct));
                }
            }
            if (pendCalls.length() > 0) {
                JSONObject am = new JSONObject().put("role", "assistant");
                JSONArray ct = new JSONArray();
                putThinking(ct, pendThinks);
                pendThinks = new JSONArray();
                if (pendCallText.length() > 0) {
                    ct.put(new JSONObject().put("type", "text").put("text", pendCallText.toString()));
                }
                for (int k = 0; k < pendCalls.length(); k++) ct.put(pendCalls.get(k));
                am.put("content", ct);
                msgs.put(am);
            }
            out.put("messages", msgs);
            JSONArray rtools = in.optJSONArray("tools");
            if (rtools != null && rtools.length() > 0) {
                JSONArray at = new JSONArray();
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                for (int i = 0; i < rtools.length(); i++) {
                    JSONObject t = rtools.optJSONObject(i);
                    if (t == null) continue;
                    String ttype = t.optString("type", "function");
                    if ("namespace".equals(ttype)) {
                        JSONArray subs = t.optJSONArray("tools");
                        String nsName = t.optString("name", "");
                        if (subs != null) {
                            for (int k = 0; k < subs.length(); k++) {
                                JSONObject sub = subs.optJSONObject(k);
                                if (sub != null) addAnthTool(at, seen, sub, nsName);
                            }
                        }
                        continue;
                    }
                    if (!ttype.equals("function")) continue;
                    JSONObject fn = t.optJSONObject("function") != null
                            ? t.optJSONObject("function") : t;
                    addAnthTool(at, seen, fn, "");
                    JSONArray subs = fn.optJSONArray("subfunctions");
                    if (subs != null) {
                        String nsName = fn.optString("name", "");
                        for (int k = 0; k < subs.length(); k++) {
                            JSONObject sub = subs.optJSONObject(k);
                            if (sub != null) addAnthTool(at, seen, sub, nsName);
                        }
                    }
                }
                if (at.length() > 0) out.put("tools", at);
            }
            return out.toString();
        } catch (Exception e) {
            System.out.println("[PX] toAnthropic err " + e);
            return "{\"model\":\"glm-5.3-flash\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"error\"}]}";
        }
    }

    static Object mapToolChoice(Object tc) {
        if (tc == null || tc == JSONObject.NULL) return null;
        if (tc instanceof String) return tc;
        if (tc instanceof JSONObject) {
            JSONObject o = (JSONObject) tc;
            String name = optStr(o, "name", "");
            if (name.length() == 0) {
                JSONObject fn = o.optJSONObject("function");
                if (fn != null) name = optStr(fn, "name", "");
            }
            if (name.length() == 0) return null;
            try {
                return new JSONObject().put("type", "function")
                        .put("function", new JSONObject().put("name", name));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static void putThinking(JSONArray ct, JSONArray pairs) throws org.json.JSONException {
        for (int i = 0; i < pairs.length(); i++) {
            JSONObject p = pairs.optJSONObject(i);
            if (p == null) continue;
            String th = optStr(p, "thinking", "");
            if (th.length() == 0) continue;
            ct.put(new JSONObject().put("type", "thinking").put("thinking", th)
                    .put("signature", optStr(p, "signature", "")));
        }
    }

    static void addAnthTool(JSONArray at, java.util.HashSet<String> seen, JSONObject fn, String ns)
            throws org.json.JSONException {
        String bare = fn.optString("name", "");
        if (bare.length() == 0) return;
        String fq = ns.length() > 0 ? ns + "__" + bare : bare;
        if (seen.contains(fq)) return;
        seen.add(fq);
        JSONObject t = new JSONObject().put("name", fq)
                .put("description", fn.optString("description", ""));
        JSONObject schema = fn.optJSONObject("parameters");
        if (schema == null) schema = fn.optJSONObject("input_schema");
        if (schema == null) schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject());
        t.put("input_schema", schema);
        at.put(t);
    }

    private static void pipeAnthropic(String bodyStr, String key, Socket s) {
        pipeAnthropic(bodyStr, key, s, System.getProperty("px.anthropic.url",
                "https://open.bigmodel.cn/api/anthropic/v1/messages"));
    }

    private static void pipeAnthropic(String bodyStr, String key, Socket s, String anthropicUrl) {
        OutputStream os = null;
        final long estIn = estTokens(bodyStr);
        try {
            byte[] payload = bodyStr.getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(anthropicUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(600000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("x-api-key", key);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            OutputStream up = conn.getOutputStream();
            up.write(payload);
            up.flush();
            up.close();
            System.out.println("[PX] anthropic connecting");
            int code = conn.getResponseCode();
            System.out.println("[PX] anthropic code=" + code);
            os = s.getOutputStream();
            if (code != 200) {
                InputStream err = conn.getErrorStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (err != null) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = err.read(b)) > 0) bos.write(b, 0, n);
                    err.close();
                }
                String detail = "";
                try {
                    JSONObject eo = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                    JSONObject inner = eo.optJSONObject("error");
                    if (inner != null) {
                        detail = inner.optString("message", "");
                        String ecode = inner.optString("code", "");
                        if (ecode.length() > 0) detail = "[code " + ecode + "] " + detail;
                    }
                } catch (Exception ig) { }
                if (detail.length() == 0) detail = "上游 HTTP " + code;
                System.out.println("[PX] anthropic err detail=" + detail);
                String respId = "resp_" + System.currentTimeMillis();
                StringBuilder ev = new StringBuilder();
                ev.append(sse("response.created", new JSONObject().put("type", "response.created")
                        .put("response", new JSONObject().put("id", respId))));
                ev.append(sse("response.failed", new JSONObject().put("type", "response.failed")
                        .put("response", new JSONObject().put("id", respId)
                                .put("status", "failed")
                                .put("error", new JSONObject().put("code", String.valueOf(code))
                                        .put("message", "模型服务报错: " + detail)))));
                os.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write(ev.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                s.close();
                return;
            }
            java.io.BufferedReader upIn = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            os.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            String respId = "resp_" + System.currentTimeMillis();
            long inTok = estIn, outTok = 0;
            int outIdx = 0;
            String mid = "msg_0_" + System.currentTimeMillis();
            String fid = "fc_0_" + System.currentTimeMillis();
            boolean created = false, msgOpen = false, toolOpen = false, thinkOpen = false;
            StringBuilder text = new StringBuilder();
            StringBuilder argsBuf = new StringBuilder();
            StringBuilder thinkBuf = new StringBuilder();
            StringBuilder thinkSig = new StringBuilder();
            String callId = "", fqName = "", ns = "", bare = "";
            StringBuilder dataBuf = new StringBuilder();
            boolean failed = false;
            String ln;
            while ((ln = upIn.readLine()) != null) {
                if (ln.startsWith("data:")) {
                    dataBuf.append(ln.substring(5).trim());
                    continue;
                }
                if (ln.length() != 0) continue;
                if (dataBuf.length() == 0) continue;
                String data = dataBuf.toString();
                dataBuf.setLength(0);
                JSONObject ev;
                try {
                    ev = new JSONObject(data);
                } catch (Exception e) {
                    continue;
                }
                String et = ev.optString("type", "");
                if (et.equals("message_start")) {
                    JSONObject m = ev.optJSONObject("message");
                    if (m != null) {
                        JSONObject u = m.optJSONObject("usage");
                        if (u != null) {
                            long t = u.optLong("input_tokens", 0)
                                    + u.optLong("cache_read_input_tokens", 0)
                                    + u.optLong("cache_creation_input_tokens", 0);
                            if (t > inTok) inTok = t;
                        }
                    }
                    os.write(sse("response.created", new JSONObject().put("type", "response.created")
                            .put("response", new JSONObject().put("id", respId))).getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    created = true;
                } else if (et.equals("content_block_start")) {
                    JSONObject cb = ev.optJSONObject("content_block");
                    if (cb == null) continue;
                    String bt = cb.optString("type", "");
                    if (bt.equals("thinking") || bt.equals("redacted_thinking")) {
                        thinkOpen = true;
                        thinkBuf.setLength(0);
                        thinkSig.setLength(0);
                        thinkBuf.append(optStr(cb, "thinking", ""));
                        thinkSig.append(optStr(cb, "signature", ""));
                        JSONObject ri = new JSONObject().put("type", "reasoning")
                                .put("id", "rs_" + outIdx + "_" + System.currentTimeMillis())
                                .put("summary", new JSONArray());
                        os.write(sse("response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                                .put("output_index", outIdx).put("item", ri)).getBytes(StandardCharsets.UTF_8));
                        os.write(sse("response.reasoning_summary_part.added", new JSONObject()
                                .put("type", "response.reasoning_summary_part.added")
                                .put("output_index", outIdx).put("summary_index", 0)
                                .put("part", new JSONObject().put("type", "summary_text").put("text", "")))
                                .getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } else if (bt.equals("tool_use")) {
                        if (msgOpen) {
                            os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                                    .put("output_index", outIdx)
                                    .put("item", new JSONObject().put("type", "message").put("role", "assistant")
                                            .put("id", mid).put("content", new JSONArray().put(
                                                    new JSONObject().put("type", "output_text").put("text", text.toString()))))).getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            msgOpen = false;
                            outIdx++;
                        }
                        callId = cb.optString("id", "");
                        fqName = cb.optString("name", "");
                        ns = "";
                        bare = fqName;
                        int pp = fqName.lastIndexOf("__");
                        if (pp > 0) {
                            ns = fqName.substring(0, pp);
                            bare = fqName.substring(pp + 2);
                        }
                        fid = "fc_" + outIdx + "_" + System.currentTimeMillis();
                        argsBuf.setLength(0);
                        JSONObject item = new JSONObject().put("type", "function_call").put("id", fid)
                                .put("call_id", callId).put("name", bare).put("arguments", "");
                        if (ns.length() > 0) item.put("namespace", ns);
                        os.write(sse("response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                                .put("output_index", outIdx)
                                .put("item", item)).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        toolOpen = true;
                    } else if (bt.equals("text")) {
                        if (!msgOpen) {
                            mid = "msg_" + outIdx + "_" + System.currentTimeMillis();
                            text.setLength(0);
                            os.write(sse("response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                                    .put("output_index", outIdx)
                                    .put("item", new JSONObject().put("type", "message").put("role", "assistant")
                                            .put("id", mid).put("content", new JSONArray()))).getBytes(StandardCharsets.UTF_8));
                            os.write(sse("response.output_text.added", new JSONObject().put("type", "response.output_text.added")
                                    .put("item_id", mid).put("output_index", outIdx).put("content_index", 0)
                                    .put("delta", "")).getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            msgOpen = true;
                        }
                    }
                } else if (et.equals("content_block_delta")) {
                    JSONObject dl = ev.optJSONObject("delta");
                    if (dl == null) continue;
                    String dt = dl.optString("type", "");
                    if (dt.equals("thinking_delta")) {
                        String th = optStr(dl, "thinking", "");
                        if (th.length() == 0) th = optStr(dl, "text", "");
                        if (th.length() > 0 && thinkOpen) {
                            thinkBuf.append(th);
                            os.write(sse("response.reasoning_summary_text.delta", new JSONObject()
                                    .put("type", "response.reasoning_summary_text.delta")
                                    .put("output_index", outIdx).put("summary_index", 0)
                                    .put("delta", th)).getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    } else if (dt.equals("signature_delta")) {
                        thinkSig.setLength(0);
                        thinkSig.append(optStr(dl, "signature", ""));
                    } else if (dt.equals("text_delta")) {
                        String t = optStr(dl, "text", "");
                        if (t.length() > 0 && msgOpen) {
                            text.append(t);
                            os.write(sse("response.output_text.delta", new JSONObject().put("type", "response.output_text.delta")
                                    .put("item_id", mid).put("output_index", outIdx)
                                    .put("delta", t)).getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    } else if (dt.equals("input_json_delta")) {
                        argsBuf.append(optStr(dl, "partial_json", ""));
                    }
                } else if (et.equals("content_block_stop")) {
                    if (thinkOpen) {
                        thinkOpen = false;
                        String th = thinkBuf.toString();
                        if (th.length() > 0) {
                            os.write(sse("response.reasoning_summary_text.done", new JSONObject()
                                    .put("type", "response.reasoning_summary_text.done")
                                    .put("output_index", outIdx).put("summary_index", 0)
                                    .put("text", th)).getBytes(StandardCharsets.UTF_8));
                        }
                        JSONObject ri = new JSONObject().put("type", "reasoning")
                                .put("id", "rs_" + outIdx + "_" + System.currentTimeMillis())
                                .put("summary", new JSONArray().put(new JSONObject()
                                        .put("type", "summary_text").put("text", th)));
                        if (thinkSig.length() > 0) {
                            ri.put("encrypted_content", thinkSig.toString());
                        }
                        os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                                .put("output_index", outIdx).put("item", ri)).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        outIdx++;
                        continue;
                    }
                    if (toolOpen) {
                        String args = argsBuf.length() > 0 ? argsBuf.toString() : "{}";
                        JSONObject item = new JSONObject().put("type", "function_call").put("id", fid)
                                .put("call_id", callId).put("name", bare).put("arguments", args);
                        if (ns.length() > 0) item.put("namespace", ns);
                        os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                                .put("output_index", outIdx)
                                .put("item", item)).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        toolOpen = false;
                        outIdx++;
                    } else if (msgOpen) {
                        os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                                .put("output_index", outIdx)
                                .put("item", new JSONObject().put("type", "message").put("role", "assistant")
                                        .put("id", mid).put("content", new JSONArray().put(
                                                new JSONObject().put("type", "output_text").put("text", text.toString()))))).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        msgOpen = false;
                        outIdx++;
                    }
                } else if (et.equals("message_delta")) {
                    JSONObject u = ev.optJSONObject("usage");
                    if (u != null) outTok = u.optLong("output_tokens", 0);
                } else if (et.equals("error")) {
                    JSONObject eo = ev.optJSONObject("error");
                    String detail = eo != null ? eo.optString("message", "stream error") : "stream error";
                    os.write(sse("response.failed", new JSONObject().put("type", "response.failed")
                            .put("response", new JSONObject().put("id", respId)
                                    .put("status", "failed")
                                    .put("error", new JSONObject().put("code", "stream")
                                            .put("message", "模型服务报错: " + detail)))).getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    failed = true;
                    break;
                } else if (et.equals("message_stop")) {
                    break;
                }
            }
            upIn.close();
            conn.disconnect();
            if (!created) {
                os.write(sse("response.created", new JSONObject().put("type", "response.created")
                        .put("response", new JSONObject().put("id", respId))).getBytes(StandardCharsets.UTF_8));
            }
            if (thinkOpen) {
                thinkOpen = false;
                JSONObject ri = new JSONObject().put("type", "reasoning")
                        .put("id", "rs_" + outIdx + "_" + System.currentTimeMillis())
                        .put("summary", new JSONArray().put(new JSONObject()
                                .put("type", "summary_text").put("text", thinkBuf.toString())));
                if (thinkSig.length() > 0) ri.put("encrypted_content", thinkSig.toString());
                os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                        .put("output_index", outIdx).put("item", ri)).getBytes(StandardCharsets.UTF_8));
                outIdx++;
            }
            if (msgOpen) {
                os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                        .put("output_index", outIdx)
                        .put("item", new JSONObject().put("type", "message").put("role", "assistant")
                                .put("id", mid).put("content", new JSONArray().put(
                                        new JSONObject().put("type", "output_text").put("text", text.toString()))))).getBytes(StandardCharsets.UTF_8));
                outIdx++;
            }
            if (toolOpen) {
                String args = argsBuf.length() > 0 ? argsBuf.toString() : "{}";
                JSONObject item = new JSONObject().put("type", "function_call").put("id", fid)
                        .put("call_id", callId).put("name", bare).put("arguments", args);
                if (ns.length() > 0) item.put("namespace", ns);
                os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                        .put("output_index", outIdx)
                        .put("item", item)).getBytes(StandardCharsets.UTF_8));
                outIdx++;
            }
            if (!failed) {
                os.write(sse("response.completed", new JSONObject().put("type", "response.completed")
                        .put("response", new JSONObject().put("id", respId)
                                .put("usage", new JSONObject().put("input_tokens", inTok)
                                        .put("output_tokens", outTok)
                                        .put("total_tokens", inTok + outTok)))).getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
            s.close();
        } catch (Exception e) {
            System.out.println("[PX] pipeAnthropic err " + e);
            try { s.close(); } catch (Exception ig) { }
        }
    }

    static String stripToken(String real, String path) {
        if (real == null || real.length() == 0 || path == null) return null;
        int qm = path.indexOf('?');
        String p = qm >= 0 ? path.substring(0, qm) : path;
        if (!p.startsWith("/t/")) return null;
        String rest = p.substring(3);
        int sl = rest.indexOf('/');
        String tok = sl < 0 ? rest : rest.substring(0, sl);
        if (!real.equals(tok)) return null;
        return sl < 0 ? "/" : rest.substring(sl);
    }

    static String redactTk(String s) {
        if (s == null) return "";
        int i = s.indexOf("/t/");
        if (i < 0) return s;
        int j = s.indexOf('/', i + 3);
        return j < 0 ? s.substring(0, i) + "/t/<tk>"
                : s.substring(0, i) + "/t/<tk>" + s.substring(j);
    }

    static String upstreamFor(String path) {
        if (path.startsWith("/ds/")) return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        if (path.startsWith("/zp/")) return "https://open.bigmodel.cn/api/paas/v4/chat/completions";
        if (path.startsWith("/dp/")) return "https://api.deepseek.com/v1/chat/completions";
        if (path.startsWith("/km/")) return "https://api.moonshot.cn/v1/chat/completions";
        return null;
    }

    static String providerFor(String path) {
        if (path.startsWith("/ds/")) return "dashscope";
        if (path.startsWith("/zp/")) return "zhipu";
        if (path.startsWith("/dp/")) return "deepseek";
        if (path.startsWith("/km/")) return "kimi";
        return "";
    }

    private static void pipeChat(String upstream, String bodyStr, String key, Socket s) {
        final long estIn = estTokens(bodyStr);
        java.io.BufferedReader upIn = null;
        OutputStream os = null;
        try {
            byte[] payload = bodyStr.getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(upstream).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(600000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            OutputStream up = conn.getOutputStream();
            up.write(payload);
            up.flush();
            up.close();
            System.out.println("[PX] upstream connecting");
            int code = conn.getResponseCode();
            System.out.println("[PX] upstream code=" + code);
            os = s.getOutputStream();
            if (code != 200) {
                InputStream err = conn.getErrorStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (err != null) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = err.read(b)) > 0) bos.write(b, 0, n);
                    err.close();
                }
                byte[] body = bos.toByteArray();
                os.write(("HTTP/1.1 " + code + " " + msg(code) + "\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(body);
                os.flush();
                conn.disconnect();
                s.close();
                return;
            }
            os.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            upIn = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String respId = "resp_" + System.currentTimeMillis();
            StringBuilder reason = new StringBuilder();
            StringBuilder text = new StringBuilder();
            java.util.ArrayList<String[]> toolCalls = new java.util.ArrayList<String[]>();
            int outIdx = 0;
            String rsId = "", mid = "";
            boolean reasonOpen = false, msgOpen = false, created = false;
            long inTok = estIn, outTok = 0;
            boolean done = false;
            while (!done) {
                String ln = upIn.readLine();
                if (ln == null) break;
                if (!ln.startsWith("data:")) continue;
                String data = ln.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                JSONObject ch;
                try {
                    ch = new JSONObject(data);
                } catch (Exception e) {
                    continue;
                }
                if (!created) {
                    os.write(sse("response.created", new JSONObject().put("type", "response.created")
                            .put("response", new JSONObject().put("id", respId))).getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    created = true;
                }
                if (ch.has("usage") && !ch.isNull("usage")) {
                    JSONObject u = ch.optJSONObject("usage");
                    if (u != null) {
                        long t = u.optLong("prompt_tokens", 0);
                        if (t > inTok) inTok = t;
                        outTok = u.optLong("completion_tokens", 0);
                    }
                }
                JSONArray choices = ch.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject c0 = choices.optJSONObject(0);
                if (c0 == null) continue;
                JSONObject delta = c0.optJSONObject("delta");
                String finish = optStr(c0, "finish_reason", "");
                if (delta != null) {
                    String rc = optStr(delta, "reasoning_content", "");
                    if (rc.length() == 0) rc = optStr(delta, "reasoning", "");
                    if (rc.length() > 0) {
                        if (!reasonOpen) {
                            reasonOpen = true;
                            rsId = "rs_" + outIdx + "_" + System.currentTimeMillis();
                            os.write(sse("response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                                    .put("output_index", outIdx)
                                    .put("item", new JSONObject().put("type", "reasoning").put("id", rsId)
                                            .put("summary", new JSONArray()))).getBytes(StandardCharsets.UTF_8));
                            os.write(sse("response.reasoning_summary_part.added", new JSONObject()
                                    .put("type", "response.reasoning_summary_part.added")
                                    .put("output_index", outIdx).put("summary_index", 0)
                                    .put("part", new JSONObject().put("type", "summary_text").put("text", "")))
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                        reason.append(rc);
                        os.write(sse("response.reasoning_summary_text.delta", new JSONObject()
                                .put("type", "response.reasoning_summary_text.delta")
                                .put("output_index", outIdx).put("summary_index", 0)
                                .put("delta", rc)).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    String content = optStr(delta, "content", "");
                    if (content.length() > 0) {
                        if (reasonOpen) {
                            emitReasoningDone(os, rsId, outIdx, reason.toString());
                            reasonOpen = false;
                            outIdx++;
                        }
                        if (!msgOpen) {
                            msgOpen = true;
                            mid = "msg_" + outIdx + "_" + System.currentTimeMillis();
                            text.setLength(0);
                            os.write(sse("response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                                    .put("output_index", outIdx)
                                    .put("item", new JSONObject().put("type", "message").put("role", "assistant")
                                            .put("id", mid).put("content", new JSONArray()))).getBytes(StandardCharsets.UTF_8));
                            os.write(sse("response.output_text.added", new JSONObject().put("type", "response.output_text.added")
                                    .put("item_id", mid).put("output_index", outIdx).put("content_index", 0)
                                    .put("delta", "")).getBytes(StandardCharsets.UTF_8));
                        }
                        text.append(content);
                        os.write(sse("response.output_text.delta", new JSONObject().put("type", "response.output_text.delta")
                                .put("item_id", mid).put("output_index", outIdx)
                                .put("delta", content)).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    JSONArray tcs = delta.optJSONArray("tool_calls");
                    if (tcs != null) {
                        if (reasonOpen) {
                            emitReasoningDone(os, rsId, outIdx, reason.toString());
                            reasonOpen = false;
                            outIdx++;
                        }
                        if (msgOpen) {
                            os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                                    .put("output_index", outIdx)
                                    .put("item", msgItem(mid, text.toString()))).getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            msgOpen = false;
                            outIdx++;
                        }
                        for (int i = 0; i < tcs.length(); i++) {
                            JSONObject tc = tcs.optJSONObject(i);
                            if (tc == null) continue;
                            int idx = tc.optInt("index", 0);
                            while (toolCalls.size() <= idx) toolCalls.add(new String[]{null, "", ""});
                            String[] slot = toolCalls.get(idx);
                            String id = tc.optString("id", "");
                            if (id.length() > 0) slot[0] = id;
                            JSONObject fn = tc.optJSONObject("function");
                            if (fn != null) {
                                String nm = optStr(fn, "name", "");
                                if (nm.length() > 0 && (slot[1] == null || slot[1].length() == 0)) slot[1] = nm;
                                slot[2] = slot[2] + optStr(fn, "arguments", "");
                            }
                        }
                    }
                }
                if (finish.length() > 0 && "stop".equals(finish)) done = true;
            }
            upIn.close();
            conn.disconnect();
            if (!created) {
                os.write(sse("response.created", new JSONObject().put("type", "response.created")
                        .put("response", new JSONObject().put("id", respId))).getBytes(StandardCharsets.UTF_8));
            }
            if (reasonOpen) {
                emitReasoningDone(os, rsId, outIdx, reason.toString());
                reasonOpen = false;
                outIdx++;
            }
            if (msgOpen) {
                os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                        .put("output_index", outIdx)
                        .put("item", msgItem(mid, text.toString()))).getBytes(StandardCharsets.UTF_8));
                msgOpen = false;
                outIdx++;
            }
            for (int i = 0; i < toolCalls.size(); i++) {
                String[] tc = toolCalls.get(i);
                if (tc == null || tc[1] == null || tc[1].length() == 0) continue;
                String callId = tc[0] != null ? tc[0] : ("call_" + outIdx + "_" + System.currentTimeMillis());
                String fid = "fc_" + outIdx + "_" + System.currentTimeMillis();
                String args = tc[2] != null && tc[2].length() > 0 ? tc[2] : "{}";
                int p = tc[1].lastIndexOf("__");
                String ns = p > 0 ? tc[1].substring(0, p) : "";
                String bare = p > 0 ? tc[1].substring(p + 2) : tc[1];
                JSONObject item = new JSONObject().put("type", "function_call").put("id", fid)
                        .put("call_id", callId).put("name", bare).put("arguments", args);
                if (ns.length() > 0) item.put("namespace", ns);
                os.write(sse("response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                        .put("output_index", outIdx)
                        .put("item", new JSONObject(item.toString()).put("arguments", ""))).getBytes(StandardCharsets.UTF_8));
                os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                        .put("output_index", outIdx).put("item", item)).getBytes(StandardCharsets.UTF_8));
                outIdx++;
            }
            os.write(sse("response.completed", new JSONObject().put("type", "response.completed")
                    .put("response", new JSONObject().put("id", respId)
                            .put("usage", new JSONObject().put("input_tokens", inTok)
                                    .put("output_tokens", outTok)
                                    .put("total_tokens", inTok + outTok)))).getBytes(StandardCharsets.UTF_8));
            os.flush();
            s.close();
        } catch (Exception e) {
            System.out.println("[PX] pipeChat err " + e);
            try { if (upIn != null) upIn.close(); } catch (Exception ig) { }
            try { s.close(); } catch (Exception ig) { }
        }
    }

    private static JSONObject msgItem(String id, String text) throws org.json.JSONException {
        return new JSONObject().put("type", "message").put("role", "assistant").put("id", id)
                .put("content", new JSONArray().put(
                        new JSONObject().put("type", "output_text").put("text", text)));
    }

    private static void emitReasoningDone(OutputStream os, String id, int outIdx, String text) throws Exception {
        os.write(sse("response.reasoning_summary_text.done", new JSONObject()
                .put("type", "response.reasoning_summary_text.done")
                .put("output_index", outIdx).put("summary_index", 0)
                .put("text", text)).getBytes(StandardCharsets.UTF_8));
        os.write(sse("response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                .put("output_index", outIdx)
                .put("item", new JSONObject().put("type", "reasoning").put("id", id)
                        .put("summary", new JSONArray().put(new JSONObject()
                                .put("type", "summary_text").put("text", text))))).getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String sse(String type, JSONObject data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    private static String msg(int code) {
        if (code == 200) return "OK";
        if (code == 400) return "Bad Request";
        if (code == 401) return "Unauthorized";
        if (code == 429) return "Too Many Requests";
        if (code == 404) return "Not Found";
        return "Error";
    }
}
