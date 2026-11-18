package com.ueasy.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：检查 → 通知界面显示横幅 → 下载 → 调起安装。
 * 不弹 Dialog（部分 ROM 上 Dialog 会被主题吞掉），由界面自己展示。
 */
final class UpdateChecker {

    /**
     * 多源容错：国内访问 raw.githubusercontent.com 经常 503，
     * 优先走 jsDelivr 的几个节点，最后回落到 GitHub raw。
     */
    /** GitHub Releases API：无 CDN 缓存，发布后立即生效 */
    static final String API_URL =
            "https://api.github.com/repos/Bingxin6451/ueasy-dist/releases/latest";

    static final String[] MANIFEST_URLS = {
            "https://cdn.jsdelivr.net/gh/Bingxin6451/ueasy-dist@main/version.json",
            "https://fastly.jsdelivr.net/gh/Bingxin6451/ueasy-dist@main/version.json",
            "https://gcore.jsdelivr.net/gh/Bingxin6451/ueasy-dist@main/version.json",
            "https://raw.githubusercontent.com/Bingxin6451/ueasy-dist/main/version.json"
    };

    interface Listener {
        /** 有新版可用 */
        void onUpdateAvailable(String versionName, String notes, String url);

        /** 无新版（手动检查时给反馈） */
        void onUpToDate(String currentVersion);

        /** 检查失败 */
        void onCheckFailed(String reason);

        /** 下载进度 0-100；-1 表示失败 */
        void onProgress(int percent);
    }

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    UpdateChecker(Activity a) {
        this.act = a;
    }

    static int currentVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 语义化版本比较："1.1.0" > "1.0.9" */
    static boolean isNewer(String remote, String current) {
        int[] a = parseVer(remote);
        int[] b = parseVer(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parseVer(String s) {
        if (s == null) return new int[0];
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        int cut = t.indexOf('-');
        if (cut > 0) t = t.substring(0, cut);
        String[] p = t.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    static String currentVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    void check(final boolean manual, final Listener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String newName = null;
                    String url = null;
                    String notes = "";
                    Exception last = null;

                    // 1) 优先 GitHub Releases API（无 CDN 缓存）
                    try {
                        LogSink.i("检查更新 API " + API_URL);
                        JSONObject rel = new JSONObject(httpGet(API_URL));
                        String tag = rel.optString("tag_name", "");
                        notes = rel.optString("body", "");
                        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
                        org.json.JSONArray assets = rel.optJSONArray("assets");
                        if (assets != null && assets.length() > 0) {
                            url = assets.getJSONObject(0).optString("browser_download_url", "");
                        }
                        if (!tag.isEmpty() && !url.isEmpty()) {
                            newName = tag;
                            LogSink.i("API 可用: " + tag + " " + url);
                        }
                    } catch (Exception e) {
                        LogSink.i("API 失败: " + e.getMessage());
                        last = e;
                    }

                    // 2) 回落到静态清单（多节点）
                    if (newName == null) {
                        for (String u : MANIFEST_URLS) {
                            try {
                                LogSink.i("检查更新 GET " + u);
                                JSONObject o = new JSONObject(httpGet(u));
                                String vn = o.optString("versionName", "");
                                String uu = o.optString("url", "");
                                if (!vn.isEmpty() && !uu.isEmpty()) {
                                    newName = vn;
                                    url = uu;
                                    notes = o.optString("notes", "");
                                    LogSink.i("清单可用: " + u);
                                    break;
                                }
                            } catch (Exception e) {
                                LogSink.i("源失败: " + u + " -> " + e.getMessage());
                                last = e;
                            }
                        }
                    }
                    if (newName == null) {
                        throw last != null ? last : new RuntimeException("全部更新源不可用");
                    }

                    final String fName = newName;
                    final String fUrl = url;
                    final String fNotes = notes;
                    final String curName = currentVersionName(act);
                    final boolean newer = isNewer(fName, curName);
                    LogSink.i("版本比较 当前=" + curName + " 远端=" + fName + " 更新=" + newer);

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (newer && !fUrl.isEmpty()) {
                                LogSink.i("有新版本，交给界面展示");
                                if (l != null) l.onUpdateAvailable(fName, fNotes, fUrl);
                            } else {
                                LogSink.i("已是最新");
                                if (l != null) l.onUpToDate(currentVersionName(act));
                            }
                        }
                    });
                } catch (final Exception e) {
                    LogSink.e("检查更新异常", e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (l != null) l.onCheckFailed(e.getClass().getSimpleName()
                                    + " " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    void download(final String url, final Listener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LogSink.i("开始下载 " + url);
                    File dir = act.getExternalFilesDir("apk");
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    final File out = new File(dir, "ueasy-update.apk");

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.connect();
                    if (c.getResponseCode() != 200) {
                        throw new RuntimeException("HTTP " + c.getResponseCode());
                    }
                    final int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    long done = 0;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (l != null) l.onProgress(pct);
                                    }
                                });
                            }
                        }
                    }
                    fo.close();
                    in.close();
                    c.disconnect();
                    LogSink.i("下载完成 " + out.getAbsolutePath() + " size=" + out.length());

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            install(out);
                        }
                    });
                } catch (final Exception e) {
                    LogSink.e("下载失败", e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (l != null) l.onProgress(-1);
                            toast("下载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void install(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            LogSink.e("调起安装器失败", e);
            toast("无法调起安装器，文件在 " + apk.getAbsolutePath());
        }
    }

    private void toast(final String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }

    private static String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "ueasy-updater");
        if (c.getResponseCode() != 200) throw new RuntimeException("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        c.disconnect();
        return sb.toString();
    }
}
