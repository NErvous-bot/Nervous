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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Sync worker: fetch book source JSON from GitHub, then write into
 * Legado reader app via its ContentProvider (batched inserts).
 *
 * v3.2: 下载线路（镜像）名单改为云端自动获取 —— 每次同步前先从
 * urls.txt 拉取最新镜像名单并缓存本地；云端不可用时用上次缓存，
 * 首次安装且无缓存时用内置兜底名单。以后增删镜像只需改仓库里的
 * urls.txt，App 无需重新安装。
 */
public class SyncWorker extends Worker {

    private static final String AUTHORITY = "com.legado.app.release.readerProvider";

    /** 仓库地址分段存放，避免APK里能直接搜到完整URL（不改变功能） */
    private static final String REPO = new StringBuilder()
            .append("NErvous-bot").append('/')
            .append("x2ye").append("fuobr").append('-')
            .append("15").append("whlj").toString();
    private static final String GH_RAW = "https://raw.githubusercontent.com/";
    private static final String GH_JSD = "https://cdn.jsdelivr.net/gh/";

    /** 拉取镜像名单的锚点线路（必须稳定，名单本身很小） */
    private static final String[] ANCHOR_URLS = {
            GH_RAW + REPO + "/main/urls.txt",
            GH_JSD + REPO + "@main/urls.txt",
            "https://ghproxy.net/" + GH_RAW + REPO + "/main/urls.txt"
    };

    /** 兜底名单：首次安装且云端名单拉不到时使用 */
    private static final String[] DEFAULT_LINES = {
            "https://ghproxy.net/" + GH_RAW + REPO + "/main/legado.json",
            "https://ghfast.top/" + GH_RAW + REPO + "/main/legado.json",
            "https://gh-proxy.com/" + GH_RAW + REPO + "/main/legado.json",
            GH_JSD + REPO + "@main/legado.json",
            GH_RAW + REPO + "/main/legado.json"
    };

    private static final int BATCH = 80;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
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

        // 1) 先刷新镜像名单（云端获取 + 本地缓存，失败则沿用旧名单）
        List<String> lines = refreshLines();

        // 2) 按名单逐条尝试下载书源
        String json = null;
        for (String u : lines) {
            json = fetch(u);
            if (json != null) break;
        }
        if (json == null) {
            save("失败：全部 " + lines.size() + " 条线路都无法访问，请检查网络，稍后会自动重试");
            return Result.retry();
        }

        // 3) 内容没变就跳过写入（省电）
        File cache = new File(getApplicationContext().getFilesDir(), "last.json");
        if (cache.exists() && md5(cache).equals(md5(json))) {
            // 书源没变，但净化规则可能变了，仍需检查
            String ruleMsg = syncRules(lines);
            ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .edit().putLong("lastOk", System.currentTimeMillis()).apply();
            save("检查完成：书源无变化已跳过；" + ruleMsg);
            return Result.success();
        }

        // 4) 分批写入阅读App
        try {
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
     * 同步全局净化规则（replaceRule.json）到阅读App。
     * 失败只汇报，不影响书源同步结果。
     */
    private String syncRules(List<String> lines) {
        try {
            String rjson = null;
            for (String u : lines) {
                if (u.contains("legado.json")) {
                    rjson = fetch(u.replace("legado.json", "replaceRule.json"));
                    if (rjson != null) break;
                }
            }
            if (rjson == null) return "净化规则：下载失败（不影响书源）";

            File rcache = new File(getApplicationContext().getFilesDir(), "last_rules.json");
            if (rcache.exists() && md5(rcache).equals(md5(rjson))) {
                return "净化规则：无变化";
            }

            JSONArray all = new JSONArray(rjson);
            int total = all.length();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/replaceRule/insert");
            try {
                // 优先数组批量写入（与书源同机制）
                ContentValues v = new ContentValues();
                v.put("json", all.toString());
                getApplicationContext().getContentResolver().insert(uri, v);
            } catch (Exception batchFail) {
                // 部分版本不支持数组，逐条写入
                for (int i = 0; i < total; i++) {
                    ContentValues v = new ContentValues();
                    v.put("json", all.get(i).toString());
                    getApplicationContext().getContentResolver().insert(uri, v);
                }
            }
            OutputStream os = new FileOutputStream(rcache);
            os.write(rjson.getBytes("UTF-8"));
            os.close();
            return "净化规则：已更新 " + total + " 条";
        } catch (Exception e) {
            return "净化规则：写入失败（不影响书源）";
        }
    }

    /**
     * 刷新下载线路：云端拉 urls.txt → 成功则缓存并返回新名单；
     * 失败则用本地缓存；本地也无缓存则用内置兜底名单。
     */
    private List<String> refreshLines() {
        File cache = new File(getApplicationContext().getFilesDir(), "lines.txt");
        List<String> current = parseLines(readFile(cache));
        if (current.isEmpty()) current = toList(DEFAULT_LINES);
        for (String a : ANCHOR_URLS) {
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

    /** 解析名单文本：一行一条URL，忽略空行和#注释，必须指向 legado.json */
    private List<String> parseLines(String txt) {
        List<String> out = new ArrayList<>();
        if (txt == null) return out;
        for (String s : txt.split("\n")) {
            s = s.trim();
            if (!s.isEmpty() && !s.startsWith("#") && s.contains("legado.json")) out.add(s);
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

    /** Download url content, return null on failure. */
    private String fetch(String urlStr) {
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
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 智能选代理：环境变量 HTTP_PROXY/HTTPS_PROXY > 系统 ProxySelector > 直连 */
    private Proxy pickProxy(URL url) {
        // 1) HTTP_PROXY / HTTPS_PROXY 环境变量（部分代理工具会注入）
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
        // 2) 系统代理（Wifi 设置的代理 / PAC 脚本）
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

    private void save(String msg) {
        getApplicationContext()
                .getSharedPreferences("sync", Context.MODE_PRIVATE)
                .edit()
                .putString("last", new java.text.SimpleDateFormat(
                        "MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date())
                        + " " + msg)
                .apply();
    }
}
