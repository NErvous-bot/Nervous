package io.nervous.sourcesync;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Sync worker: fetch book source JSON from GitHub, then write into
 * Legado reader app via its ContentProvider (batched inserts).
 *
 * v3.6：
 *  - 全部下载改为字节级处理并验签原始字节（彻底排除任何转码差异）；
 *  - 请求追加随机 cb 参数穿透代理/CDN 缓存——带缓存的 WiFi 代理曾把
 *    旧版 json 和新签名混搭发给 App 导致验签失败，现在每次强制回源；
 *  - 全程 setProgress 上报进度（界面实时显示当前第几条线路）。
 */
public class SyncWorker extends Worker {

    private static final String AUTHORITY = "com.legado.app.release.readerProvider";

    /** 仓库地址逐字符 ^ 0x5A 存储，防 dex 字符串直搜（运行时解码） */
    private static final int[] REPO_ENC = {
            20, 31, 40, 44, 53, 47, 41, 119, 56, 53, 46, 117, 34, 104,
            35, 63, 60, 47, 53, 56, 40, 119, 107, 111, 45, 50, 54, 48
    };

    /** Ed25519 公钥（X509 DER, base64）。私钥只在仓库 Actions 的 Secrets 里。 */
    private static final String PUB_B64 =
            "MCowBQYDK2VwAyEACqlWcnXE6x9d/shXbKSNF20Ur2Xy/cCTBHP4gGx85TE=";

    private static final String GH_RAW = "https://raw.githubusercontent.com/";
    private static final String GH_JSD = "https://cdn.jsdelivr.net/gh/";

    private static String[] anchorUrls(String repo) {
        return new String[]{
                GH_RAW + repo + "/main/urls.txt",
                GH_JSD + repo + "@main/urls.txt",
                "https://ghproxy.net/" + GH_RAW + repo + "/main/urls.txt"
        };
    }

    private static String[] defaultLines(String repo) {
        return new String[]{
                "https://ghproxy.net/" + GH_RAW + repo + "/main/legado.json",
                "https://ghfast.top/" + GH_RAW + repo + "/main/legado.json",
                "https://gh-proxy.com/" + GH_RAW + repo + "/main/legado.json",
                GH_JSD + repo + "@main/legado.json",
                GH_RAW + repo + "/main/legado.json"
        };
    }

    private static final int BATCH = 80;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private void prog(String msg) {
        try { setProgressAsync(new androidx.work.Data.Builder().putString("msg", msg).build()); }
        catch (Exception ignored) {}
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // 0) 调度窗口：每周一凌晨5-11点执行；超13天未成功同步则任何时间补跑；
        //    手动「立即同步」不受窗口限制
        if (!"manual".equals(getInputData().getString("mode"))) {
            android.content.SharedPreferences sp0 = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE);
            long lastOk = sp0.getLong("lastOk", 0);
            boolean firstRun = lastOk == 0;
            boolean overdue = lastOk > 0
                    && System.currentTimeMillis() - lastOk > 13L * 24 * 3600 * 1000;
            java.util.Calendar now = java.util.Calendar.getInstance();
            boolean inWindow = now.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.MONDAY
                    && now.get(java.util.Calendar.HOUR_OF_DAY) >= 5
                    && now.get(java.util.Calendar.HOUR_OF_DAY) < 11;
            if (!firstRun && !overdue && !inWindow) {
                save("未到同步窗口（每周一凌晨5点），本次跳过检查");
                return Result.success();
            }
        }

        // 1) 刷新镜像名单
        prog("正在刷新镜像名单…");
        List<String> lines = refreshLines();

        // 2) 下载书源（逐线路「下载+验签」双确认，验签不过=线路不可信，换线）
        StringBuilder err = new StringBuilder();
        String json = fetchVerified("legado.json", lines, err);
        if (json == null) {
            save("失败：" + err + "，稍后会自动重试");
            return Result.retry();
        }

        // 3) 内容没变就跳过写入（省电）
        File cache = new File(getApplicationContext().getFilesDir(), "last.json");
        if (cache.exists() && md5(cache).equals(md5(json))) {
            String ruleMsg = syncRules(lines);
            ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .edit().putLong("lastOk", System.currentTimeMillis()).apply();
            save("检查完成：书源无变化已跳过；" + ruleMsg);
            return Result.success();
        }

