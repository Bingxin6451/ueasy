package com.ueasy.client;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 离线日志落盘：每行即时 flush，进程被杀也不丢；按会话分文件，自动清理旧文件。
 * 优先写 /sdcard/Download/bleapk-logs/（无需连电脑即可取），失败则退到应用私有目录。
 */
final class LogSink {

    private static final long MAX_BYTES = 4 * 1024 * 1024;  // 单文件 4MB
    private static final int KEEP_FILES = 15;               // 最多保留 15 个会话

    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FN = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private static FileWriter writer;
    private static File current;
    private static File dirRef;
    private static int part = 1;
    private static long written = 0;
    private static boolean inited = false;

    private LogSink() {
    }

    static synchronized void init(Context ctx) {
        if (inited) return;
        inited = true;
        try {
            File dir = pickDir(ctx);
            if (dir == null) return;
            dirRef = dir;
            cleanupOld(dir);
            current = new File(dir, "session-" + FN.format(new Date()) + ".log");
            writer = new FileWriter(current, true);
            written = 0;
            part = 1;
            write("=== 洗衣助手 会话开始 ===");
            write("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            write("包: com.ble.attack");
        } catch (Throwable ignored) {
        }
    }

    private static File pickDir(Context ctx) {
        // 1) 公共下载目录：拔掉数据线也能在文件管理里找到
        try {
            File pub = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "bleapk-logs");
            if (!pub.exists()) pub.mkdirs();
            if (pub.isDirectory() && pub.canWrite()) return pub;
        } catch (Throwable ignored) {
        }
        // 2) 应用外部私有目录（无需任何权限）
        try {
            File ext = ctx.getExternalFilesDir("logs");
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs();
                return ext;
            }
        } catch (Throwable ignored) {
        }
        // 3) 内部存储兜底
        try {
            File in = new File(ctx.getFilesDir(), "logs");
            if (!in.exists()) in.mkdirs();
            return in;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void cleanupOld(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= KEEP_FILES) return;
            Arrays.sort(fs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            int remove = fs.length - KEEP_FILES;
            for (int i = 0; i < remove; i++) {
                if (fs[i].isFile()) fs[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized void i(String msg) {
        write(msg);
    }

    static synchronized void e(String msg, Throwable t) {
        write(msg);
        if (t != null) {
            write(t.getClass().getName() + ": " + t.getMessage());
            for (StackTraceElement el : t.getStackTrace()) {
                write("    at " + el);
            }
        }
    }

    private static void write(String msg) {
        if (writer == null) return;
        try {
            String line = TS.format(new Date()) + "  " + msg + "\n";
            writer.write(line);
            writer.flush();
            written += line.length();
            if (written > MAX_BYTES) {
                // 续开新文件，绝不中断记录
                try {
                    writer.write(TS.format(new Date()) + "  === 本文件达到上限，续接到下一个文件 ===\n");
                    writer.flush();
                } catch (Throwable ignored) {
                }
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
                part++;
                if (dirRef != null) {
                    current = new File(dirRef, "session-" + FN.format(new Date())
                            + "-p" + part + ".log");
                    writer = new FileWriter(current, true);
                    written = 0;
                    writer.write(TS.format(new Date()) + "  === 续接（第 " + part + " 段） ===\n");
                    writer.flush();
                } else {
                    writer = null;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized File currentFile() {
        return current;
    }

    static synchronized String currentPath() {
        return current != null ? current.getAbsolutePath() : "(日志未初始化)";
    }

    static synchronized void close() {
        try {
            if (writer != null) {
                write("=== 会话结束 ===");
                writer.close();
            }
        } catch (Throwable ignored) {
            writer = null;
        }
    }
}
