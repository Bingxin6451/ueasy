package com.ueasy.client;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 纯前端组件库：浅色工具型设计系统。
 * 不包含任何蓝牙 / 协议逻辑。
 */
final class UiKit {

    // ---------- 配色（浅色工具风） ----------
    static final int BG = Color.parseColor("#F4F6FA");
    static final int SURFACE = Color.parseColor("#FFFFFF");
    static final int SURFACE_2 = Color.parseColor("#F8FAFC");
    static final int BORDER = Color.parseColor("#E4E9F0");
    static final int BORDER_SOFT = Color.parseColor("#EEF2F7");

    static final int TEXT = Color.parseColor("#101828");
    static final int TEXT_2 = Color.parseColor("#475467");
    static final int MUTED = Color.parseColor("#98A2B3");

    static final int PRIMARY = Color.parseColor("#2563EB");
    static final int PRIMARY_DARK = Color.parseColor("#1D4ED8");
    static final int PRIMARY_SOFT = Color.parseColor("#EFF4FF");
    static final int PRIMARY_BORDER = Color.parseColor("#B2CCFF");

    static final int GREEN = Color.parseColor("#067647");
    static final int GREEN_SOFT = Color.parseColor("#ECFDF3");
    static final int GREEN_BORDER = Color.parseColor("#A6F4C5");

    static final int AMBER = Color.parseColor("#B54708");
    static final int AMBER_SOFT = Color.parseColor("#FFFAEB");
    static final int AMBER_BORDER = Color.parseColor("#FEDF89");

    static final int RED = Color.parseColor("#B42318");
    static final int RED_SOFT = Color.parseColor("#FEF3F2");
    static final int RED_BORDER = Color.parseColor("#FECDCA");

    // ---------- 单位 ----------
    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    // ---------- 形状 ----------
    static GradientDrawable fill(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable stroke(Context c, int color, int strokeColor, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{from, to});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable topRound(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(c, radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------- 文本 ----------
    static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView medium(TextView t) {
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView mono(TextView t) {
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 13, TEXT_2);
        medium(t);
        return t;
    }

    // ---------- 容器 ----------
    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(Context c, float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margins(Context c, LinearLayout.LayoutParams p,
                                             int l, int t, int r, int b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        v.setBackgroundColor(BORDER_SOFT);
        return v;
    }

    /** 白色卡片容器 */
    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(stroke(c, SURFACE, BORDER, 16));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return l;
    }

    static void clickable(View v, android.graphics.drawable.Drawable background) {
        v.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x142563EB), background, null));
        v.setClickable(true);
        v.setFocusable(true);
    }

    static TextView pill(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 11, fg);
        medium(t);
        t.setBackground(fill(c, bg, 6));
        t.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ---------- 自绘小部件 ----------

    /** 四格信号强度条 */
    static class RssiBars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = 0;
        private int color = PRIMARY;

        RssiBars(Context c) {
            super(c);
        }

        void setRssi(float rssi) {
            int lv;
            if (rssi >= -55) lv = 4;
            else if (rssi >= -67) lv = 3;
            else if (rssi >= -78) lv = 2;
            else if (rssi >= -90) lv = 1;
            else lv = 0;
            if (lv != level) {
                level = lv;
                invalidate();
            }
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        static String levelText(float rssi) {
            if (rssi >= -55) return "信号极好";
            if (rssi >= -67) return "信号良好";
            if (rssi >= -78) return "信号一般";
            if (rssi >= -90) return "信号较弱";
            return "信号微弱";
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float gap = w * 0.12f;
            float bw = (w - gap * 3) / 4f;
            for (int i = 0; i < 4; i++) {
                float bh = h * (0.32f + 0.22f * i);
                float left = i * (bw + gap);
                p.setColor(i < level ? color : BORDER);
                canvas.drawRoundRect(left, h - bh, left + bw, h, bw * 0.35f, bw * 0.35f, p);
            }
        }
    }

    /** 连接阶段：连接设备 → 读取服务 → 可以启动 */
    static class Steps extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int stage = 0;
        private final String[] labels = {"连接设备", "读取服务", "可以启动"};

        Steps(Context c) {
            super(c);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setStage(int s) {
            if (stage != s) {
                stage = s;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cy = h * 0.26f;
            float step = w / 3f;
            tp.setTextSize(h * 0.30f);

            for (int i = 0; i < 3; i++) {
                float cx = step * i + step / 2f;
                boolean done = stage > i || stage == 3;
                boolean active = stage == i + 1;
                int col = stage == -1 ? RED : (done ? GREEN : (active ? PRIMARY : BORDER));

                if (i < 2) {
                    p.setStrokeWidth(h * 0.035f);
                    p.setColor(stage > i + 1 || stage == 3 ? GREEN : BORDER);
                    canvas.drawLine(cx + h * 0.16f, cy, cx + step - h * 0.16f, cy, p);
                }
                p.setColor(col);
                canvas.drawCircle(cx, cy, h * (active ? 0.13f : 0.10f), p);
                tp.setColor(done || active ? TEXT_2 : MUTED);
                canvas.drawText(labels[i], cx, h * 0.80f, tp);
            }
        }
    }
}
