package com.ueasy.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import static com.ueasy.client.UiKit.*;

/**
 * 洗衣助手 —— 工具型产品形态
 * 首页：附近设备（价格 / 距离 / 信号一目了然）
 * 设备页：选程序 → 看金额 → 一键启动
 * 调试日志默认折叠，不干扰普通用户。
 */
public class MainActivity extends Activity {

    private static final int C_GREEN = Color.parseColor("#067647");

    private static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    private static final String[] HNH_KEY_LADDER = {
            "0102030405060708",
            "0102030405060709",
            "1122334455667788",
            "0000000000000000",
            "1234567890123456"
    };

    private static final Map<String, String> KNOWN_SCVTC = new HashMap<String, String>() {{
        put("AC:23:3F:42:44:27", "4舍-201 洗衣机");
        put("AC:23:3F:42:44:C4", "4舍-201 洗鞋机");
        put("AC:23:3F:53:0B:22", "4舍-201 烘干机");
        put("AC:9A:22:54:5A:CA", "4舍-401 烘干机");
        put("4244B8", "2舍-203");
        put("41BB4F", "1舍-604");
        put("4244BB", "17栋-101");
        put("424423", "17栋-102");
        put("4244AE", "17栋-202");
        put("42449D", "17栋-203");
        put("42445A", "17栋-302");
        put("42449C", "17栋-303");
        put("4244B3", "17栋-401");
        put("424CC0", "17栋-402");
        put("424450", "17栋-403");
        put("424498", "17栋-601");
        put("42452B", "17栋-602");
        put("424F6B", "17栋-603");
        put("424473", "16栋-601");
        put("424474", "12栋-4B301");
    }};

    private static final String[] WASHER_NAMES = {"单脱水", "快速洗", "标准洗", "大件洗"};
    private static final String[] WASHER_PRICES = {"0.80", "1.50", "2.50", "2.80"};
    private static final int[] WASHER_PULSES = {1, 2, 3, 4};
    private static final String[] WASHER_SUB = {"约 8 分钟", "约 20 分钟", "约 35 分钟", "约 45 分钟"};

    private static final String[] DRYER_NAMES = {"低温烘干", "标准烘干", "强力烘干", "超长烘干"};
    private static final String[] DRYER_PRICES = {"1.50", "2.50", "3.50", "4.50"};
    private static final int[] DRYER_PULSES = {1, 2, 3, 4};
    private static final String[] DRYER_SUB = {"15 分钟", "30 分钟", "45 分钟", "60 分钟"};

    // ==================== 数据模型（后端） ====================
    public static class MachineTarget {
        public final String mac;
        public String name;
        public boolean isDryer;
        public float smoothedRssi;
        public int rawRssi;
        public long lastSeen;
        public String proto = "AUTO";

        public MachineTarget(String mac, String name, boolean isDryer, int initialRssi) {
            this.mac = mac;
            this.name = name;
            this.isDryer = isDryer;
            this.rawRssi = initialRssi;
            this.smoothedRssi = (float) initialRssi;
            this.lastSeen = SystemClock.uptimeMillis();
        }

        public void updateRssi(int newRssi) {
            this.rawRssi = newRssi;
            this.smoothedRssi = (0.20f * newRssi) + (0.80f * this.smoothedRssi);
            this.lastSeen = SystemClock.uptimeMillis();
        }

        public float getDistance() {
            double ratio = (Math.abs(smoothedRssi) - 59.0) / 22.0;
            return (float) Math.max(0.3, Math.min(15.0, Math.pow(10.0, ratio)));
        }
    }

    private final Map<String, MachineTarget> machines = new HashMap<>();
    private final List<MachineTarget> stableDisplayList = new ArrayList<>();
    private MachineTarget currentTarget = null;
    private int selectedProgramIndex = 1;

    // ==================== 蓝牙状态（后端） ====================
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private volatile boolean isScanning = false;
    private int scanRetry = 0;

    private BluetoothGatt currentGatt;
    private BluetoothGattCharacteristic writeCh, notifyCh;
    private String currentProto = "UNKNOWN";
    private volatile boolean isConnecting = false;
    private volatile boolean isReady = false;

    private final Deque<byte[]> writeQueue = new ArrayDeque<>();
    private volatile boolean writeBusy = false;

    private int hnhKeyIndex = 0;
    private byte[] hnhFyKey = null;
    private int hnhPByte = 0, hnhQByte = 0;
    private boolean pendingStart = false;
    private final StringBuilder rxBuf = new StringBuilder();

    // ==================== UI 引用 ====================
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScrollView homePage;
    private ScrollView controlPage;
    private LinearLayout deviceListBox;
    private LinearLayout emptyBox;
    private LinearLayout bottomBar;
    private LinearLayout actionPanel;
    private TextView actionTargetTv;
    private TextView actionSubTv;
    private TextView statusTv;
    private TextView logTv;
    private LinearLayout programRow;
    private Button startBtn;
    private TextView priceTv;
    private TextView scanStatusTv;
    private TextView appBarTitle;
    private TextView appBarSub;
    private TextView backBtn;
    private LinearLayout bannerBox;
    private TextView bannerTv;
    private Steps stepsView;
    private RssiBars controlRssi;
    private ScrollView logScroll;
    private TextView debugToggle;
    private View scanDot;
    private boolean debugExpanded = false;
    private boolean uiConnError = false;
    private volatile boolean gotRxSinceStart = false;
    private int fallbackCount = 0;
    private final Map<String, Long> lastScanLogAt = new HashMap<>();

    private final StringBuilder logBuf = new StringBuilder();

    // ==================== 后端循环 ====================
    private long lastSortTime = 0;
    private final Runnable scanRefresher = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            boolean listChanged = false;