        // 4) 分批写入阅读App
        try {
            prog("验签通过，正在写入阅读App…");
            JSONArray all = new JSONArray(json);
            int total = all.length();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/bookSources/insert");
            for (int i = 0; i < total; i += BATCH) {
                JSONArray part = new JSONArray();
                for (int j = i; j < Math.min(i + BATCH, total); j++) {
                    part.put(all.get(j));
                }
                ContentValues v = new ContentValues();
                v.put("json", part.toString());
                getApplicationContext().getContentResolver().insert(uri, v);
                prog("正在写入书源 " + Math.min(i + BATCH, total) + "/" + total + "…");
            }
            OutputStream os = new FileOutputStream(cache);
            os.write(json.getBytes("UTF-8"));
            os.close();
            String ruleMsg = syncRules(lines);
            ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .edit().putLong("lastOk", System.currentTimeMillis()).apply();
            save("成功：已写入 " + total + " 个书源；" + ruleMsg);
            return Result.success();
        } catch (Exception e) {
            save("失败：" + e.getMessage());
            return Result.failure();
        }
    }

    /**
     * 带 Ed25519 验签的下载：对每条线路，同时取「文件 + 文件.sig」，
     * 验签通过才返回内容；任何一环失败都换下一条线路（fail closed）。
     * 请求带随机 cb 参数穿透代理/CDN 缓存，保证 body 与 sig 来自同一
     * 份回源数据，杜绝「旧内容配新签名」的缓存错位。
     */
    private String fetchVerified(String fileName, List<String> lines, StringBuilder err) {
        int total = lines.size();
        for (int i = 0; i < total; i++) {
            String url = lines.get(i).replace("legado.json", fileName);
            String host = hostOf(url);
            prog("正在尝试线路 " + (i + 1) + "/" + total + "：" + host.trim());
            String cb = (url.contains("?") ? "&" : "?") + "cb=" + System.currentTimeMillis();
            byte[] body = fetchBytes(url + cb);
            if (body == null) { err.append("[下载失败]").append(host); continue; }
            byte[] sigB = fetchBytes(url + ".sig" + cb);
            if (sigB == null) { err.append("[无签名]").append(host); continue; }
            String vr = verify(body, new String(sigB, java.nio.charset.StandardCharsets.UTF_8).trim());
            if (vr == null) return new String(body, java.nio.charset.StandardCharsets.UTF_8);
            err.append("[签名不符]").append(host);
        }
        if (err.length() == 0) err.append("未知错误");
        return null;
    }

    /**
     * 验证 Ed25519 签名（签名 = 原始64字节签名的base64，由 Actions 的
     * openssl pkeyutl -rawin 生成，RFC 8032 兼容）。
     * 直接对下载的原始字节验签，不做任何字符串转码。
     * @return null=通过；其他=失败原因
     */
    private String verify(byte[] content, String sigB64) {
        try {
            byte[] pub = Base64.getDecoder().decode(PUB_B64);
            PublicKey pk = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(pub));
            Signature sg = Signature.getInstance("Ed25519");
            sg.initVerify(pk);
            sg.update(content);
            if (sg.verify(Base64.getDecoder().decode(sigB64))) return null;
            return "签名不符";
        } catch (Exception e) {
            return "验签异常:" + e.getClass().getSimpleName();
        }
    }

    private String hostOf(String url) {
        try { return new URL(url).getHost() + " "; }
        catch (Exception e) { return "? "; }
    }

    /** 同步全局净化规则到阅读App，同样走验签。失败只汇报，不影响书源同步。 */
    private String syncRules(List<String> lines) {
        try {
            StringBuilder err = new StringBuilder();
            String rjson = fetchVerified("replaceRule.json", lines, err);
            if (rjson == null) return "净化规则：验签下载失败(" + err + ")（不影响书源）";

            File rcache = new File(getApplicationContext().getFilesDir(), "last_rules.json");
            if (rcache.exists() && md5(rcache).equals(md5(rjson))) {
                return "净化规则：无变化";
            }

            JSONArray all = new JSONArray(rjson);
            int total = all.length();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/replaceRule/insert");
            try {
                ContentValues v = new ContentValues();
                v.put("json", all.toString());
                getApplicationContext().getContentResolver().insert(uri, v);
            } catch (Exception batchFail) {
                for (int i = 0; i < total; i++) {
                    ContentValues v = new ContentValues();
                    v.put("json", all.get(i).toString());
                    getApplicationContext().getContentResolver().insert(uri, v);
                }
            }
            OutputStream os = new FileOutputStream(rcache);
            os.write(rjson.getBytes("UTF-8"));
            os.close();
            return "净化规则：已更新 " + total + " 条（已验签）";
        } catch (Exception e) {
            return "净化规则：写入失败（不影响书源）";
        }
    }

    /** 刷新下载线路：云端拉 urls.txt → 成功则缓存并返回新名单；失败则用本地缓存；再退内置兜底。 */
    private List<String> refreshLines() {
        String repo = decodeRepo();
        File cache = new File(getApplicationContext().getFilesDir(), "lines.txt");
        List<String> current = parseLines(readFile(cache));
        if (current.isEmpty()) current = toList(defaultLines(repo));
        for (String a : anchorUrls(repo)) {
            String txt = fetch(a);
            if (txt == null) continue;
            List<String> fresh = parseLines(txt);
            if (fresh.size() >= 3) {              // 名单至少3条才算有效
                if (!fresh.equals(current)) writeFile(cache, txt);
                return fresh;
            }
        }
        return current;
    }

    /** 运行时解码仓库地址 */
    private static String decodeRepo() {
        StringBuilder sb = new StringBuilder();
        for (int c : REPO_ENC) sb.append((char) (c ^ 0x5A));
        return sb.toString();
    }

    /** 解析名单文本：只接受 https:// 且指向 legado.json 的行。 */
    private List<String> parseLines(String txt) {
        List<String> out = new ArrayList<>();
        if (txt == null) return out;
        for (String s : txt.split("\n")) {
            s = s.trim();
            if (!s.isEmpty() && !s.startsWith("#") && s.contains("legado.json")
                    && s.startsWith("https://")) out.add(s);
        }
        return out;
    }

    private List<String> toList(String[] arr) {
        List<String> out = new ArrayList<>();
        for (String s : arr) out.add(s);
        return out;
    }

    private String readFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(File f, String text) {
        try {
            OutputStream os = new FileOutputStream(f);
            os.write(text.getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {}
    }

    /** Download url content as raw bytes, return null on failure. */
    private byte[] fetchBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            // 优先级：HTTP_PROXY 环境变量 > 系统 Wifi 代理/PAC > 直连
            Proxy proxy = pickProxy(url);
            conn = (HttpURLConnection) (proxy == Proxy.NO_PROXY
                    ? url.openConnection()
                    : url.openConnection(proxy));
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/142.0 Mobile Safari/537.36");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String fetch(String urlStr) {
        byte[] b = fetchBytes(urlStr);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 智能选代理：环境变量 HTTP_PROXY/HTTPS_PROXY > 系统 ProxySelector > 直连 */
    private Proxy pickProxy(URL url) {
        try {
            String envKey = "HTTPS_PROXY";
            String env = System.getenv(envKey);
            if (env == null || env.isEmpty()) {
                envKey = "HTTP_PROXY";
                env = System.getenv(envKey);
            }
            if (env != null && !env.isEmpty()) {
                java.net.URI u = java.net.URI.create(env.contains("://") ? env : "http://" + env);
                String h = u.getHost();
                int p = u.getPort() == -1 ? 8888 : u.getPort();
                if (h != null) return new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress(h, p));
            }
        } catch (Exception ignored) {}
        try {
            URI uri = url.toURI();
            java.util.List<Proxy> proxies = ProxySelector.getDefault().select(uri);
            if (proxies != null && !proxies.isEmpty()) {
                Proxy p = proxies.get(0);
                if (p != null && p.address() != null) return p;
            }
        } catch (Exception ignored) {}
        return Proxy.NO_PROXY;
    }

    private String md5(String text) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String md5(File file) {
        return md5(readFile(file));
    }

    /** 保存本次结果，并滚动保留最近5条历史（界面可查，排查不再靠记忆） */
    private void save(String msg) {
        try {
            android.content.SharedPreferences sp =
                    getApplicationContext().getSharedPreferences("sync", Context.MODE_PRIVATE);
            String line = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date())
                    + " " + msg;
            List<String> hist = new ArrayList<>();
            String old = sp.getString("hist", null);
            if (old != null) for (String s : old.split("\n")) if (!s.isEmpty()) hist.add(s);
            hist.add(0, line);
            while (hist.size() > 5) hist.remove(hist.size() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hist.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(hist.get(i));
            }
            sp.edit().putString("last", line).putString("hist", sb.toString()).apply();
        } catch (Exception ignored) {}
    }
}
