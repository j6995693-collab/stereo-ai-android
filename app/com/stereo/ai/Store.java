package com.stereo.ai;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;

public class Store {
    public static final String TAG = "StereoStore";

    public static File codexHome(Context c) {
        File d = new File(c.getFilesDir(), "codex_home");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File workDir(Context c) {
        File d = new File(c.getFilesDir(), "work");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File uploadsDir(Context c) {
        File d = new File(workDir(c), "uploads");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File audioDir(Context c) {
        File d = new File(c.getFilesDir(), "audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File keysFile(Context c) {
        return new File(codexHome(c), "keys.json");
    }

    public static File bundleDir(Context c) {
        return new File(c.getFilesDir(), "bundle");
    }

    public static synchronized void extractBundle(Context c) {
        try {
            File bd = bundleDir(c);
            File ver = new File(bd, ".ver");
            if (ver.isFile()) {
                byte[] vb = read(ver);
                if (vb != null && BundleVer.VER.equals(
                        new String(vb, "UTF-8").trim())) return;
            }
            rdelete(bd);
            copyAssetDir(c.getAssets(), "bundle", bd);
            FileOutputStream o = new FileOutputStream(ver);
            o.write(BundleVer.VER.getBytes("UTF-8"));
            o.close();
            Log.i(TAG, "bundle extracted to " + bd);
        } catch (Exception e) {
            Log.e(TAG, "extractBundle", e);
        }
    }

    private static void rdelete(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) rdelete(x);
        f.delete();
    }

    private static void copyAssetDir(AssetManager am, String ap, File out) throws Exception {
        String[] kids = am.list(ap);
        out.mkdirs();
        if (kids == null || kids.length == 0) {
            copyAssetFile(am, ap, out);
            return;
        }
        for (String k : kids) {
            String np = ap + "/" + k;
            String[] sub = am.list(np);
            if (sub != null && sub.length > 0) copyAssetDir(am, np, new File(out, k));
            else copyAssetFile(am, np, out);
        }
    }

    private static void copyAssetFile(AssetManager am, String ap, File outDir) throws Exception {
        InputStream in = am.open(ap);
        File f = new File(outDir, ap.substring(ap.lastIndexOf('/') + 1));
        FileOutputStream o = new FileOutputStream(f);
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        o.close();
        in.close();
    }

    public static File settingsFile(Context c) {
        return new File(codexHome(c), "app_settings.json");
    }

    private static synchronized JSONObject readKeysRaw(Context c) {
        try {
            byte[] b = read(keysFile(c));
            if (b != null) return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) { }
        return new JSONObject();
    }

    public static synchronized JSONObject loadKeys(Context c) {
        JSONObject raw = readKeysRaw(c);
        JSONObject out = new JSONObject();
        boolean migrated = false;
        java.util.Iterator<String> it = raw.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = safeStr(raw, k);
            if (v == null) continue;
            String p;
            if (Secret.isSealed(v)) {
                p = Secret.open(c, v);
            } else {
                p = v;
                String sealed = Secret.seal(c, v);
                if (sealed != null) {
                    try {
                        raw.put(k, sealed);
                        migrated = true;
                    } catch (Exception ig) { }
                }
            }
            if (p == null) continue;
            try {
                out.put(k, p);
            } catch (Exception ig) { }
        }
        if (migrated) {
            try {
                writeAtomic(keysFile(c), raw.toString());
                harden(keysFile(c));
            } catch (Exception e) {
                Log.e(TAG, "keys migrate", e);
            }
        }
        return out;
    }

    private static void harden(File f) {
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    public static synchronized void saveKey(Context c, String provider, String key) throws Exception {
        JSONObject o = readKeysRaw(c);
        if (key == null || key.length() == 0) {
            o.remove(provider);
        } else {
            String sealed = Secret.seal(c, key);
            if (sealed == null) {
                throw new Exception("密钥加密失败（Android Keystore 不可用），已拒绝以明文保存");
            }
            o.put(provider, sealed);
        }
        writeAtomic(keysFile(c), o.toString());
        harden(keysFile(c));
        writeConfig(c);
    }

    public static synchronized void markCallTid(Context c, String tid) {
        try {
            if (tid == null || tid.length() != 36) return;
            JSONObject stt = loadSettings(c);
            JSONArray a = stt.optJSONArray("call_tids");
            if (a == null) a = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (a.optString(i).equals(tid)) return;
            }
            a.put(tid);
            while (a.length() > 200) a.remove(0);
            stt.put("call_tids", a);
            saveSettings(c, stt);
        } catch (Exception e) { }
    }

    public static synchronized JSONObject loadSettings(Context c) {
        try {
            byte[] b = read(settingsFile(c));
            if (b != null) return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) { }
        return new JSONObject();
    }

    public static synchronized void saveSettings(Context c, JSONObject o) {
        try {
            writeAtomic(settingsFile(c), o.toString());
            harden(settingsFile(c));
        } catch (Exception e) {
            Log.e(TAG, "saveSettings", e);
        }
    }

    public static synchronized String getSetting(Context c, String k, String def) {
        String v = safeStr(loadSettings(c), k);
        return v != null ? v : def;
    }

    public static synchronized void putSetting(Context c, String k, String v) {
        try {
            JSONObject o = loadSettings(c);
            String old = safeStr(o, k);
            o.put(k, v);
            saveSettings(c, o);
            if (("model".equals(k) || "provider".equals(k))
                    && (old == null || !old.equals(v))) {
                Log.i(TAG, "setting " + k + " " + old + " -> " + v + " @" + caller());
            }
        } catch (Exception e) { }
    }

    public static synchronized String getToken(Context c) throws Exception {
        JSONObject st = loadSettings(c);
        String t = safeStr(st, "api_token");
        if (t != null && t.length() > 0) {
            if (Secret.isSealed(t)) {
                String p = Secret.open(c, t);
                if (p != null && p.length() > 0) return p;
            } else {
                String sealed = Secret.seal(c, t);
                if (sealed != null) {
                    st.put("api_token", sealed);
                    saveSettings(c, st);
                }
                return t;
            }
        }
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        t = sb.toString();
        String sealed = Secret.seal(c, t);
        if (sealed != null) {
            st.put("api_token", sealed);
            saveSettings(c, st);
        }
        return t;
    }

    private static String safeStr(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return null;
        String s = String.valueOf(v);
        return s;
    }

    private static String safeStr(JSONArray a, int i) {
        if (a == null) return null;
        Object v = a.opt(i);
        if (v == null || v == JSONObject.NULL) return null;
        return String.valueOf(v);
    }

    private static void writeAtomic(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(s.getBytes("UTF-8"));
        out.flush();
        out.close();
        if (!tmp.renameTo(f)) {
            FileOutputStream o2 = new FileOutputStream(f);
            o2.write(s.getBytes("UTF-8"));
            o2.close();
            tmp.delete();
        }
    }

    private static byte[] read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream2 bos = new ByteArrayOutputStream2();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject customProviders(Context c) {
        JSONObject st = loadSettings(c);
        JSONObject cp = st.optJSONObject("custom_providers");
        return cp == null ? new JSONObject() : cp;
    }

    public static String providerProto(Context c, String id) {
        JSONObject p = customProviders(c).optJSONObject(id);
        if (p == null) return null;
        return safeStr(p, "proto") == null ? "openai" : safeStr(p, "proto");
    }

    public static String providerBase(Context c, String id) {
        JSONObject p = customProviders(c).optJSONObject(id);
        return p == null ? null : safeStr(p, "base");
    }

    public static JSONArray providers(Context c) {
        JSONObject keys = loadKeys(c);
        JSONObject st = loadSettings(c);
        JSONObject remote = st.optJSONObject("remote_models");
        if (remote == null) remote = new JSONObject();
        JSONObject custom = st.optJSONObject("custom_models");
        if (custom == null) custom = new JSONObject();
        JSONArray arr = new JSONArray();
        String curProv = safeStr(st, "provider");
        String curModel = safeStr(st, "model");
        if (curProv != null && curProv.length() > 0 && curModel != null && curModel.length() > 0) {
            try {
                JSONArray old = remote.optJSONArray(curProv);
                JSONArray nn = new JSONArray();
                nn.put(curModel);
                if (old != null) {
                    for (int i = 0; i < old.length(); i++) {
                        String m = safeStr(old, i);
                        if (m != null && m.length() > 0 && !m.equals(curModel)) nn.put(m);
                    }
                }
                remote.put(curProv, nn);
            } catch (Exception ig) { }
        }
        try {
            JSONObject p1 = new JSONObject();
            p1.put("id", "zhipu");
            p1.put("name", "智谱 GLM");
            p1.put("base", "https://open.bigmodel.cn/api/anthropic");
            p1.put("list_url", "https://open.bigmodel.cn/api/paas/v4/models");
            p1.put("models", mergeModels(new JSONArray()
                    .put("glm-5.3-flash").put("glm-4.7").put("glm-4.6").put("glm-4.5"),
                    remote, custom, "zhipu"));
            JSONArray c1 = custom.optJSONArray("zhipu");
            p1.put("custom", c1 != null ? c1 : new JSONArray());
            p1.put("hasKey", keys.has("zhipu"));
            arr.put(p1);
            JSONObject p2 = new JSONObject();
            p2.put("id", "deepseek");
            p2.put("name", "DeepSeek");
            p2.put("base", "https://api.deepseek.com/v1");
            p2.put("list_url", "https://api.deepseek.com/models");
            p2.put("models", mergeModels(new JSONArray().put("deepseek-chat").put("deepseek-reasoner"),
                    remote, custom, "deepseek"));
            JSONArray c2 = custom.optJSONArray("deepseek");
            p2.put("custom", c2 != null ? c2 : new JSONArray());
            p2.put("hasKey", keys.has("deepseek"));
            arr.put(p2);
            JSONObject p3 = new JSONObject();
            p3.put("id", "kimi");
            p3.put("name", "Kimi 月之暗面");
            p3.put("base", "https://api.moonshot.cn/v1");
            p3.put("list_url", "https://api.moonshot.cn/v1/models");
            p3.put("models", mergeModels(new JSONArray().put("kimi-k2-turbo-preview").put("kimi-k2-0905-preview"),
                    remote, custom, "kimi"));
            JSONArray c3 = custom.optJSONArray("kimi");
            p3.put("custom", c3 != null ? c3 : new JSONArray());
            p3.put("hasKey", keys.has("kimi"));
            arr.put(p3);
            JSONObject p4 = new JSONObject();
            p4.put("id", "dashscope");
            p4.put("name", "阿里百炼 Qwen");
            p4.put("base", "https://dashscope.aliyuncs.com/compatible-mode/v1");
            p4.put("list_url", "https://dashscope.aliyuncs.com/compatible-mode/v1/models");
            p4.put("models", mergeModels(new JSONArray()
                    .put("qwen3.8-max").put("qwen3.8-flash").put("qwen3.7-plus").put("qwen-plus")
                    .put("qwen3-coder-flash"),
                    remote, custom, "dashscope"));
            JSONArray c4 = custom.optJSONArray("dashscope");
            p4.put("custom", c4 != null ? c4 : new JSONArray());
            p4.put("hasKey", keys.has("dashscope"));
            arr.put(p4);
            JSONObject cp = st.optJSONObject("custom_providers");
            if (cp != null) {
                java.util.Iterator<String> it = cp.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    if ("dashscope".equals(id) || "zhipu".equals(id)
                            || "deepseek".equals(id) || "kimi".equals(id)) continue;
                    JSONObject def = cp.optJSONObject(id);
                    if (def == null) continue;
                    JSONObject pn = new JSONObject();
                    pn.put("id", id);
                    String nm = safeStr(def, "name");
                    pn.put("name", nm == null || nm.length() == 0 ? id : nm);
                    String cb = safeStr(def, "base");
                    pn.put("base", cb);
                    String cproto = safeStr(def, "proto");
                    pn.put("proto", cproto);
                    if (cb != null && cb.length() > 0 && !"anthropic".equals(cproto)) {
                        String lb = cb.endsWith("/") ? cb.substring(0, cb.length() - 1) : cb;
                        pn.put("list_url", lb + "/models");
                    }
                    pn.put("userDefined", true);
                    pn.put("models", mergeModels(new JSONArray(),
                            st.optJSONObject("remote_models"), custom, id));
                    JSONArray cc = custom.optJSONArray(id);
                    pn.put("custom", cc != null ? cc : new JSONArray());
                    pn.put("hasKey", keys.has(id));
                    arr.put(pn);
                }
            }
        } catch (Exception e) { }
        return arr;
    }

    static JSONArray mergeModels(JSONArray def, JSONObject remote, JSONObject custom, String pid) {
        try {
            JSONArray out = new JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            JSONArray[] srcs = {remote == null ? null : remote.optJSONArray(pid), def,
                    custom == null ? null : custom.optJSONArray(pid)};
            for (JSONArray src : srcs) {
                if (src == null) continue;
                for (int i = 0; i < src.length(); i++) {
                    String m = safeStr(src, i);
                    if (m == null || m.length() == 0 || seen.contains(m)) continue;
                    seen.add(m);
                    out.put(m);
                }
            }
            return out;
        } catch (Exception e) {
            return def;
        }
    }

    public static String detectObsidianRoot(Context c) {
        String[] bases = {"/storage/emulated/0/Documents", "/storage/emulated/0"};
        for (int bi = 0; bi < bases.length; bi++) {
            File[] fs = new File(bases[bi]).listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs);
            File best = null;
            int bestScore = -1;
            for (File f : fs) {
                if (!f.isDirectory() || f.isHidden()) continue;
                if (!f.getName().toLowerCase().contains("obsidian")) continue;
                int score = 0;
                if (new File(f, ".obsidian").isDirectory()) score += 10;
                File[] inner = f.listFiles();
                if (inner != null) {
                    for (File x : inner) if (x.getName().endsWith(".md")) score++;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            if (best != null) return best.getAbsolutePath();
        }
        return null;
    }

    public static String retireModel(String m) {
        if (m == null) return null;
        if (m.equals("qwen3-max") || m.equals("qwen3-max-preview")
                || m.equals("qwen3-coder-plus")) {
            return m.equals("qwen3-coder-plus") ? "qwen3-coder-flash" : "qwen3.8-max";
        }
        if (m.equals("qwen-vl-max") || m.equals("qwen-vl-plus") || m.equals("qwen3-vl-max")) {
            return "qwen3.8-max";
        }
        return m;
    }

    private static boolean isVisionModel(String m) {
        return m != null && m.indexOf("-vl") >= 0;
    }

    private static String caller() {
        StackTraceElement[] tr = new Throwable().getStackTrace();
        StringBuilder b = new StringBuilder();
        for (int i = 2; i < tr.length && i < 6; i++) {
            if (b.length() > 0) b.append("<-");
            b.append(tr[i].getClassName()).append('#').append(tr[i].getMethodName())
                    .append(':').append(tr[i].getLineNumber());
        }
        return b.toString();
    }

    public static String tomlStr(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') b.append("\\\\");
            else if (ch == '"') b.append("\\\"");
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }

    public static synchronized void writeConfig(Context c) {
        try {
            extractBundle(c);
            JSONObject st = loadSettings(c);
            String model = safeStr(st, "model");
            if (model == null) model = "qwen3.8-max";
            String by = safeStr(st, "model_by");
            if (by == null) by = "";
            String provider = safeStr(st, "provider");
            if (provider == null) provider = "dashscope";
            String fixedM = retireModel(model);
            if (!fixedM.equals(model)) {
                Log.i(TAG, "retire model " + model + " -> " + fixedM);
                model = fixedM;
                by = "auto";
                st.put("model", model);
                st.put("model_by", by);
                saveSettings(c, st);
            } else if (!"user".equals(by) && "dashscope".equals(provider)
                    && isVisionModel(model)) {
                Log.i(TAG, "vision model " + model + " was not picked by user -> qwen3.8-max");
                model = "qwen3.8-max";
                by = "auto";
                st.put("model", model);
                st.put("model_by", by);
                saveSettings(c, st);
            }
            Log.i(TAG, "store state model=" + model + " model_by=" + by + " provider=" + provider);
            String effort = safeStr(st, "effort");
            if (effort == null) effort = "medium";
            JSONObject keys = loadKeys(c);
            String tk;
            try {
                tk = getToken(c);
            } catch (Exception e) {
                tk = "";
            }
            if (tk == null) tk = "";
            StringBuilder sb = new StringBuilder();
            sb.append("model = \"").append(model).append("\"\n");
            sb.append("model_provider = \"").append(provider).append("\"\n");
            sb.append("model_reasoning_effort = \"").append(effort).append("\"\n");
            sb.append("model_context_window = 128000\n");
            sb.append("model_auto_compact_token_limit = 100000\n");
            sb.append("approval_policy = \"never\"\n");
            sb.append("sandbox_mode = \"danger-full-access\"\n");
            sb.append("show_raw_agent_reasoning = false\n");
            sb.append("[model_providers.dashscope]\n");
            sb.append("name = \"DashScope\"\n");
            sb.append("base_url = \"http://127.0.0.1:8768/t/").append(tk).append("/ds\"\n");
            sb.append("env_key = \"DASHSCOPE_API_KEY\"\n");
            sb.append("wire_api = \"responses\"\n");
            sb.append("[model_providers.zhipu]\n");
            sb.append("name = \"Zhipu\"\n");
            sb.append("base_url = \"http://127.0.0.1:8768/t/").append(tk).append("/zp\"\n");
            sb.append("env_key = \"ZHIPU_API_KEY\"\n");
            sb.append("wire_api = \"responses\"\n");
            sb.append("[model_providers.deepseek]\n");
            sb.append("name = \"DeepSeek\"\n");
            sb.append("base_url = \"http://127.0.0.1:8768/t/").append(tk).append("/dp\"\n");
            sb.append("env_key = \"DEEPSEEK_API_KEY\"\n");
            sb.append("wire_api = \"responses\"\n");
            sb.append("[model_providers.kimi]\n");
            sb.append("name = \"Kimi\"\n");
            sb.append("base_url = \"http://127.0.0.1:8768/t/").append(tk).append("/km\"\n");
            sb.append("env_key = \"KIMI_API_KEY\"\n");
            sb.append("wire_api = \"responses\"\n");
            JSONObject cprov = st.optJSONObject("custom_providers");
            if (cprov != null) {
                java.util.Iterator<String> cit = cprov.keys();
                while (cit.hasNext()) {
                    String cid = cit.next();
                    if ("dashscope".equals(cid) || "zhipu".equals(cid)
                            || "deepseek".equals(cid) || "kimi".equals(cid)) continue;
                    JSONObject cd = cprov.optJSONObject(cid);
                    if (cd == null) continue;
                    String cbase = safeStr(cd, "base");
                    if (cbase == null || cbase.length() == 0) continue;
                    if (!cid.matches("[A-Za-z0-9_\\-]{1,32}")) continue;
                    String cnm = safeStr(cd, "name");
                    if (cnm == null || cnm.length() == 0) cnm = cid;
                    sb.append("[model_providers.").append(cid).append("]\n");
                    sb.append("name = \"").append(cnm.replace("\"", "")).append("\"\n");
                    sb.append("base_url = \"http://127.0.0.1:8768/t/").append(tk)
                            .append("/p/").append(cid).append("\"\n");
                    sb.append("env_key = \"").append(envForKey(cid)).append("\"\n");
                    sb.append("wire_api = \"responses\"\n");
                }
            }
            sb.append("[mcp_servers.stereo]\n");
            sb.append("url = \"http://127.0.0.1:8767/t/").append(tk).append("/mcp\"\n");
            sb.append("startup_timeout_sec = 30\n");
            sb.append("tool_timeout_sec = 86400\n");
            JSONArray cmcp = st.optJSONArray("custom_mcp");
            if (cmcp != null) {
                for (int i = 0; i < cmcp.length(); i++) {
                    JSONObject m = cmcp.optJSONObject(i);
                    if (m == null) continue;
                    String mid = safeStr(m, "id");
                    if (mid == null || !mid.matches("[A-Za-z0-9_\\-]{1,32}")) continue;
                    if ("stereo".equals(mid)) continue;
                    String murl = safeStr(m, "url");
                    String mcmd = safeStr(m, "command");
                    boolean httpMode = murl != null && murl.length() > 0;
                    if (httpMode) {
                        if (!murl.startsWith("http://") && !murl.startsWith("https://")) continue;
                    } else if (mcmd == null || mcmd.length() == 0) {
                        continue;
                    }
                    sb.append("[mcp_servers.").append(mid).append("]\n");
                    if (httpMode) {
                        sb.append("url = \"").append(tomlStr(murl)).append("\"\n");
                    } else {
                        sb.append("command = \"").append(tomlStr(mcmd)).append("\"\n");
                        JSONArray ar = m.optJSONArray("args");
                        if (ar != null && ar.length() > 0) {
                            sb.append("args = [");
                            for (int j = 0; j < ar.length(); j++) {
                                if (j > 0) sb.append(", ");
                                sb.append('"').append(tomlStr(String.valueOf(ar.opt(j)))).append('"');
                            }
                            sb.append("]\n");
                        }
                    }
                    sb.append("startup_timeout_sec = 30\n");
                    sb.append("tool_timeout_sec = 86400\n");
                    JSONObject env = m.optJSONObject("env");
                    if (env != null && env.length() > 0) {
                        sb.append("[mcp_servers.").append(mid).append(".env]\n");
                        java.util.Iterator<String> eit = env.keys();
                        while (eit.hasNext()) {
                            String ek = eit.next();
                            if (!ek.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) continue;
                            String ev = safeStr(env, ek);
                            sb.append(ek).append(" = \"").append(tomlStr(ev == null ? "" : ev))
                                    .append("\"\n");
                        }
                    }
                }
            }
            String cfgOut = sb.toString();
            if (System.getenv("CODEX_SIM_SAFE") != null) {
                cfgOut = cfgOut.replace("approval_policy = \"never\"\n", "");
            }
            File f = new File(codexHome(c), "config.toml");
            writeAtomic(f, cfgOut);
            byte[] agb = readAsset(c, "agents_md.txt");
            if (agb != null) {
                String ags = new String(agb, "UTF-8")
                        .replace("{BUNDLE_DIR}", bundleDir(c).getAbsolutePath())
                        .replace("{WORK_DIR}", workDir(c).getAbsolutePath());
                StringBuilder agx = new StringBuilder(ags);
                String sk = Skills.indexText(c);
                agx.append("\n\n## 已装技能索引（启动时快照，直接看这里，不要为此调 skill_list）\n");
                agx.append(sk.length() == 0 ? "（暂无技能）\n" : sk);
                agx.append("\n## 长期记忆索引（启动时快照，不要为此调 memory_list）\n");
                String mm = MemoryStore.indexText(c);
                agx.append(mm.length() == 0 ? "（暂无记忆条目）\n" : mm);
                agx.append("\n需要某条记忆或某个技能的正文时才用 memory_read / skill_use 取一次；"
                        + "只有在本次会话里刚用 skill_write / memory_write 新建了条目时，"
                        + "才需要再调一次 skill_list / memory_list 看最新的。\n");
                File ag = new File(codexHome(c), "AGENTS.md");
                writeAtomic(ag, agx.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "writeConfig", e);
        }
    }

    public static String envForKey(String provider) {
        if ("dashscope".equals(provider)) return "DASHSCOPE_API_KEY";
        if ("zhipu".equals(provider)) return "ZHIPU_API_KEY";
        if ("deepseek".equals(provider)) return "DEEPSEEK_API_KEY";
        if ("kimi".equals(provider)) return "KIMI_API_KEY";
        return ("KEY_" + provider).toUpperCase();
    }

    static File importFile() {
        String p = System.getProperty("st.keys_import", "");
        return p.length() == 0 ? null : new File(p);
    }

    public static void importLegacyKeys(Context c) {
        File f = importFile();
        if (f == null || !f.isFile()) return;
        int n = 0;
        try {
            byte[] b = read(f);
            if (b != null) {
                JSONObject o = new JSONObject(new String(b, "UTF-8"));
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = safeStr(o, k);
                    if (v == null || v.trim().length() == 0) continue;
                    saveKey(c, k, v.trim());
                    n++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "importLegacyKeys", e);
        } finally {
            f.delete();
        }
        if (n > 0) Log.i(TAG, "keys imported: " + n);
    }

    public static void importLegacyConvs(Context c) {
        try {
            String p = System.getProperty("st.convs_import", "");
            if (p.length() == 0) return;
            File src = new File(p);
            if (!src.isDirectory()) return;
            File dst = new File(c.getFilesDir(), "legacy_convs");
            if (!dst.exists()) dst.mkdirs();
            File[] fs = src.listFiles();
            if (fs == null) return;
            int n = 0;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(".json")) continue;
                if (f.getName().endsWith(".live.json")) continue;
                File out = new File(dst, f.getName());
                if (out.isFile() && out.lastModified() >= f.lastModified()) continue;
                byte[] b = read(f);
                if (b == null) continue;
                FileOutputStream o = new FileOutputStream(out);
                o.write(b);
                o.close();
                n++;
            }
            if (n > 0) Log.i(TAG, "legacy convs imported: " + n);
        } catch (Exception e) {
            Log.i(TAG, "importLegacyConvs skip: " + e);
        }
    }

    private static byte[] readAsset(Context c, String name) {
        try {
            InputStream in = c.getAssets().open(name);
            ByteArrayOutputStream2 bos = new ByteArrayOutputStream2();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static void copyAsset(Context c, String name, File dst) {
        try {
            byte[] b = readAsset(c, name);
            if (b == null) return;
            FileOutputStream out = new FileOutputStream(dst);
            out.write(b);
            out.close();
        } catch (Exception e) { }
    }

    static class ByteArrayOutputStream2 {
        private byte[] buf = new byte[4096];
        private int len = 0;

        void write(byte[] b, int off, int n) {
            if (len + n > buf.length) {
                int nl = buf.length;
                while (nl < len + n) nl *= 2;
                byte[] nb = new byte[nl];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(b, off, buf, len, n);
            len += n;
        }

        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