            synchronized (machines) {
                Iterator<Map.Entry<String, MachineTarget>> it = machines.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, MachineTarget> entry = it.next();
                    if (now - entry.getValue().lastSeen > 15000) {
                        it.remove();
                        listChanged = true;
                    }
                }
            }

            if (now - lastSortTime > 2500 && actionPanel.getVisibility() != View.VISIBLE) {
                applyHysteresisSort();
                lastSortTime = now;
                listChanged = true;
            }

            if (listChanged || stableDisplayList.isEmpty()) {
                updateBottomUI();
            }

            mainHandler.postDelayed(this, 600);
        }
    };

    private final Runnable writeWatchdog = new Runnable() {
        @Override
        public void run() {
            if (writeBusy) {
                writeBusy = false;
                log("! 写入超时，队列继续");
                pumpWriteQueue();
            }
        }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (isConnecting && !isReady) {
                isConnecting = false;
                setStatus("✗ 连接超时，请靠近设备后点重连");
                log("! 连接超时");
                closeGatt();
            }
        }
    };

    // 启动后无回执：如实报告，不猜测协议、不盲发
    private final Runnable startWatchdog = new Runnable() {
        @Override
        public void run() {
            if (gotRxSinceStart) return;
            setStatus("✗ 指令已发送但设备没有回执（" + currentProto + "）");
            log("! " + currentProto + " 无回执，未做任何自动重试");
            resetStartBtn();
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_ON) {
                log("蓝牙已开启，重启扫描");
                startScan();
            } else if (st == BluetoothAdapter.STATE_OFF) {
                stopScan();
                synchronized (machines) {
                    machines.clear();
                }
                applyHysteresisSort();
                updateBottomUI();
                log("蓝牙已关闭");
            }
        }
    };

    // ==================== 前端刷新循环 ====================
    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            updateEnvStatus();
            updateSteps();
            if (controlRssi != null && currentTarget != null) {
                controlRssi.setRssi(currentTarget.smoothedRssi);
            }
            mainHandler.postDelayed(this, 400);
        }
    };

    // ==================== 基础工具 ====================
    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private void vibrateFeedback(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ==================== 日志 / 状态 ====================
    private void log(final String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    log(line);
                }
            });
            return;
        }
        Log.d("BleApp", line);
        LogSink.i(line);
        long now = SystemClock.uptimeMillis();
        String stamp = String.format("%02d:%02d ", (now / 60000) % 60, (now / 1000) % 60);
        logBuf.append(stamp).append(line).append('\n');
        String[] lines = logBuf.toString().split("\n");
        if (lines.length > 40) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 40; i < lines.length; i++) sb.append(lines[i]).append('\n');
            logBuf.setLength(0);
            logBuf.append(sb);
        }
        if (logTv != null) {
            logTv.setText(logBuf.toString());
            if (logScroll != null) {
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }
    }

    private void setStatus(final String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    setStatus(s);
                }
            });
            return;
        }
        uiConnError = s.startsWith("✗");
        if (statusTv != null) {
            statusTv.setText(s);
            statusTv.setTextColor(uiConnError ? RED : (s.startsWith("●") || s.startsWith("✓") ? GREEN : TEXT_2));
        }
    }

    // ==================== 生命周期 ====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 离线日志：每行即时落盘，闪退也会留下堆栈
        LogSink.init(this);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                LogSink.e("!!! 未捕获异常 thread=" + thread.getName(), ex);
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(SURFACE);
            getWindow().setNavigationBarColor(SURFACE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> req = new ArrayList<>();
            if (checkCallingOrSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                req.add("android.permission.WRITE_EXTERNAL_STORAGE");
            }
            if (checkCallingOrSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                req.add("android.permission.ACCESS_FINE_LOCATION");
            }
            if (checkCallingOrSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                req.add("android.permission.ACCESS_COARSE_LOCATION");
            }
            if (!req.isEmpty()) {
                requestPermissions(req.toArray(new String[0]), 101);
            }
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }

        buildUi();

        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(btReceiver, f);
        }

        mainHandler.post(scanRefresher);
        mainHandler.post(uiTicker);

        startScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isScanning) startScan();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        LogSink.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (controlPage != null && controlPage.getVisibility() == View.VISIBLE) {
            closeActionPanel();
        } else {
            super.onBackPressed();
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(12);
    }

    // =====================================================================
    // 前端：整体结构
    // =====================================================================
    private void buildUi() {
        LinearLayout root = vbox(this);
        root.setBackgroundColor(BG);

        // ---------- 顶部导航 ----------
        LinearLayout appBar = hbox(this);
        appBar.setBackgroundColor(SURFACE);
        appBar.setPadding(dp(16), statusBarHeight() + dp(14), dp(16), dp(14));

        backBtn = medium(text(this, "‹", 30, TEXT));
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeActionPanel();
            }
        });
        appBar.addView(backBtn, margins(this, lp(dp(28), dp(36)), 0, 0, 6, 0));

        LinearLayout titleCol = vbox(this);
        titleCol.setLayoutParams(weight(this, 1f));
        appBarTitle = medium(text(this, "洗衣助手", 21, TEXT));
        titleCol.addView(appBarTitle);
        appBarSub = text(this, "靠近设备 · 选程序 · 一键启动", 12, MUTED);
        appBarSub.setPadding(0, dp(4), 0, 0);
        titleCol.addView(appBarSub);
        appBar.addView(titleCol);

        TextView help = medium(text(this, "使用帮助", 13, PRIMARY));
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        });
        appBar.addView(help);
        root.addView(appBar);

        View appBarLine = new View(this);
        appBarLine.setBackgroundColor(BORDER_SOFT);
        root.addView(appBarLine, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ---------- 内容区 ----------
        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, clp);

        // 首页
        homePage = new ScrollView(this);
        homePage.setFillViewport(true);
        LinearLayout homeContent = vbox(this);
        homeContent.setPadding(dp(16), dp(14), dp(16), dp(24));
        homePage.addView(homeContent);
        content.addView(homePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bannerBox = hbox(this);
        bannerBox.setBackground(stroke(this, RED_SOFT, RED_BORDER, 12));
        bannerBox.setPadding(dp(12), dp(11), dp(12), dp(11));
        bannerTv = text(this, "", 13, RED);
        bannerTv.setLayoutParams(weight(this, 1f));
        bannerBox.addView(bannerTv);
        TextView bannerAct = medium(text(this, "去处理", 13, RED));
        bannerAct.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBannerAction();
            }
        });
        bannerBox.addView(bannerAct);
        bannerBox.setVisibility(View.GONE);
        homeContent.addView(bannerBox, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 0, 12));

        // 扫描状态卡
        LinearLayout scanCard = hbox(this);
        scanCard.setBackground(stroke(this, SURFACE, BORDER, 14));
        scanCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout scanTextWrap = hbox(this);
        scanTextWrap.setLayoutParams(weight(this, 1f));
        scanDot = new View(this);
        scanDot.setBackground(fill(this, PRIMARY, 100));
        scanTextWrap.addView(scanDot, lp(dp(8), dp(8)));
        scanStatusTv = medium(text(this, "正在扫描附近设备…", 13, TEXT_2));
        LinearLayout.LayoutParams ssp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ssp.setMargins(dp(9), 0, 0, 0);
        scanTextWrap.addView(scanStatusTv, ssp);
        scanCard.addView(scanTextWrap);

        TextView rescan = medium(text(this, "重新扫描", 13, PRIMARY));
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在重新扫描");
                stopScan();
                startScan();
            }
        });
        scanCard.addView(rescan);
        homeContent.addView(scanCard, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        homeContent.addView(spacer(this, 14));

        deviceListBox = vbox(this);
        homeContent.addView(deviceListBox);

        // 空态
        emptyBox = card(this);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView emptyIcon = text(this, "🧺", 44, TEXT);
        emptyIcon.setGravity(Gravity.CENTER);
        emptyBox.addView(emptyIcon, margins(this,
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 10, 0, 0));
        TextView emptyTitle = medium(text(this, "还没有发现洗衣设备", 16, TEXT));
        emptyTitle.setGravity(Gravity.CENTER);
        emptyBox.addView(emptyTitle, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 14, 0, 0));
        TextView emptyTips = text(this,
                "1. 走到洗衣机 / 烘干机旁 2 米内\n2. 确认手机蓝牙已开启\n3. 允许定位权限（系统蓝牙扫描需要）",
                13, TEXT_2);
        emptyTips.setLineSpacing(0, 1.5f);
        emptyTips.setGravity(Gravity.CENTER);
        emptyBox.addView(emptyTips, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 6));
        homeContent.addView(emptyBox);

        TextView footer = text(this, "仅显示洗衣机和烘干机，其他蓝牙设备已自动过滤", 11, MUTED);
        footer.setGravity(Gravity.CENTER);
        homeContent.addView(footer, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 16, 0, 0));

        // 设备页
        controlPage = new ScrollView(this);
        controlPage.setFillViewport(true);
        controlPage.setVisibility(View.GONE);
        actionPanel = vbox(this);
        actionPanel.setPadding(dp(16), dp(14), dp(16), dp(24));
        controlPage.addView(actionPanel);
        content.addView(controlPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildControlPanel();

        // 底部结算栏
        bottomBar = hbox(this);
        bottomBar.setBackground(stroke(this, SURFACE, BORDER, 0));
        bottomBar.setPadding(dp(18), dp(12), dp(18), dp(14) + navBarHeight());
        bottomBar.setVisibility(View.GONE);

        LinearLayout priceCol = vbox(this);
        TextView priceLabel = text(this, "应付金额", 11, MUTED);
        priceCol.addView(priceLabel);
        priceTv = medium(text(this, "¥ --", 24, PRIMARY));
        priceCol.addView(priceTv, margins(this,
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 2, 0, 0));
        bottomBar.addView(priceCol);

        startBtn = new Button(this);
        startBtn.setAllCaps(false);
        startBtn.setTextSize(16);
        startBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        startBtn.setPadding(0, 0, 0, 0);
        startBtn.setMinHeight(0);
        startBtn.setMinimumHeight(0);
        startBtn.setStateListAnimator(null);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStartCommand();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        blp.setMargins(dp(18), 0, 0, 0);
        bottomBar.addView(startBtn, blp);
        root.addView(bottomBar);
        setStartEnabled(false);

        setContentView(root);
        showHome();
    }

    private void buildControlPanel() {
        // 设备信息卡
        LinearLayout head = card(this);
        LinearLayout row = hbox(this);
        TextView icon = text(this, "🌀", 22, PRIMARY);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(fill(this, PRIMARY_SOFT, 12));
        row.addView(icon, lp(dp(44), dp(44)));

        LinearLayout nameCol = vbox(this);
        nameCol.setPadding(dp(12), 0, dp(8), 0);
        nameCol.setLayoutParams(weight(this, 1f));
        actionTargetTv = medium(text(this, "设备", 17, TEXT));
        nameCol.addView(actionTargetTv);
        actionSubTv = text(this, "", 12, MUTED);
        actionSubTv.setPadding(0, dp(5), 0, 0);
        nameCol.addView(actionSubTv);
        row.addView(nameCol);

        controlRssi = new RssiBars(this);
        row.addView(controlRssi, lp(dp(24), dp(18)));
        head.addView(row);
        actionPanel.addView(head);
        actionPanel.addView(spacer(this, 12));

        // 连接状态卡
        LinearLayout statusCard = card(this);
        stepsView = new Steps(this);
        statusCard.addView(stepsView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        statusTv = text(this, "○ 未连接", 13, TEXT_2);
        statusTv.setGravity(Gravity.CENTER);
        statusCard.addView(statusTv, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 4, 0, 0));

        LinearLayout ops = hbox(this);
        ops.setGravity(Gravity.CENTER);
        TextView retry = medium(text(this, "重新连接", 13, PRIMARY));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentTarget != null) connectGatt(currentTarget.mac);
            }
        });
        ops.addView(retry);
        TextView switchType = medium(text(this, "机型不对？切换", 13, MUTED));
        switchType.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentTarget != null) {
                    currentTarget.isDryer = !currentTarget.isDryer;
                    selectedProgramIndex = currentTarget.isDryer ? 0 : 1;
                    buildPrograms();
                    refreshControlHeader();
                }
            }
        });
        ops.addView(switchType, margins(this,
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 28, 0, 0, 0));
        statusCard.addView(ops, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 12, 0, 0));
        actionPanel.addView(statusCard);
        actionPanel.addView(spacer(this, 12));

        // 程序卡
        LinearLayout progCard = card(this);
        progCard.addView(section(this, "选择程序"));
        progCard.addView(spacer(this, 10));
        programRow = vbox(this);
        progCard.addView(programRow);
        actionPanel.addView(progCard);
        actionPanel.addView(spacer(this, 12));

        // 调试卡（默认折叠）
        LinearLayout debugCard = card(this);
        LinearLayout debugHead = hbox(this);
        TextView dTitle = section(this, "调试信息（TX / RX）");
        dTitle.setLayoutParams(weight(this, 1f));
        debugHead.addView(dTitle);
        debugToggle = medium(text(this, "展开", 13, PRIMARY));
        debugHead.addView(debugToggle);
        debugHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                debugExpanded = !debugExpanded;
                logScroll.setVisibility(debugExpanded ? View.VISIBLE : View.GONE);
                debugToggle.setText(debugExpanded ? "收起" : "展开");
            }
        });
        debugCard.addView(debugHead);

        logTv = mono(text(this, "", 10.5f, TEXT_2));
        logTv.setPadding(dp(10), dp(8), dp(10), dp(8));
        logTv.setLineSpacing(0, 1.3f);
        logScroll = new ScrollView(this);
        logScroll.setBackground(fill(this, SURFACE_2, 10));
        logScroll.setVisibility(View.GONE);
        logScroll.addView(logTv);
        debugCard.addView(logScroll, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)), 0, 10, 0, 0));
        actionPanel.addView(debugCard);
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("怎么用")
                .setMessage("1. 走到洗衣机或烘干机旁边，保持 2 米以内。\n\n"
                        + "2. 首页会自动列出附近设备，信号越好排越前，卡片上能看到楼层、距离和价格区间。\n\n"
                        + "3. 点「使用」进入设备页，等状态变成「可以启动」。\n\n"
                        + "4. 选程序，确认金额，点「立即启动」。\n\n"
                        + "连不上怎么办\n"
                        + "· 靠近设备后点「重新连接」\n"
                        + "· 确认蓝牙和定位权限都已开启\n"
                        + "· 点「机型不对？切换」换一种设备类型\n\n"
                        + "日志（离线排查用）\n" + LogSink.currentPath())
                .setPositiveButton("知道了", null)
                .setNeutralButton("导出日志", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        shareLog();
                    }
                })
                .show();
    }

    /** 把本次会话日志分享出去（微信 / QQ / 文件传输助手），不需要连电脑 */
    private void shareLog() {
        File f = LogSink.currentFile();
        if (f == null || !f.exists()) {
            toast("日志文件还没生成：" + LogSink.currentPath());
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
            it.putExtra(Intent.EXTRA_SUBJECT, "洗衣助手日志");
            it.putExtra(Intent.EXTRA_TEXT, "日志路径：" + f.getAbsolutePath()
                    + "\n大小：" + (f.length() / 1024) + " KB");
            startActivity(Intent.createChooser(it, "导出日志"));
        } catch (Throwable t) {
            toast("导出失败，文件在：" + f.getAbsolutePath());
        }
    }

    // =====================================================================
    // 前端：环境状态 / 阶段
    // =====================================================================
    private void updateEnvStatus() {
        boolean btOk = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        boolean permOk = hasScanPermission();

        if (scanStatusTv != null) {
            String s;
            if (!btOk) s = "蓝牙未开启";
            else if (!permOk) s = "缺少定位权限";
            else if (stableDisplayList.isEmpty()) s = isScanning ? "正在扫描附近设备…" : "扫描已暂停";
            else s = "发现 " + stableDisplayList.size() + " 台设备 · 按信号排序";
            scanStatusTv.setText(s);
        }
        if (scanDot != null) {
            scanDot.setBackground(fill(this, !btOk || !permOk ? RED : (isScanning ? PRIMARY : MUTED), 100));
        }
        if (bannerBox != null) {
            boolean need = !btOk || !permOk;
            bannerBox.setVisibility(need ? View.VISIBLE : View.GONE);
            if (need) {
                bannerTv.setText(!permOk ? "需要定位权限才能扫描蓝牙设备" : "蓝牙未开启，无法发现设备");
            }
        }
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkCallingOrSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == PackageManager.PERMISSION_GRANTED
                || checkCallingOrSelfPermission("android.permission.ACCESS_COARSE_LOCATION")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onBannerAction() {
        if (!hasScanPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"}, 101);
        } else if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void updateSteps() {
        if (stepsView == null) return;
        int stage;
        if (isReady) stage = 3;
        else if (uiConnError) stage = -1;
        else if (isConnecting) stage = (currentGatt != null ? 2 : 1);
        else stage = 0;
        stepsView.setStage(stage);
    }

    // =====================================================================
    // 设备识别（后端，未改动）
    // =====================================================================
    public static class LaundryProbeResult {
        public boolean matched;
        public boolean isDryer;
        public String parsedName;
        public String proto;
    }

    private static String short16(UUID u) {
        if (u == null) return null;
        String s = u.toString().toLowerCase();
        if (s.length() != 36) return null;
        if (!s.endsWith(SIG_BASE_SUFFIX)) return null;
        return s.substring(4, 8);
    }

    private static String shortUuidList(ScanRecord record) {
        StringBuilder sb = new StringBuilder();
        List<ParcelUuid> us = (record != null) ? record.getServiceUuids() : null;
        if (us != null) {
            for (ParcelUuid pu : us) {
                String s = short16(pu.getUuid());
                sb.append(s != null ? s : pu.getUuid().toString()).append(',');
            }
        }
        return sb.toString();
    }

    /** 楼层机位编号：1-1 / 1_2 / 101 / 3F-2 / 3楼2 / A1-3 */
    /** 楼层机位号：1-1 / 1_2 / 101 / 3F-2 / 3楼2 / A1-3。刻意收紧，避免把 2025 这类型号当机位 */
    private static boolean isRoomCodeName(String n) {
        if (n == null || n.isEmpty()) return false;
        return Pattern.matches("^\\d{1,2}[-_ ]\\d{1,2}$", n)
                || Pattern.matches("^\\d{3}$", n)
                || Pattern.matches("^\\d{1,2}(F|L|楼)[-_ ]?\\d{1,2}$", n)
                || Pattern.matches("^[A-Z]{1,2}\\d{1,2}[-_]\\d{1,2}$", n)
                || Pattern.matches("^\\d{1,2}楼\\d{1,2}$", n);
    }

    /** 从设备名里推断楼层，用于卡片展示；推不出返回空串 */
    private static String floorLabel(String name) {
        if (name == null) return "";
        String n = name.trim().toUpperCase()
                .replace("洗衣机", "").replace("烘干机", "").replace("·", "").trim();
        java.util.regex.Matcher m = Pattern.compile("^(\\d{1,2})\\s*(?:F|L|楼)?\\s*[-_ ]\\s*\\d{1,2}$").matcher(n);
        if (m.find()) return m.group(1) + " 楼";
        m = Pattern.compile("^(\\d{1,2})\\s*(?:F|L|楼)").matcher(n);
        if (m.find()) return m.group(1) + " 楼";
        m = Pattern.compile("^(\\d)\\d{2,3}$").matcher(n);
        if (m.find()) return m.group(1) + " 楼";
        return "";
    }

    /** 明确不是洗衣设备的蓝牙产品关键词（耳机 / 音箱 / 手环 / 家电等） */
    private static boolean isNonLaundryName(String n) {
        if (n == null || n.isEmpty()) return false;
        String[] keys = {"BUDS", "EARBUD", "EARPHONE", "HEADPHONE", "HEADSET", "AIRPOD",
                "SPEAKER", "SOUNDBAR", "AUDIO", "QCY", "EDIFIER", "JBL", "BOSE", "SONY",
                "BEATS", "MARSHALL", "ANKER", "SOUNDCORE", "ENCO", "FREEBUDS", "TWS",
                "SOUNDPEATS", "HAYLOU", "BASEUS", "UGREEN", "REALME", "HONOR", "REDMI",
                "OPPO", "VIVO", "HUAWEI", "LENOVO", "THINKPLUS",
                "WATCH", "BAND", "FITBIT", "GARMIN",
                "耳机", "音箱", "手环", "手表", "电视", "空调", "牙刷", "体重秤",
                "KEYBOARD", "MOUSE", "PRINTER", "TOOTHBRUSH", "OBD", "TPMS", "SCALE",
                "BULB", "LAMP", "SWITCHBOT", "MIJIA", "LIGHT", "CAMERA", "ROUTER"};
        for (String k : keys) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    private static boolean isHex6Name(String n) {
        return n != null && Pattern.matches("^[0-9A-F]{6}$", n);
    }

    private static boolean isLaundryName(String n) {
        if (n == null || n.isEmpty()) return false;
        String[] keys = {"洗衣", "脱水", "烘干", "干衣", "WASH", "DRY", "LAUNDRY",
                "HGJ", "XYJ", "YQ", "HNH", "CLOTHES", "SCVTC"};
        for (String k : keys) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /**
     * 严格识别：必须有「强证据」才认，服务 UUID 本身不算证据。
     *
     * 强证据（满足其一）：
     *   1. MAC 落在已知设备表（整串或后 6 位）
     *   2. MAC 前缀属于校园洗烘板厂牌 OUI（Minew / 云里物里方案）
     *   3. 广播名含洗衣 / 烘干关键词
     *   4. 广播名是 6 位十六进制校园编号
     *   5. 广播名是楼层机位号（1-1 / 101 / 3F-2）
     *
     * 名称未知且 MAC 不在已知 OUI → 一律丢弃。
     * 原因：fff0 / fee9 / ffe0 是通用 UUID，手环、电子秤、门锁、玩具都在用，
     * 只按 UUID 判定会把整栋楼的路人设备都收进来。
     */
    private LaundryProbeResult auditLaundryDevice(String mac, String advName, ScanRecord record) {
        LaundryProbeResult res = new LaundryProbeResult();
        String uMac = mac.toUpperCase();
        String uName = (advName != null) ? advName.trim().toUpperCase() : "";
        String macEnd = uMac.replace(":", "");
        String tail6 = macEnd.length() >= 6 ? macEnd.substring(macEnd.length() - 6) : macEnd;

        // 0. 负向词表优先：明确的非洗衣产品直接丢弃
        if (isNonLaundryName(uName)) {
            return res;
        }

        // 1. 已知 MAC（整串）
        if (KNOWN_SCVTC.containsKey(uMac)) {
            res.matched = true;
            res.parsedName = KNOWN_SCVTC.get(uMac);
            res.isDryer = res.parsedName.contains("烘干") || uMac.contains("53:0B");
            res.proto = res.isDryer ? "HNH" : "YQ";
            return res;
        }
        // 1b. 已知 MAC（后 6 位）
        if (KNOWN_SCVTC.containsKey(tail6)) {
            res.matched = true;
            res.parsedName = KNOWN_SCVTC.get(tail6);
            res.isDryer = res.parsedName.contains("烘干");
            res.proto = res.isDryer ? "HNH" : "YQ";
            return res;
        }

        // 2. 厂牌 OUI：这类板子只用于校园洗烘/物联，可作为强证据
        boolean knownOui = uMac.startsWith("AC:23:3F:42") || uMac.startsWith("AC:23:3F:41")
                || uMac.startsWith("AC:23:3F:53") || uMac.startsWith("AC:9A:22:54")
                || uMac.startsWith("C8:47:8C") || uMac.startsWith("F0:13:C3");

        // 3/4/5. 名称证据
        boolean nameLaundry = isLaundryName(uName);
        boolean nameHex6 = isHex6Name(uName);
        boolean nameRoom = isRoomCodeName(uName);

        if (!(knownOui || nameLaundry || nameHex6 || nameRoom)) {
            // 无强证据：即便广播里有 fff0 / fee9 也不认
            return res;
        }

        // 到这里才认定为洗衣设备，协议只用 UUID 来「判型」，不作为入网依据
        String proto = protocolFromUuids(record);
        boolean dryerByProto = "HNH".equals(proto);
        boolean dryerByName = uName.contains("烘干") || uName.contains("干衣") || uName.contains("烘")
                || uName.contains("HGJ") || uName.contains("DRY") || uName.startsWith("HG_")
                || uName.startsWith("HG-") || uName.startsWith("HNH") || uMac.contains("53:0B");

        res.matched = true;
        res.isDryer = dryerByProto || dryerByName;
        res.proto = proto;

        if (!uName.isEmpty() && !isHex6Name(uName) && !isRoomCodeName(uName)) {
            res.parsedName = uName;
        } else if (!uName.isEmpty()) {
            res.parsedName = (res.isDryer ? "烘干机 " : "洗衣机 ") + uName;
        } else {
            res.parsedName = (res.isDryer ? "烘干机 " : "洗衣机 ") + tail6;
        }
        return res;
    }

    /** 只用来判断协议类型，不再作为「这是洗衣设备」的依据 */
    private static String protocolFromUuids(ScanRecord record) {
        List<ParcelUuid> uuids = (record != null) ? record.getServiceUuids() : null;
        if (uuids == null) return "AUTO";
        String best = "AUTO";
        for (ParcelUuid pu : uuids) {
            String u = short16(pu.getUuid());
            if (u == null) continue;
            if (u.equals("fff0")) return "YQ";
            if (u.equals("fee9") || u.equals("fee8")) best = "HNH";
            else if (u.equals("ffe0") || u.equals("fee0") || u.equals("ff00")) {
                if (!"HNH".equals(best)) best = "PULSE";
            }
        }
        return best;
    }

    // =====================================================================
    // 扫描（后端，未改动）
    // =====================================================================
    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (isScanning) return;
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) return;

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) handleScanResult(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                isScanning = false;
                log("! 扫描失败 code=" + errorCode);
                if (scanRetry < 3) {
                    scanRetry++;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startScan();
                        }
                    }, 1500);
                }
            }
        };

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            bleScanner.startScan(null, settings, scanCallback);
            isScanning = true;
            scanRetry = 0;
        } catch (Exception e) {
            isScanning = false;
        }
    }

    private void handleScanResult(ScanResult result) {
        if (result == null) return;
        BluetoothDevice dev = result.getDevice();
        if (dev == null || dev.getAddress() == null) return;
        String mac = dev.getAddress().toUpperCase();

        ScanRecord record = result.getScanRecord();
        String advName = (record != null) ? record.getDeviceName() : null;
        if (advName == null || advName.isEmpty()) advName = dev.getName();

        LaundryProbeResult probe = auditLaundryDevice(mac, advName, record);
        if (!probe.matched) {
            Log.d("BleScan", "skip " + mac + " name=" + advName
                    + " uuids=" + shortUuidList(record) + " rssi=" + result.getRssi());
            // 去重：同一设备 60 秒只落一次盘，避免广播刷屏把日志写满
            long nowMs = SystemClock.uptimeMillis();
            Long prev = lastScanLogAt.get(mac);
            if (prev == null || nowMs - prev > 60000) {
                lastScanLogAt.put(mac, nowMs);
                LogSink.i("SCAN skip " + mac + " name=" + advName
                        + " uuids=" + shortUuidList(record) + " rssi=" + result.getRssi());
            }
            return;
        }
        Log.d("BleScan", "HIT " + mac + " name=" + advName
                + " dryer=" + probe.isDryer + " proto=" + probe.proto
                + " uuids=" + shortUuidList(record));
        long hitNow = SystemClock.uptimeMillis();
        Long hitPrev = lastScanLogAt.get("HIT:" + mac);
        if (hitPrev == null || hitNow - hitPrev > 60000) {
            lastScanLogAt.put("HIT:" + mac, hitNow);
            LogSink.i("SCAN HIT " + mac + " name=" + advName
                    + " dryer=" + probe.isDryer + " proto=" + probe.proto
                    + " uuids=" + shortUuidList(record) + " rssi=" + result.getRssi());
        }

        int rawRssi = result.getRssi();
        boolean isNew = false;
        synchronized (machines) {
            MachineTarget t = machines.get(mac);
            if (t == null) {
                t = new MachineTarget(mac, probe.parsedName, probe.isDryer, rawRssi);
                t.proto = probe.proto;
                machines.put(mac, t);
                isNew = true;
            } else {
                t.updateRssi(rawRssi);
            }
        }

        if (isNew) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    applyHysteresisSort();
                    updateBottomUI();
                }
            });
        }
    }

    private void stopScan() {
        if (isScanning && bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        isScanning = false;
    }

    private void applyHysteresisSort() {
        List<MachineTarget> raw = new ArrayList<>();
        synchronized (machines) {
            raw.addAll(machines.values());
        }

        if (stableDisplayList.isEmpty()) {
            Collections.sort(raw, new Comparator<MachineTarget>() {
                @Override
                public int compare(MachineTarget a, MachineTarget b) {
                    return Float.compare(b.smoothedRssi, a.smoothedRssi);
                }
            });
            stableDisplayList.clear();
            stableDisplayList.addAll(raw);
            return;
        }

        Iterator<MachineTarget> it = stableDisplayList.iterator();
        while (it.hasNext()) {
            MachineTarget cur = it.next();
            if (!machines.containsKey(cur.mac)) {
                it.remove();
            }
        }

        for (MachineTarget m : raw) {
            boolean found = false;
            for (MachineTarget cur : stableDisplayList) {
                if (cur.mac.equals(m.mac)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                stableDisplayList.add(m);
            }
        }

        for (int i = stableDisplayList.size() - 1; i > 0; i--) {
            MachineTarget lower = stableDisplayList.get(i);
            MachineTarget upper = stableDisplayList.get(i - 1);
            if (lower.smoothedRssi > upper.smoothedRssi + 7.0f) {
                stableDisplayList.set(i, upper);
                stableDisplayList.set(i - 1, lower);
            }
        }
    }

    // =====================================================================
    // 前端：首页设备列表
    // =====================================================================
    private void updateBottomUI() {
        if (actionPanel.getVisibility() == View.VISIBLE) {
            return;
        }
        renderDeviceList();
    }

    private void renderDeviceList() {
        deviceListBox.removeAllViews();
        emptyBox.setVisibility(stableDisplayList.isEmpty() ? View.VISIBLE : View.GONE);

        int count = Math.min(stableDisplayList.size(), 12);
        for (int i = 0; i < count; i++) {
            deviceListBox.addView(buildDeviceCard(stableDisplayList.get(i), i));
        }
    }

    private View buildDeviceCard(final MachineTarget m, int index) {
        LinearLayout cardBox = card(this);
        clickable(cardBox, stroke(this, SURFACE, BORDER, 16));
        cardBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectDeviceAndOpenControl(m);
            }
        });

        LinearLayout top = hbox(this);
        TextView icon = text(this, m.isDryer ? "♨" : "🌀", 22, PRIMARY);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(fill(this, PRIMARY_SOFT, 12));
        top.addView(icon, lp(dp(44), dp(44)));

        LinearLayout info = vbox(this);
        info.setPadding(dp(12), 0, dp(8), 0);
        info.setLayoutParams(weight(this, 1f));
        TextView nameTv = medium(text(this, m.name, 16, TEXT));
        nameTv.setSingleLine(true);
        info.addView(nameTv);
        String floor = floorLabel(m.name);
        TextView subTv = text(this,
                (floor.isEmpty() ? "" : floor + " · ") + (m.isDryer ? "烘干机" : "洗衣机")
                        + " · 约 " + String.format("%.1f", m.getDistance())
                        + " 米 · " + RssiBars.levelText(m.smoothedRssi), 12, MUTED);
        subTv.setPadding(0, dp(5), 0, 0);
        info.addView(subTv);
        top.addView(info);

        RssiBars bars = new RssiBars(this);
        bars.setRssi(m.smoothedRssi);
        top.addView(bars, lp(dp(24), dp(18)));
        cardBox.addView(top);

        LinearLayout bottom = hbox(this);
        bottom.setPadding(0, dp(12), 0, 0);
        TextView price = medium(text(this, priceRange(m.isDryer), 15, TEXT));
        bottom.addView(price);
        TextView progCount = text(this, "  ·  4 档程序", 12, MUTED);
        progCount.setLayoutParams(weight(this, 1f));
        bottom.addView(progCount);

        TextView use = medium(text(this, "使用", 14, Color.WHITE));
        use.setGravity(Gravity.CENTER);
        use.setBackground(fill(this, PRIMARY, 10));
        bottom.addView(use, lp(dp(76), dp(38)));
        cardBox.addView(bottom);

        LinearLayout wrap = vbox(this);
        wrap.addView(cardBox, margins(this,
                lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 0, 12));
        return wrap;
    }

    private String priceRange(boolean dryer) {
        return dryer ? "¥1.50 – 4.50" : "¥0.80 – 2.80";
    }

    private void showHome() {
        controlPage.setVisibility(View.GONE);
        actionPanel.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        homePage.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.GONE);
        appBarTitle.setText("洗衣助手");
        appBarSub.setText("靠近设备 · 选程序 · 一键启动");
        renderDeviceList();
    }

    private void selectDeviceAndOpenControl(MachineTarget target) {
        currentTarget = target;
        homePage.setVisibility(View.GONE);
        controlPage.setVisibility(View.VISIBLE);
        actionPanel.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.VISIBLE);
        appBarTitle.setText("启动设备");
        appBarSub.setText("等待连接就绪后再启动");

        selectedProgramIndex = target.isDryer ? 0 : 1;
        refreshControlHeader();
        buildPrograms();
        setStatus("○ 准备连接");
        setStartEnabled(false);

        vibrateFeedback(20);
        log("→ 连接 " + target.mac);
        connectGatt(target.mac);
    }

    private void closeActionPanel() {
        closeGatt();
        currentTarget = null;
        showHome();
    }

    private void refreshControlHeader() {
        if (currentTarget == null) return;
        actionTargetTv.setText(currentTarget.name);
        StringBuilder sb = new StringBuilder();
        String fl = floorLabel(currentTarget.name);
        if (!fl.isEmpty()) sb.append(fl).append(" · ");
        sb.append(currentTarget.isDryer ? "烘干机" : "洗衣机");
        String proto = currentProto == null ? "" : currentProto;
        if (!proto.isEmpty() && !"UNKNOWN".equals(proto) && !"AUTO".equals(proto)) {
            sb.append(" · ").append(proto);
        }
        sb.append(" · 约 ").append(String.format("%.1f", currentTarget.getDistance())).append(" 米");
        actionSubTv.setText(sb.toString());
    }

    // =====================================================================
    // 前端：程序选择（列表式，价格直接可见）
    // =====================================================================
    private void buildPrograms() {
        programRow.removeAllViews();
        boolean isDryer = (currentTarget != null && currentTarget.isDryer);
        final String[] names = isDryer ? DRYER_NAMES : WASHER_NAMES;
        final String[] prices = isDryer ? DRYER_PRICES : WASHER_PRICES;
        final String[] subs = isDryer ? DRYER_SUB : WASHER_SUB;

        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            final boolean isSel = (idx == selectedProgramIndex);

            LinearLayout row = hbox(this);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            clickable(row, isSel
                    ? stroke(this, PRIMARY_SOFT, PRIMARY, 12)
                    : stroke(this, SURFACE, BORDER_SOFT, 12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedProgramIndex = idx;
                    vibrateFeedback(10);
                    buildPrograms();
                }
            });

            View radio = new View(this);
            radio.setBackground(isSel
                    ? stroke(this, PRIMARY, PRIMARY, 100)
                    : stroke(this, SURFACE, BORDER, 100));
            row.addView(radio, lp(dp(12), dp(12)));

            LinearLayout nameCol = vbox(this);
            nameCol.setPadding(dp(12), 0, 0, 0);
            nameCol.setLayoutParams(weight(this, 1f));
            nameCol.addView(medium(text(this, names[idx], 15, isSel ? TEXT : TEXT_2)));
            TextView dur = text(this, subs[idx], 12, MUTED);
            dur.setPadding(0, dp(4), 0, 0);
            nameCol.addView(dur);
            row.addView(nameCol);

            row.addView(medium(text(this, "¥ " + prices[idx], 16, isSel ? PRIMARY : TEXT_2)));

            programRow.addView(row, margins(this,
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, 0, 0, idx == names.length - 1 ? 0 : 8));
        }

        if (selectedProgramIndex >= 0 && selectedProgramIndex < names.length) {
            if (priceTv != null) {
                priceTv.setText("¥ " + prices[selectedProgramIndex]);
            }
            if (startBtn != null) {
                startBtn.setText("立即启动 · " + names[selectedProgramIndex]);
            }
        }
    }

    private void setStartEnabled(final boolean on) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    setStartEnabled(on);
                }
            });
            return;
        }
        if (startBtn != null) {
            startBtn.setEnabled(on);
            if (on) {
                startBtn.setTextColor(Color.WHITE);
                startBtn.setBackground(gradient(this, PRIMARY, PRIMARY_DARK, 12));
            } else {
                startBtn.setTextColor(MUTED);
                startBtn.setBackground(fill(this, BORDER_SOFT, 12));
            }
        }
    }

    private void resetStartBtn() {
        if (startBtn == null) return;
        buildPrograms();
        setStartEnabled(isReady);
    }

    // =====================================================================
    // GATT（后端，未改动）
    // =====================================================================
    private void connectGatt(final String mac) {
        if (isConnecting) return;
        isConnecting = true;
        isReady = false;
        setStartEnabled(false);
        closeGatt();
        isConnecting = true;
        writeBusy = false;
        synchronized (writeQueue) {
            writeQueue.clear();
        }
        rxBuf.setLength(0);

        mainHandler.removeCallbacks(connectTimeout);
        mainHandler.postDelayed(connectTimeout, 12000);

        try {
            BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(mac);
            currentGatt = dev.connectGatt(this, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (gatt != currentGatt) return;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        setStatus("◐ 已连接，正在读取服务…");
                        log("✓ GATT 已连接");
                        try {
                            gatt.requestMtu(247);
                        } catch (Exception ignored) {
                        }
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnecting = false;
                        isReady = false;
                        setStartEnabled(false);
                        setStatus("○ 已断开");
                        log("GATT 断开 status=" + status);
                        closeGatt();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (gatt != currentGatt) return;
                    writeCh = null;
                    notifyCh = null;
                    currentProto = "UNKNOWN";

                    // 两段式选择：先按服务归类，再在选定服务里挑特征，避免 fee8 抢先占坑
                    BluetoothGattService yqSvc = null, hnh9 = null, hnh8 = null, pulseSvc = null;
                    for (BluetoothGattService svc : gatt.getServices()) {
                        String su = short16(svc.getUuid());
                        if ("fff0".equals(su)) yqSvc = svc;
                        else if ("fee9".equals(su)) hnh9 = svc;
                        else if ("fee8".equals(su)) hnh8 = svc;
                        else if ("ffe0".equals(su) || "fee0".equals(su) || "ff00".equals(su)) pulseSvc = svc;
                    }

                    if (yqSvc != null) {
                        currentProto = "YQ";
                        for (BluetoothGattCharacteristic ch : yqSvc.getCharacteristics()) {
                            String cu = short16(ch.getUuid());
                            if ("fff2".equals(cu)) writeCh = ch;
                            if ("fff1".equals(cu)) notifyCh = ch;
                        }
                        if (writeCh == null) writeCh = firstWritable(yqSvc);
                        if (notifyCh == null) notifyCh = firstNotifiable(yqSvc);
                    } else if (hnh9 != null || hnh8 != null) {
                        // fee9 才是 HNH 串口服务；fee8 多为配置/OTA 通道，必须优先 fee9
                        BluetoothGattService svc = (hnh9 != null) ? hnh9 : hnh8;
                        currentProto = "HNH";
                        for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                            // HNH 特征不是 SIG 短 UUID，而是 128 位且以 9600 / 9601 结尾
                            String cu = ch.getUuid().toString().toLowerCase();
                            if (cu.endsWith("9600")) writeCh = ch;
                            else if (cu.endsWith("9601")) notifyCh = ch;
                        }
                        if (writeCh == null) writeCh = firstWritable(svc);
                        if (notifyCh == null) notifyCh = firstNotifiable(svc);
                        LogSink.i("GATT HNH 选用服务 " + svc.getUuid()
                                + " write=" + (writeCh != null ? writeCh.getUuid() : "null")
                                + " notify=" + (notifyCh != null ? notifyCh.getUuid() : "null"));
                    } else if (pulseSvc != null) {
                        currentProto = "PULSE";
                        for (BluetoothGattCharacteristic ch : pulseSvc.getCharacteristics()) {
                            int prop = ch.getProperties();
                            if ((prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                                    || (prop & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                writeCh = ch;
                            }
                            if ((prop & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                                notifyCh = ch;
                            }
                        }
                    }

                    if (writeCh == null) {
                        for (BluetoothGattService svc : gatt.getServices()) {
                            BluetoothGattCharacteristic c = firstWritable(svc);
                            if (c != null) {
                                writeCh = c;
                                // 找不到已知服务就不猜协议，留给 doStartCommand 拒绝发送
                                break;
                            }
                        }
                    }

                    // 诊断：把设备的完整服务/特征表打到 logcat
                    for (BluetoothGattService svc : gatt.getServices()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("SVC ").append(svc.getUuid()).append(" -> ");
                        for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                            sb.append(ch.getUuid()).append("[p=")
                                    .append(Integer.toHexString(ch.getProperties())).append("] ");
                        }
                        Log.d("BleGatt", sb.toString());
                        LogSink.i("GATT " + sb.toString());
                    }

                    if (notifyCh != null) {
                        try {
                            gatt.setCharacteristicNotification(notifyCh, true);
                            BluetoothGattDescriptor desc = notifyCh.getDescriptor(UUID_CCCD);
                            if (desc != null) {
                                // 只支持 INDICATE 的特征必须用 INDICATION 值，否则永远收不到回执
                                boolean canNotify = (notifyCh.getProperties()
                                        & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                                desc.setValue(canNotify
                                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                                gatt.writeDescriptor(desc);
                                Log.d("BleGatt", "CCCD " + (canNotify ? "NOTIFY" : "INDICATE")
                                        + " on " + notifyCh.getUuid());
                                LogSink.i("GATT CCCD " + (canNotify ? "NOTIFY" : "INDICATE")
                                        + " on " + notifyCh.getUuid());
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    isConnecting = false;
                    mainHandler.removeCallbacks(connectTimeout);

                    if (writeCh == null) {
                        isReady = false;
                        setStatus("✗ 未找到可写特征，无法下发指令");
                        log("! 服务枚举完成但无可写通道");
                    } else {
                        isReady = true;
                        setStatus("● 已就绪，可以启动");
                        log("✓ 服务就绪 " + currentProto + " write=" + writeCh.getUuid());
                        setStartEnabled(true);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshControlHeader();
                        }
                    });
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic ch, int status) {
                    if (gatt != currentGatt) return;
                    writeBusy = false;
                    mainHandler.removeCallbacks(writeWatchdog);
                    pumpWriteQueue();
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
                    if (gatt != currentGatt) return;
                    gotRxSinceStart = true;
                    byte[] raw = ch.getValue();
                    String hex = toHex(raw);
                    log("RX " + hex);
                    handleRx(hex);
                }
            }, BluetoothDevice.TRANSPORT_LE);

        } catch (Exception e) {
            isConnecting = false;
            setStatus("✗ 连接失败: " + e.getClass().getSimpleName());
            log("! 连接异常 " + e);
        }
    }

    private static BluetoothGattCharacteristic firstWritable(BluetoothGattService svc) {
        for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
            int p = ch.getProperties();
            if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                return ch;
            }
        }
        return null;
    }

    private static BluetoothGattCharacteristic firstNotifiable(BluetoothGattService svc) {
        for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
            int p = ch.getProperties();
            if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                return ch;
            }
        }
        return null;
    }

    private void closeGatt() {
        if (currentGatt != null) {
            try {
                currentGatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                currentGatt.close();
            } catch (Exception ignored) {
            }
            currentGatt = null;
        }
        writeCh = null;
        notifyCh = null;
        isReady = false;
        writeBusy = false;
        mainHandler.removeCallbacks(writeWatchdog);
        mainHandler.removeCallbacks(connectTimeout);
        mainHandler.removeCallbacks(startWatchdog);
        synchronized (writeQueue) {
            writeQueue.clear();
        }
    }

    private void doStartCommand() {
        if (currentTarget == null) return;
        if (currentGatt == null || writeCh == null || !isReady) {
            setStatus("✗ 通道未就绪，请重连");
            return;
        }

        boolean isDryer = currentTarget.isDryer;
        int pulse = isDryer ? DRYER_PULSES[selectedProgramIndex] : WASHER_PULSES[selectedProgramIndex];

        if ("YQ".equals(currentProto)) {
            String cmd = "X5750000" + pulse;
            queueSend(cmd.getBytes(StandardCharsets.UTF_8));
        } else if ("HNH".equals(currentProto)) {
            if (hnhFyKey == null) {
                pendingStart = true;
                hnhKeyIndex = 0;
                setStatus("◐ 正在与设备协商密钥 1/" + HNH_KEY_LADDER.length);
                queueSend(buildHnhFrame("A70108", HNH_KEY_LADDER[hnhKeyIndex]));
            } else {
                sendHnhStart();
            }
        } else {
            // 协议未识别：不发送任何猜测指令，避免未知设备被乱写一通
            setStatus("✗ 未识别该设备协议（" + currentProto + "），已停止发送");
            log("! proto=" + currentProto + " 非 YQ/HNH，拒绝盲发");
            resetStartBtn();
            return;
        }

        if (startBtn != null) {
            startBtn.setText("启动中，请稍候…");
        }
        setStatus("◐ 指令已下发，等待设备回执…");

        fallbackCount = 0;
        gotRxSinceStart = false;
        mainHandler.removeCallbacks(startWatchdog);
        mainHandler.postDelayed(startWatchdog, 2600);
    }

    private void queueSend(byte[] payload) {
        if (payload == null) return;
        synchronized (writeQueue) {
            writeQueue.add(payload);
        }
        pumpWriteQueue();
    }

    private void pumpWriteQueue() {
        if (writeBusy || currentGatt == null || writeCh == null) return;
        byte[] next;
        synchronized (writeQueue) {
            next = writeQueue.poll();
        }
        if (next == null) return;

        writeBusy = true;
        try {
            int props = writeCh.getProperties();
            // 有「带响应写」能力时优先用它，能确认对端真的收到
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                writeCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writeCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
            writeCh.setValue(next);
            boolean ok = currentGatt.writeCharacteristic(writeCh);
            if (!ok) {
                writeBusy = false;
                log("! 写入被栈拒绝");
                return;
            }
            log("TX " + toHex(next));
            mainHandler.removeCallbacks(writeWatchdog);
            mainHandler.postDelayed(writeWatchdog, 1500);
        } catch (Exception e) {
            writeBusy = false;
            log("! 写入异常 " + e.getClass().getSimpleName());
        }
    }

    private void handleRx(String chunk) {
        String h = chunk;
        if (h == null || h.isEmpty()) return;

        if (!h.startsWith("A8")) {
            rxBuf.append(h);
            String buf = rxBuf.toString();
            int idx = buf.indexOf("A8");
            if (idx < 0) {
                if (buf.length() > 64) rxBuf.setLength(0);
                return;
            }
            buf = buf.substring(idx);
            if (buf.length() < 8) return;
            h = buf;
            rxBuf.setLength(0);
        }

        if (h.startsWith("A80200")) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (startBtn != null) {
                        startBtn.setText("✓ 已启动，机器开始运转");
                        startBtn.setBackground(fill(MainActivity.this, GREEN_SOFT, 12));
                        startBtn.setTextColor(C_GREEN);
                    }
                    setStatus("✓ 启动成功");
                    vibrateFeedback(180);
                }
            });
            log("✓ 启动回执 A80200");
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    resetStartBtn();
                }
            }, 6000);
            return;
        }

        handleHnhResponse(h);
    }

    private void handleHnhResponse(String hex) {
        if (hex == null || hex.length() < 6) return;

        if (hex.startsWith("A80102")) {
            if (hnhKeyIndex + 1 < HNH_KEY_LADDER.length) {
                hnhKeyIndex++;
                setStatus("◐ 正在与设备协商密钥 " + (hnhKeyIndex + 1) + "/" + HNH_KEY_LADDER.length);
                log("密钥 " + hnhKeyIndex + " 被拒，换下一档");
                queueSend(buildHnhFrame("A70108", HNH_KEY_LADDER[hnhKeyIndex]));
            } else {
                pendingStart = false;
                setStatus("✗ 设备拒绝了全部密钥，请重试");
                log("! 密钥梯队耗尽");
                resetStartBtn();
            }
            return;
        }

        if (hex.startsWith("A8010A") && hex.length() >= 22) {
            String mid = hex.substring(6, hex.length() - 6);
            byte[] mid8 = hexToBytes(mid);
            if (mid8 != null && mid8.length >= 8) {
                byte[] v = new byte[8];
                v[0] = (byte) (mid8[0] ^ 0x20);
                v[1] = (byte) (mid8[1] ^ 0x16);
                v[2] = (byte) (mid8[2] ^ 0x10);
                v[3] = (byte) (mid8[3] ^ 0x18);
                v[4] = (byte) (mid8[4] ^ v[0]);
                v[5] = (byte) (mid8[5] ^ v[1]);
                v[6] = (byte) (mid8[6] ^ v[2]);
                v[7] = (byte) (mid8[7] ^ v[3]);
                hnhPByte = mid8[2] & 0xFF;
                hnhQByte = mid8[3] & 0xFF;

                String activeKey = HNH_KEY_LADDER[hnhKeyIndex];
                byte[] sk = hexToBytes(activeKey);
                byte[] fw = desEnc(sk, v);
                hnhFyKey = (fw != null) ? desEnc(fw, sk) : null;

                if (hnhFyKey == null) {
                    setStatus("✗ 会话密钥派生失败");
                    log("! DES 派生失败");
                    return;
                }
                setStatus("◐ 密钥协商成功，正在取单…");
                log("✓ 密钥 " + (hnhKeyIndex + 1) + " 协商成功");
                queueSend(buildHnhFrame("A70A08", activeKey));
            }
            return;
        }

        if (hex.startsWith("A80A00")) {
            if (pendingStart) {
                pendingStart = false;
                sendHnhStart();
            }
        }
    }

    private void sendHnhStart() {
        if (hnhFyKey == null || currentTarget == null) return;
        int pulse = currentTarget.isDryer ? DRYER_PULSES[selectedProgramIndex] : WASHER_PULSES[selectedProgramIndex];
        int val = pulse * 100;
        byte I = (byte) ((val >> 8) & 0xFF);
        byte J = (byte) (val & 0xFF);
        byte[] K = new byte[]{(byte) 0x88, (byte) 0x88, (byte) 0x88, (byte) 0x88};

        byte[] payload = new byte[]{K[0], K[1], K[2], K[3], I, J, (byte) hnhPByte, (byte) hnhQByte};
        byte[] enc = desEnc(payload, hnhFyKey);
        if (enc == null) {
            setStatus("✗ 启动帧加密失败");
            return;
        }
        queueSend(buildHnhFrame("A70208", toHex(enc)));
        setStatus("◐ 启动帧已发送，等待回执…");
    }

    private static byte[] buildHnhFrame(String headerHex, String payloadHex) {
        byte[] head = hexToBytes(headerHex);
        byte[] payload = hexToBytes(payloadHex);
        if (head == null) return new byte[0];
        if (payload == null) payload = new byte[0];
        byte[] frame = new byte[head.length + payload.length + 1];
        System.arraycopy(head, 0, frame, 0, head.length);
        System.arraycopy(payload, 0, frame, head.length, payload.length);
        byte x = 0;
        for (int i = 0; i < head.length + payload.length; i++) x ^= frame[i];
        frame[frame.length - 1] = x;
        return frame;
    }

    private static byte[] desEnc(byte[] plain8, byte[] key8) {
        try {
            if (plain8 == null || key8 == null || key8.length != 8) return null;
            Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key8, "DES"));
            return c.doFinal(plain8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
