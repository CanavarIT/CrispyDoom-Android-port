package com.crispy.doom;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/**
 * Компактный экранный геймпад для Crispy Doom (SDL2).
 *
 *  - Плавающий джойстик слева: вперёд/назад + страйф, край круга = бег (Shift)
 *  - Справа свайп по свободной зоне = плавный поворот (мышь), либо клавишами
 *  - FIRE (Ctrl) — можно вести пальцем и целиться одновременно
 *  - USE (Space), выбор оружия 1..7 во всплывающей полосе, MAP (Tab)
 *  - Режимы переключаются САМИ: Crispy Doom захватывает мышь только в игре
 *    (уровень, меню закрыто) — это ловится в SDLActivity.setRelativeMouseEnabled.
 *    Меню/титул: джойстик = стрелки, OK = Enter, BACK = Esc, Y / N / QS / QL.
 *    Игра: страйф, FIRE, USE, оружие 1..7, MAP. ☰ = Esc, ⋯ = Y/N/QS/QL.
 *  - Настройки: размер, прозрачность, чувствительность, вибро, левша, ...
 *
 * Все события уходят напрямую в SDL (onNativeKeyDown/Up, onNativeMouse).
 */
public class TouchControlsView extends View {

    // ---------- идентификаторы кнопок ----------
    private static final int B_FIRE = 0, B_USE = 1, B_WEAP = 2, B_MENU = 3, B_MAP = 4,
            B_GEAR = 5, B_Y = 6, B_N = 7, B_QS = 8, B_QL = 9, B_MORE = 10, B_KBD = 11;

    // ---------- роли пальцев ----------
    private static final int R_NONE = 0, R_STICK = 1, R_BTN = 2, R_LOOK = 3;

    // ---------- цвета ----------
    private static final int C_FIRE = 0xFFFF4A38;
    private static final int C_USE = 0xFFFFB020;
    private static final int C_WEAP = 0xFF35C8FF;
    private static final int C_GLASS = 0xFFB8C4D0;
    private static final int C_GREEN = 0xFF4CD964;
    private static final int C_RED = 0xFFFF5A4D;
    private static final int C_RUN = 0xFFFFA726;
    private static final int C_DARK = 0xFF10141A;
    private static final int C_STICK = 0xFF6FD3FF;

    private static class Btn {
        final int id;
        float cx, cy, r;

        Btn(int id) {
            this.id = id;
        }
    }

    private static class Ptr {
        int role = R_NONE;
        Btn btn;
        int key;      // клавиша, удерживаемая кнопкой
        int key2;     // резерв
        Runnable lp;  // долгое нажатие (☰: ручное переключение раскладки)
        boolean longFired;
        int turnKey;  // клавиша поворота (режим "клавишами")
        float lastX, anchorX;
    }

    // ---------- состояние ----------
    private final Btn[] btns = new Btn[12];
    private final SparseArray<Ptr> ptrs = new SparseArray<>();
    private final SparseIntArray held = new SparseIntArray(); // keycode -> счётчик

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG); // иконки
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final SharedPreferences prefs;
    private final float density;

    // настройки
    private float scale = 1.0f;     // 0.6 .. 1.4
    private int opacity = 85;       // 20 .. 100
    private int sens = 6;           // 1 .. 15
    private boolean haptic = true;
    private boolean alwaysRun = false;
    private boolean keyTurn = false;
    private boolean lefty = false;

    // раскладка
    private float W, H, unit;
    private float stickR, defStickX, defStickY;
    private float k = 1f; // временный множитель прозрачности

    // режимы
    private volatile boolean menuMode = true; // true: меню / титул / пауза, false: игра
    private boolean autoMode = false;          // режим переключает сама игра
    private long startTime = SystemClock.uptimeMillis();
    private boolean touched = false;           // был ли хоть один тап по экрану
    private boolean wasInGame = false;         // (ручной режим) уже заходили в игру
    private volatile boolean advanceMode = false; // интермиссия / финал: OK и BACK листают дальше
    private boolean kbdOn = false;             // экранная клавиатура сейчас показана
    private boolean levelLive = false;         // движок: идёт настоящая игра (уровень, не демо)
    private long lockUntil = 0;                // после нашей кнопки не слушаем движок ~0.5 с
    private final EngineProbe probe;
    private boolean popup = false; // полоса выбора оружия
    private boolean more = false;  // полоса Y / N / QS / QL (в игре, по кнопке ⋯)

    // джойстик
    private boolean stickActive = false;
    private int stickId = -1;
    private float stickCx, stickCy, knobDx, knobDy;
    private int heldH = 0, heldV = 0, heldRun = 0;
    private int hDir = 0, vDir = 0;
    private boolean runOn = false;

    private float remX = 0f;

    /** Опрос состояния движка: меню открыто / идёт уровень. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            syncMode();
            boolean k = kbdShownNow();
            if (k != kbdOn) {
                kbdOn = k;
                invalidate();
            }
            postDelayed(this, 100);
        }
    };

    private final Runnable hidePopup = () -> {
        popup = false;
        invalidate();
    };
    private final Runnable hideMore = () -> {
        more = false;
        invalidate();
    };

    public TouchControlsView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("touch_controls", Context.MODE_PRIVATE);
        probe = new EngineProbe(ctx.getApplicationInfo().sourceDir);
        for (int i = 0; i < btns.length; i++) btns[i] = new Btn(i);
        setFocusable(false);
        setHapticFeedbackEnabled(true);
        ip.setStrokeCap(Paint.Cap.ROUND);
        ip.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        load();
    }

    // =====================================================================
    //  Настройки
    // =====================================================================

    private void load() {
        scale = prefs.getFloat("scale", 1.0f);
        opacity = prefs.getInt("opacity", 85);
        sens = prefs.getInt("sens", 6);
        haptic = prefs.getBoolean("haptic", true);
        alwaysRun = prefs.getBoolean("alwaysRun", false);
        keyTurn = prefs.getBoolean("keyTurn", false);
        lefty = prefs.getBoolean("lefty", false);
    }

    private void save() {
        prefs.edit()
                .putFloat("scale", scale)
                .putInt("opacity", opacity)
                .putInt("sens", sens)
                .putBoolean("haptic", haptic)
                .putBoolean("alwaysRun", alwaysRun)
                .putBoolean("keyTurn", keyTurn)
                .putBoolean("lefty", lefty)
                .apply();
    }

    private void showSettings() {
        releaseAll();
        final Context ctx = getContext();
        final float d = density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        root.setPadding(pad, (int) (8 * d), pad, 0);

        root.addView(seekRow(ctx, "Размер кнопок", 60, 140, Math.round(scale * 100), "%", v -> {
            scale = v / 100f;
            buildLayout();
            invalidate();
        }));
        root.addView(seekRow(ctx, "Прозрачность", 20, 100, opacity, "%", v -> {
            opacity = v;
            invalidate();
        }));
        root.addView(seekRow(ctx, "Чувствительность поворота", 1, 15, sens, "", v -> sens = v));

        root.addView(checkRow(ctx, "Вибро-отклик", haptic, v -> haptic = v));
        root.addView(checkRow(ctx, "Всегда бежать", alwaysRun, v -> alwaysRun = v));
        root.addView(checkRow(ctx, "Поворот клавишами (если мышь не работает)", keyTurn, v -> keyTurn = v));
        root.addView(checkRow(ctx, "Левша (зеркальная раскладка)", lefty, v -> {
            lefty = v;
            buildLayout();
            invalidate();
        }));

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);

        new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Управление")
                .setView(sv)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Сброс", (dlg, w) -> {
                    scale = 1.0f;
                    opacity = 85;
                    sens = 6;
                    haptic = true;
                    alwaysRun = false;
                    keyTurn = false;
                    lefty = false;
                    save();
                    buildLayout();
                    invalidate();
                })
                .setOnDismissListener(dlg -> save())
                .show();
    }

    private View seekRow(Context ctx, final String label, final int min, int max, int cur,
                         final String suffix, final IntConsumer onChange) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
        final TextView tv = new TextView(ctx);
        tv.setText(label + ": " + cur + suffix);
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = min + p;
                tv.setText(label + ": " + v + suffix);
                onChange.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        row.addView(tv);
        row.addView(sb);
        return row;
    }

    private interface BoolConsumer {
        void accept(boolean v);
    }

    private View checkRow(Context ctx, String label, boolean cur, final BoolConsumer onChange) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(label);
        cb.setChecked(cur);
        cb.setOnCheckedChangeListener((b, v) -> onChange.accept(v));
        return cb;
    }

    // =====================================================================
    //  Раскладка
    // =====================================================================

    /** Зеркалирование по X для режима "левша". */
    private float mx(float x) {
        return lefty ? W - x : x;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildLayout();
    }

    private void buildLayout() {
        W = getWidth();
        H = getHeight();
        if (W <= 0 || H <= 0) return;
        float s = density * scale;
        unit = s;
        float m = 16 * s;

        // джойстик
        stickR = 54 * s;
        defStickX = mx(m * 1.4f + stickR);
        defStickY = H - m - stickR;

        // правый нижний кластер (в немиррорных координатах)
        float rF = 38 * s, rU = 25 * s, rW = 21 * s;
        float fx = W - m - rF, fy = H - m - rF;
        Btn fire = btns[B_FIRE];
        fire.r = rF;
        fire.cx = mx(fx);
        fire.cy = fy;

        float dU = rF + rU + 8 * s;
        Btn use = btns[B_USE];
        use.r = rU;
        use.cx = mx(fx + (float) (dU * Math.cos(Math.toRadians(150))));
        use.cy = fy - (float) (dU * Math.sin(Math.toRadians(150)));

        float dW = rF + rW + 8 * s;
        Btn weap = btns[B_WEAP];
        weap.r = rW;
        weap.cx = mx(fx + (float) (dW * Math.cos(Math.toRadians(100))));
        weap.cy = fy - (float) (dW * Math.sin(Math.toRadians(100)));

        // верхний ряд справа: меню, карта, настройки
        float rS = 17 * s;
        float ty = m * 0.7f + rS;
        float step = 2 * rS + 8 * s;
        Btn menu = btns[B_MENU];
        menu.r = rS;
        menu.cx = W - m - rS;
        menu.cy = ty;
        Btn map = btns[B_MAP];
        map.r = rS;
        map.cx = menu.cx - step;
        map.cy = ty;
        Btn gear = btns[B_GEAR];
        gear.r = rS;
        gear.cx = map.cx - step;
        gear.cy = ty;
        Btn mo = btns[B_MORE];
        mo.r = rS;
        mo.cx = gear.cx - step;
        mo.cy = ty;

        // верхний ряд по центру (по кнопке ⋯): Y N QS QL
        float st2 = 2 * rS + 10 * s;
        int[] ids = {B_Y, B_N, B_QS, B_QL, B_KBD};
        for (int i = 0; i < ids.length; i++) {
            Btn b = btns[ids[i]];
            b.r = rS;
            b.cx = W / 2f + (i - 2f) * st2;
            b.cy = ty;
        }
    }

    private boolean isVisible(Btn b) {
        switch (b.id) {
            case B_WEAP:
            case B_MAP:
            case B_MORE:
                return !menuMode;
            case B_Y:
            case B_N:
            case B_QS:
            case B_QL:
            case B_KBD:
                return menuMode || more;
            default:
                return true;
        }
    }

    private float popupCx(int i) { // i = 0..6
        float s = unit;
        float rP = 19 * s, gap = 6 * s, m = 16 * s;
        return mx(W - m - rP - (6 - i) * (2 * rP + gap));
    }

    private float popupCy() {
        float s = unit;
        return btns[B_WEAP].cy - btns[B_WEAP].r - 19 * s - 12 * s;
    }

    private boolean inStickZone(float x) {
        return lefty ? x > W * 0.58f : x < W * 0.42f;
    }

    // =====================================================================
    //  Отрисовка
    // =====================================================================

    private int col(int argb, float mul) {
        int a = Math.round(Color.alpha(argb) * mul * k * opacity / 100f);
        if (a > 255) a = 255;
        if (a < 0) a = 0;
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (W <= 0) return;
        drawStick(c);
        for (Btn b : btns) {
            if (isVisible(b)) drawBtn(c, b);
        }
        if (popup) drawPopup(c);
    }

    /** Стеклянная круглая кнопка. */
    private void drawGlass(Canvas c, float cx, float cy, float r, int accent, boolean pressed) {
        float rr = pressed ? r * 0.93f : r;
        paint.setStyle(Paint.Style.FILL);
        if (pressed) {
            paint.setShader(new RadialGradient(cx, cy, rr * 1.4f,
                    new int[]{col(accent, 0.60f), col(accent, 0f)},
                    new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rr * 1.4f, paint);
        }
        paint.setShader(new RadialGradient(cx - rr * 0.25f, cy - rr * 0.30f, rr * 1.3f,
                new int[]{col(pressed ? accent : mix(accent, C_DARK, 0.55f), pressed ? 0.80f : 0.62f),
                        col(C_DARK, 0.68f)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rr, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * unit);
        paint.setColor(col(pressed ? accent : 0xFFFFFFFF, pressed ? 0.95f : 0.42f));
        c.drawCircle(cx, cy, rr, paint);

        // блик
        paint.setStrokeWidth(1.2f * unit);
        paint.setColor(col(0xFFFFFFFF, 0.30f));
        float h = rr * 0.84f;
        rect.set(cx - h, cy - h, cx + h, cy + h);
        c.drawArc(rect, 205, 130, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = (int) (Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.argb(255, r, g, bl);
    }

    private void label(Canvas c, String text, float cx, float cy, float size, int color) {
        ip.setStyle(Paint.Style.FILL);
        ip.setTextAlign(Paint.Align.CENTER);
        ip.setTextSize(size);
        ip.setColor(color);
        Paint.FontMetrics fm = ip.getFontMetrics();
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, ip);
    }

    private boolean isDown(Btn b) {
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.role == R_BTN && p.btn == b) return true;
        }
        return false;
    }

    private void drawBtn(Canvas c, Btn b) {
        boolean down = isDown(b);
        float cx = b.cx, cy = b.cy, r = b.r;
        int white = col(0xFFFFFFFF, 0.95f);
        ip.setStyle(Paint.Style.STROKE);
        ip.setStrokeWidth(2.2f * unit);
        ip.setColor(white);

        switch (b.id) {
            case B_FIRE: {
                drawGlass(c, cx, cy, r, menuMode ? C_GREEN : C_FIRE, down);
                if (menuMode) {
                    label(c, "OK", cx, cy, r * 0.62f, white);
                } else {
                    c.drawCircle(cx, cy, r * 0.34f, ip);
                    float a = r * 0.50f, e = r * 0.72f;
                    c.drawLine(cx - e, cy, cx - a, cy, ip);
                    c.drawLine(cx + a, cy, cx + e, cy, ip);
                    c.drawLine(cx, cy - e, cx, cy - a, ip);
                    c.drawLine(cx, cy + a, cx, cy + e, ip);
                    ip.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, r * 0.08f, ip);
                }
                break;
            }
            case B_USE: {
                drawGlass(c, cx, cy, r, menuMode ? C_RED : C_USE, down);
                label(c, menuMode ? "BACK" : "USE", cx, cy, r * (menuMode ? 0.38f : 0.46f), white);
                break;
            }
            case B_WEAP: {
                drawGlass(c, cx, cy, r, C_WEAP, down || popup);
                ip.setStyle(Paint.Style.FILL);
                rect.set(cx - r * 0.42f, cy - r * 0.24f, cx + r * 0.40f, cy + r * 0.02f);
                c.drawRoundRect(rect, r * 0.06f, r * 0.06f, ip);
                rect.set(cx - r * 0.26f, cy, cx - r * 0.02f, cy + r * 0.40f);
                c.drawRoundRect(rect, r * 0.06f, r * 0.06f, ip);
                break;
            }
            case B_MENU: {
                drawGlass(c, cx, cy, r, menuMode ? C_GREEN : C_GLASS, down);
                if (menuMode && resumeShown()) { // ▶ = "закрыть меню и вернуться в игру"
                    ip.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx - r * 0.22f, cy - r * 0.36f);
                    path.lineTo(cx - r * 0.22f, cy + r * 0.36f);
                    path.lineTo(cx + r * 0.40f, cy);
                    path.close();
                    c.drawPath(path, ip);
                } else {
                    float w = r * 0.42f, g = r * 0.30f;
                    c.drawLine(cx - w, cy - g, cx + w, cy - g, ip);
                    c.drawLine(cx - w, cy, cx + w, cy, ip);
                    c.drawLine(cx - w, cy + g, cx + w, cy + g, ip);
                }
                break;
            }
            case B_MAP: {
                drawGlass(c, cx, cy, r, C_GLASS, down);
                label(c, "MAP", cx, cy, r * 0.52f, white);
                break;
            }
            case B_GEAR: {
                drawGlass(c, cx, cy, r, C_GLASS, down);
                ip.setStrokeWidth(2f * unit);
                c.drawCircle(cx, cy, r * 0.26f, ip);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(cx + ca * r * 0.40f, cy + sa * r * 0.40f,
                            cx + ca * r * 0.56f, cy + sa * r * 0.56f, ip);
                }
                break;
            }
            case B_MORE: {
                drawGlass(c, cx, cy, r, C_GLASS, down || more);
                ip.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * r * 0.36f, cy, r * 0.11f, ip);
                break;
            }
            case B_Y:
                drawGlass(c, cx, cy, r, C_GREEN, down);
                label(c, "Y", cx, cy, r * 0.8f, white);
                break;
            case B_N:
                drawGlass(c, cx, cy, r, C_RED, down);
                label(c, "N", cx, cy, r * 0.8f, white);
                break;
            case B_QS:
                drawGlass(c, cx, cy, r, C_GLASS, down);
                label(c, "QS", cx, cy, r * 0.62f, white);
                break;
            case B_QL:
                drawGlass(c, cx, cy, r, C_GLASS, down);
                label(c, "QL", cx, cy, r * 0.62f, white);
                break;
            case B_KBD: {
                drawGlass(c, cx, cy, r, C_GLASS, down || kbdOn);
                // значок клавиатуры: рамка, два ряда клавиш и пробел
                ip.setStyle(Paint.Style.STROKE);
                ip.setStrokeWidth(1.8f * unit);
                float kw = r * 0.58f, kh = r * 0.36f;
                rect.set(cx - kw, cy - kh, cx + kw, cy + kh);
                c.drawRoundRect(rect, r * 0.10f, r * 0.10f, ip);
                ip.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 4; i++) {
                    float kx = cx + (i - 1.5f) * r * 0.26f;
                    c.drawCircle(kx, cy - kh * 0.45f, r * 0.055f, ip);
                    c.drawCircle(kx, cy + kh * 0.02f, r * 0.055f, ip);
                }
                rect.set(cx - kw * 0.46f, cy + kh * 0.40f, cx + kw * 0.46f, cy + kh * 0.62f);
                c.drawRoundRect(rect, r * 0.04f, r * 0.04f, ip);
                break;
            }
        }
        ip.setStyle(Paint.Style.FILL);
    }

    private void drawPopup(Canvas c) {
        float rP = 19 * unit;
        float cy = popupCy();
        // подложка
        float x0 = Math.min(popupCx(0), popupCx(6)) - rP - 6 * unit;
        float x1 = Math.max(popupCx(0), popupCx(6)) + rP + 6 * unit;
        rect.set(x0, cy - rP - 5 * unit, x1, cy + rP + 5 * unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(col(0xFF000000, 0.40f));
        c.drawRoundRect(rect, rP + 5 * unit, rP + 5 * unit, paint);
        for (int i = 0; i < 7; i++) {
            float cx = popupCx(i);
            drawGlass(c, cx, cy, rP, C_WEAP, false);
            label(c, String.valueOf(i + 1), cx, cy, rP * 0.95f, col(0xFFFFFFFF, 0.95f));
        }
    }

    private void chevron(Canvas c, float cx, float cy, float ang, float dist, float sz, int color) {
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
        float px = cx + ca * dist, py = cy + sa * dist;
        path.reset();
        path.moveTo(px + ca * sz, py + sa * sz);
        path.lineTo(px - ca * sz - sa * sz * 1.1f, py - sa * sz + ca * sz * 1.1f);
        path.lineTo(px - ca * sz + sa * sz * 1.1f, py - sa * sz - ca * sz * 1.1f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private void drawStick(Canvas c) {
        float R = stickR;
        float cx = stickActive ? stickCx : defStickX;
        float cy = stickActive ? stickCy : defStickY;
        k = stickActive ? 1f : 0.55f;

        // основа
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, R,
                new int[]{col(0xFF1B222C, 0.50f), col(C_DARK, 0.42f)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * unit);
        paint.setColor(col(0xFFFFFFFF, 0.35f));
        c.drawCircle(cx, cy, R, paint);
        paint.setStrokeWidth(1f * unit);
        paint.setColor(col(0xFFFFFFFF, 0.10f));
        c.drawCircle(cx, cy, R * 0.34f, paint);

        // кольцо бега
        if (runOn) {
            paint.setStrokeWidth(3f * unit);
            paint.setColor(col(C_RUN, 0.95f));
            c.drawCircle(cx, cy, R, paint);
        }

        // стрелки
        float dist = R * 0.78f, sz = R * 0.09f;
        int dim = col(0xFFFFFFFF, 0.28f);
        int lit = col(menuMode ? C_GREEN : C_STICK, 0.95f);
        chevron(c, cx, cy, (float) (-Math.PI / 2), dist, sz, vDir < 0 ? lit : dim);
        chevron(c, cx, cy, (float) (Math.PI / 2), dist, sz, vDir > 0 ? lit : dim);
        chevron(c, cx, cy, (float) Math.PI, dist, sz, hDir < 0 ? lit : dim);
        chevron(c, cx, cy, 0f, dist, sz, hDir > 0 ? lit : dim);

        // ручка
        drawGlass(c, cx + knobDx, cy + knobDy, R * 0.42f, menuMode ? C_GREEN : C_STICK, stickActive);
        k = 1f;
    }

    // =====================================================================
    //  Ввод в SDL
    // =====================================================================

    private void nativeKey(int key, boolean down) {
        try {
            if (down) SDLActivity.onNativeKeyDown(key);
            else SDLActivity.onNativeKeyUp(key);
        } catch (Throwable ignored) {
        }
    }

    private void press(int key) {
        if (key == 0) return;
        int n = held.get(key, 0);
        held.put(key, n + 1);
        if (n == 0) nativeKey(key, true);
    }

    private void release(int key) {
        if (key == 0) return;
        int n = held.get(key, 0);
        if (n <= 0) return;
        if (n == 1) {
            held.delete(key);
            nativeKey(key, false);
        } else {
            held.put(key, n - 1);
        }
    }

    /**
     * Короткое нажатие. Клавиша держится ~90 мс (> одного тика Doom, 28 мс),
     * иначе игра, опрашивающая gamekeydown[] раз в тик, может не увидеть нажатие.
     */
    private void tap(final int key) {
        press(key);
        postDelayed(() -> release(key), 90);
    }

    private void sendLook(float dxPx) {
        remX += (dxPx / density) * sens * 1.4f;
        int out = (int) remX; // к нулю
        if (out != 0) {
            remX -= out;
            try {
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, out, 0, true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void buzz() {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Включить автопереключение режима (если SDL умеет relative mouse). */
    public void setAutoMode(boolean auto) {
        autoMode = auto;
        invalidate();
    }

    /**
     * Сигнал захвата мыши больше НЕ используется: Crispy Doom в полноэкранном режиме
     * захватывает мышь ВСЕГДА (в том числе в меню), поэтому сигнал не отличает меню от игры.
     */
    public void onGameGrabChanged(final boolean grabbed) {
        Log.i("CrispyDoom", "mouse grab = " + grabbed + " (ignored)");
    }

    // ---------- экранная клавиатура (встроенная, системная) ----------

    private boolean kbdShownNow() {
        try {
            return SDLActivity.isScreenKeyboardShown();
        } catch (Throwable t) {
            return kbdOn;
        }
    }

    /**
     * Показать / спрятать обычную клавиатуру телефона. Ввод идёт через штатный механизм SDL
     * (SDLInputConnection): буквы приходят в игру как нажатия клавиш, Backspace и Enter тоже.
     */
    private void toggleKeyboard() {
        try {
            if (kbdShownNow()) {
                SDLActivity.sendMessage(3, 0); // COMMAND_TEXTEDIT_HIDE
                kbdOn = false;
            } else {
                SDLActivity.showTextInput(0, 0, 1, 1);
                kbdOn = true;
            }
        } catch (Throwable t) {
            Log.w("CrispyDoom", "keyboard toggle failed: " + t);
        }
        invalidate();
    }

    /** Показывать ▶ вместо ☰: меню открыто поверх идущей игры. */
    private boolean resumeShown() {
        return probe.manual() || levelLive; // если движок не читается — ▶ всегда (ручной режим)
    }

    /** Режим раскладки берём прямо из состояния движка (menuactive / gamestate / usergame). */
    private void syncMode() {
        if (!probe.read()) {
            levelLive = false;
            advanceMode = false;
            return; // движок пока недоступен -> ручной режим (☰ / ▶), пробуем снова
        }
        levelLive = probe.gamestate == 0 && probe.usergame != 0 && probe.demoplayback == 0;
        // Экран итогов уровня (1) и финал (2): Doom листает их по клавишам игры (USE/FIRE),
        // а не по Enter/Esc — поэтому OK и BACK там должны слать USE.
        advanceMode = (probe.gamestate == 1 || probe.gamestate == 2)
                && probe.menuactive == 0 && probe.demoplayback == 0;
        boolean inGame = levelLive && probe.menuactive == 0;
        if (SystemClock.uptimeMillis() < lockUntil) return;
        setMenuModeNow(!inGame);
        if (!inGame) invalidate();
    }

    /**
     * Чтение переменных Crispy Doom (gamestate, menuactive, usergame, demoplayback) прямо из
     * памяти libcrispy.so. Адрес = база библиотеки + смещение символа.
     *
     * Библиотека лежит несжатой внутри base.apk, поэтому в /proc/self/maps она значится как
     * "base.apk" со смещением в файле — имени "libcrispy.so" там НЕТ. Базу находим так:
     * считаем смещение данных libcrispy.so внутри APK (читаем zip-каталог) и ищем в maps
     * отображение base.apk с таким же смещением. Если библиотека распакована на диск —
     * ищем её по имени.
     *
     * Смещения взяты из .dynsym текущего libcrispy.so (arm64-v8a); при пересборке библиотеки
     * их нужно обновить (nm -D libcrispy.so). Любая ошибка -> ручной режим, без падения.
     */
    private static final class EngineProbe {
        // Запасные смещения (старая сборка). Реальные читаются из .dynsym самой библиотеки.
        long offGamestate = 0x20a9a8L;
        long offMenuactive = 0x20be6cL;
        long offUsergame = 0x20ae90L;
        long offDemoplayback = 0x20aa60L;
        private boolean symsTried = false;
        private String libPath = null;
        private long libFileOff = 0;
        static final String LIB = "libcrispy.so";
        static final String APK_ENTRY = "lib/arm64-v8a/libcrispy.so";

        int gamestate, menuactive, usergame, demoplayback;
        private final String apkPath;
        private long base = -1;
        private final ArrayList<long[]> ranges = new ArrayList<>();
        private RandomAccessFile mem;
        private Object unsafe;
        private java.lang.reflect.Method unsafeGetInt;
        private int failStreak = 0;
        private boolean everOk = false;
        private long nextTry = 0;
        private int lastSig = -1;
        private long apkDataOffset = -2;

        EngineProbe(String apkPath) {
            this.apkPath = apkPath;
        }

        /** Движок не читается уже несколько секунд -> включаем ручной режим. */
        boolean manual() {
            return failStreak >= 40;
        }

        boolean read() {
            long now = SystemClock.uptimeMillis();
            if (failStreak > 0 && now < nextTry) return false;
            try {
                if (base < 0) findBase();
                int gs = rd(offGamestate), ma = rd(offMenuactive);
                int ug = rd(offUsergame), dp = rd(offDemoplayback);
                if (gs < 0 || gs > 3 || ma < 0 || ma > 1 || ug < 0 || ug > 1 || dp < 0 || dp > 1) {
                    throw new IllegalStateException("bad values gs=" + gs + " ma=" + ma
                            + " ug=" + ug + " dp=" + dp);
                }
                gamestate = gs;
                menuactive = ma;
                usergame = ug;
                demoplayback = dp;
                failStreak = 0;
                everOk = true;
                int sig = gs | ma << 4 | ug << 5 | dp << 6;
                if (sig != lastSig) {
                    lastSig = sig;
                    Log.i("CrispyDoom", "engine: gamestate=" + gs + " menuactive=" + ma
                            + " usergame=" + ug + " demoplayback=" + dp);
                }
                return true;
            } catch (Throwable t) {
                if (failStreak == 0 || failStreak % 20 == 0) {
                    Log.w("CrispyDoom", "engine probe: " + t);
                }
                failStreak++;
                base = -1; // пересчитать базу в следующий раз
                nextTry = now + 200;
                return false;
            }
        }

        private int rd(long off) throws Exception {
            long addr = base + off;
            boolean inside = false;
            for (long[] r : ranges) {
                if (addr >= r[0] && addr + 4 <= r[1]) {
                    inside = true;
                    break;
                }
            }
            if (!inside) throw new IllegalStateException("address not mapped");
            try {
                if (mem == null) mem = new RandomAccessFile("/proc/self/mem", "r");
                byte[] b = new byte[4];
                mem.seek(addr);
                mem.readFully(b);
                return (b[0] & 0xFF) | (b[1] & 0xFF) << 8 | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
            } catch (Throwable memErr) {
                // запасной путь: sun.misc.Unsafe (адрес уже проверен по maps)
                if (unsafe == null) {
                    Class<?> uc = Class.forName("sun.misc.Unsafe");
                    java.lang.reflect.Field f = uc.getDeclaredField("theUnsafe");
                    f.setAccessible(true);
                    unsafe = f.get(null);
                    unsafeGetInt = uc.getMethod("getInt", long.class);
                }
                return (Integer) unsafeGetInt.invoke(unsafe, addr);
            }
        }

        private void findBase() throws Exception {
            if (apkDataOffset == -2) {
                try {
                    apkDataOffset = apkEntryDataOffset(apkPath, APK_ENTRY);
                    Log.i("CrispyDoom", "libcrispy data offset in apk = 0x"
                            + Long.toHexString(apkDataOffset));
                } catch (Throwable t) {
                    apkDataOffset = -1;
                    Log.w("CrispyDoom", "apk lookup failed: " + t);
                }
            }
            ranges.clear();
            long byName = Long.MAX_VALUE, byApk = Long.MAX_VALUE;
            String namePath = null;
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] f = line.trim().split("\\s+", 6);
                    if (f.length < 3) continue;
                    int dash = f[0].indexOf('-');
                    if (dash < 0) continue;
                    long a = Long.parseUnsignedLong(f[0].substring(0, dash), 16);
                    long e = Long.parseUnsignedLong(f[0].substring(dash + 1), 16);
                    if (f[1].length() > 0 && f[1].charAt(0) == 'r') ranges.add(new long[]{a, e});
                    String path = f.length > 5 ? f[5] : "";
                    if (path.contains(LIB) && a < byName) {
                        byName = a;
                        namePath = path;
                    }
                    if (apkDataOffset >= 0 && path.endsWith(".apk")) {
                        long fo = Long.parseUnsignedLong(f[2], 16);
                        if (fo == apkDataOffset && a < byApk) byApk = a;
                    }
                }
            }
            long b = byName != Long.MAX_VALUE ? byName : byApk;
            if (b == Long.MAX_VALUE) throw new IllegalStateException("libcrispy.so not mapped (yet)");
            if (base != b) {
                Log.i("CrispyDoom", "libcrispy base = 0x" + Long.toHexString(b)
                        + (byName != Long.MAX_VALUE ? " (by name)" : " (by apk offset)"));
            }
            base = b;
            if (!symsTried) {
                symsTried = true;
                if (byName != Long.MAX_VALUE && namePath != null && namePath.startsWith("/")) {
                    libPath = namePath;
                    libFileOff = 0;
                } else {
                    libPath = apkPath;
                    libFileOff = apkDataOffset;
                }
                try {
                    loadSymbols(libPath, libFileOff);
                } catch (Throwable t) {
                    Log.w("CrispyDoom", "symbol lookup failed, using built-in offsets: " + t);
                }
            }
        }

        /**
         * Читает .dynsym из ELF-файла библиотеки (внутри APK или на диске) и находит смещения
         * gamestate / menuactive / usergame / demoplayback по имени. Так проект не ломается
         * при каждой пересборке libcrispy.so.
         */
        private void loadSymbols(String path, long fileOff) throws Exception {
            if (fileOff < 0) throw new IllegalStateException("no file offset");
            try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
                byte[] eh = new byte[64];
                f.seek(fileOff);
                f.readFully(eh);
                if (eh[0] != 0x7f || eh[1] != 'E' || eh[2] != 'L' || eh[3] != 'F' || eh[4] != 2) {
                    throw new IllegalStateException("not ELF64");
                }
                long shoff = le64(eh, 0x28);
                int shentsize = le16(eh, 0x3A), shnum = le16(eh, 0x3C);
                if (shoff == 0 || shnum == 0 || shentsize < 64) {
                    throw new IllegalStateException("no section headers");
                }
                byte[] sh = new byte[shnum * shentsize];
                f.seek(fileOff + shoff);
                f.readFully(sh);
                for (int i = 0; i < shnum; i++) {
                    int o = i * shentsize;
                    if (le32(sh, o + 4) != 11) continue; // SHT_DYNSYM
                    long symOff = le64(sh, o + 24), symSize = le64(sh, o + 32);
                    int link = (int) le32(sh, o + 40);
                    int lo = link * shentsize;
                    long strOff = le64(sh, lo + 24), strSize = le64(sh, lo + 32);
                    byte[] str = new byte[(int) strSize];
                    f.seek(fileOff + strOff);
                    f.readFully(str);
                    byte[] sym = new byte[(int) symSize];
                    f.seek(fileOff + symOff);
                    f.readFully(sym);
                    long gs = -1, ma = -1, ug = -1, dp = -1;
                    for (int k = 0; k + 24 <= sym.length; k += 24) {
                        int nameIdx = (int) le32(sym, k);
                        if (nameIdx <= 0 || nameIdx >= str.length) continue;
                        int e = nameIdx;
                        while (e < str.length && str[e] != 0) e++;
                        String n = new String(str, nameIdx, e - nameIdx, "UTF-8");
                        long val = le64(sym, k + 8);
                        if (val == 0) continue;
                        switch (n) {
                            case "gamestate": gs = val; break;
                            case "menuactive": ma = val; break;
                            case "usergame": ug = val; break;
                            case "demoplayback": dp = val; break;
                            default: break;
                        }
                    }
                    if (gs < 0 || ma < 0 || ug < 0 || dp < 0) {
                        throw new IllegalStateException("symbols not exported: gs=" + gs
                                + " ma=" + ma + " ug=" + ug + " dp=" + dp);
                    }
                    offGamestate = gs;
                    offMenuactive = ma;
                    offUsergame = ug;
                    offDemoplayback = dp;
                    Log.i("CrispyDoom", "symbols: gamestate=0x" + Long.toHexString(gs)
                            + " menuactive=0x" + Long.toHexString(ma)
                            + " usergame=0x" + Long.toHexString(ug)
                            + " demoplayback=0x" + Long.toHexString(dp));
                    return;
                }
                throw new IllegalStateException("no .dynsym");
            }
        }

        private static long le64(byte[] b, int i) {
            return le32(b, i) | (le32(b, i + 4) << 32);
        }

        private static int le16(byte[] b, int i) {
            return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
        }

        private static long le32(byte[] b, int i) {
            return ((b[i] & 0xFFL) | (b[i + 1] & 0xFFL) << 8 | (b[i + 2] & 0xFFL) << 16
                    | (b[i + 3] & 0xFFL) << 24);
        }

        /** Смещение данных (несжатой) записи zip внутри файла APK. */
        private static long apkEntryDataOffset(String apk, String entry) throws Exception {
            try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
                long len = f.length();
                int max = (int) Math.min(len, 65557);
                byte[] tail = new byte[max];
                f.seek(len - max);
                f.readFully(tail);
                int eocd = -1;
                for (int i = max - 22; i >= 0; i--) {
                    if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 5 && tail[i + 3] == 6) {
                        eocd = i;
                        break;
                    }
                }
                if (eocd < 0) throw new IllegalStateException("no EOCD");
                int cdSize = (int) le32(tail, eocd + 12);
                long cdOff = le32(tail, eocd + 16);
                byte[] cd = new byte[cdSize];
                f.seek(cdOff);
                f.readFully(cd);
                int p = 0;
                while (p + 46 <= cdSize && le32(cd, p) == 0x02014b50L) {
                    int method = le16(cd, p + 10);
                    int nlen = le16(cd, p + 28), elen = le16(cd, p + 30), clen = le16(cd, p + 32);
                    long lho = le32(cd, p + 42);
                    String name = new String(cd, p + 46, nlen, "UTF-8");
                    if (name.equals(entry)) {
                        if (method != 0) throw new IllegalStateException("entry is compressed");
                        byte[] lh = new byte[30];
                        f.seek(lho);
                        f.readFully(lh);
                        return lho + 30 + le16(lh, 26) + le16(lh, 28);
                    }
                    p += 46 + nlen + elen + clen;
                }
                throw new IllegalStateException("entry not found: " + entry);
            }
        }
    }

    private void setMenuModeNow(boolean m) {
        if (menuMode == m) return;
        menuMode = m;
        if (!m) wasInGame = true;
        applyStickKeys(0, 0, false);
        closePopup();
        closeMore();
        invalidate();
    }

    /** Отпустить всё (пауза, диалог, потеря фокуса). */
    public void releaseAll() {
        ptrs.clear();
        for (int i = 0; i < held.size(); i++) nativeKey(held.keyAt(i), false);
        held.clear();
        stickActive = false;
        stickId = -1;
        knobDx = knobDy = 0;
        heldH = heldV = heldRun = 0;
        hDir = vDir = 0;
        runOn = false;
        popup = false;
        more = false;
        removeCallbacks(hidePopup);
        removeCallbacks(hideMore);
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTime = SystemClock.uptimeMillis();
        menuMode = true;
        removeCallbacks(poll);
        postDelayed(poll, 100);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(poll);
        releaseAll();
        super.onDetachedFromWindow();
    }

    // =====================================================================
    //  Тачи
    // =====================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    pointerMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        invalidate();
        return true;
    }

    private Btn hitButton(float x, float y) {
        Btn best = null;
        float bestD = Float.MAX_VALUE;
        for (Btn b : btns) {
            if (!isVisible(b)) continue;
            float d = (float) Math.hypot(x - b.cx, y - b.cy);
            if (d <= b.r * 1.2f && d < bestD) {
                best = b;
                bestD = d;
            }
        }
        return best;
    }

    private int popupHit(float x, float y) {
        float rP = 19 * unit;
        float cy = popupCy();
        for (int i = 0; i < 7; i++) {
            if (Math.hypot(x - popupCx(i), y - cy) <= rP * 1.15f) return i + 1;
        }
        return 0;
    }

    private void closeMore() {
        more = false;
        removeCallbacks(hideMore);
    }

    private void closePopup() {
        popup = false;
        removeCallbacks(hidePopup);
    }

    private void pointerDown(int id, float x, float y) {
        touched = true;
        Ptr p = new Ptr();
        p.lastX = x;
        p.anchorX = x;
        ptrs.put(id, p);

        if (popup) {
            int n = popupHit(x, y);
            if (n > 0) {
                buzz();
                tap(KeyEvent.KEYCODE_0 + n); // KEYCODE_1..7
                closePopup();
                return;
            }
            Btn hb = hitButton(x, y);
            closePopup();
            if (hb != null && hb.id == B_WEAP) return; // повторное нажатие закрывает
        }

        Btn b = hitButton(x, y);
        if (b != null) {
            p.role = R_BTN;
            p.btn = b;
            buzz();
            buttonDown(b, p);
            return;
        }

        if (inStickZone(x) && stickId == -1) {
            float m = 8 * unit;
            stickCx = Math.max(stickR + m, Math.min(W - stickR - m, x));
            stickCy = Math.max(stickR + m, Math.min(H - stickR - m, y));
            stickActive = true;
            stickId = id;
            knobDx = knobDy = 0;
            p.role = R_STICK;
            return;
        }

        if (!menuMode) p.role = R_LOOK;
    }

    private void buttonDown(Btn b, Ptr p) {
        switch (b.id) {
            case B_FIRE:
                if (menuMode && advanceMode) {
                    tap(KeyEvent.KEYCODE_SPACE); // дальше по экрану итогов / финалу
                    break;
                }
                p.key = menuMode ? KeyEvent.KEYCODE_ENTER : KeyEvent.KEYCODE_CTRL_LEFT;
                press(p.key);
                break;
            case B_USE:
                if (menuMode && advanceMode) {
                    tap(KeyEvent.KEYCODE_SPACE); // BACK тоже листает дальше
                    break;
                }
                p.key = menuMode ? KeyEvent.KEYCODE_ESCAPE : KeyEvent.KEYCODE_SPACE;
                press(p.key);
                break;
            case B_WEAP:
                popup = true;
                removeCallbacks(hidePopup);
                postDelayed(hidePopup, 4000);
                break;
            case B_MENU:
                lockUntil = SystemClock.uptimeMillis() + 500;
                if (!menuMode) {
                    // ☰ в игре: раскладка меню + Esc (открывается меню Doom)
                    setMenuModeNow(true);
                    tap(KeyEvent.KEYCODE_ESCAPE);
                } else if (resumeShown()) {
                    // ▶: закрыть меню (Esc) и вернуть игровые кнопки
                    tap(KeyEvent.KEYCODE_ESCAPE);
                    setMenuModeNow(false);
                } else {
                    // титул / главное меню без игры: Esc открывает/закрывает меню Doom
                    tap(KeyEvent.KEYCODE_ESCAPE);
                }
                break;
            case B_MORE:
                more = !more;
                removeCallbacks(hideMore);
                if (more) postDelayed(hideMore, 5000);
                break;
            case B_MAP:
                tap(KeyEvent.KEYCODE_TAB);
                break;
            case B_GEAR:
                post(this::showSettings);
                break;
            case B_Y:
                tap(KeyEvent.KEYCODE_Y);
                closeMore();
                break;
            case B_N:
                tap(KeyEvent.KEYCODE_N);
                closeMore();
                break;
            case B_QS:
                tap(KeyEvent.KEYCODE_F6);
                postDelayed(() -> tap(KeyEvent.KEYCODE_Y), 150); // подтверждение перезаписи
                closeMore();
                break;
            case B_QL:
                tap(KeyEvent.KEYCODE_F9);
                closeMore();
                break;
            case B_KBD:
                toggleKeyboard();
                closeMore();
                break;
        }
    }

    private void pointerMove(int id, float x, float y) {
        Ptr p = ptrs.get(id);
        if (p == null) return;
        switch (p.role) {
            case R_STICK:
                updateStick(x, y);
                break;
            case R_LOOK:
                look(p, x);
                break;
            case R_BTN:
                if (p.btn.id == B_FIRE && !menuMode) look(p, x);
                break;
        }
    }

    private void pointerUp(int id) {
        Ptr p = ptrs.get(id);
        if (p == null) return;
        ptrs.remove(id);
        switch (p.role) {
            case R_STICK:
                stickActive = false;
                stickId = -1;
                knobDx = knobDy = 0;
                applyStickKeys(0, 0, false);
                break;
            case R_BTN:
                if (p.lp != null) {
                    removeCallbacks(p.lp);
                    if (!p.longFired) tap(KeyEvent.KEYCODE_ESCAPE);
                }
                release(p.key);
                release(p.key2);
                release(p.turnKey);
                break;
            case R_LOOK:
                release(p.turnKey);
                break;
        }
    }

    // ---------- поворот ----------

    private void look(Ptr p, float x) {
        if (keyTurn) {
            float th = 14 * density, lim = 80 * density;
            float off = x - p.anchorX;
            if (off > lim) {
                p.anchorX = x - lim;
                off = lim;
            } else if (off < -lim) {
                p.anchorX = x + lim;
                off = -lim;
            }
            int want = off > th ? KeyEvent.KEYCODE_DPAD_RIGHT
                    : off < -th ? KeyEvent.KEYCODE_DPAD_LEFT : 0;
            if (want != p.turnKey) {
                release(p.turnKey);
                press(want);
                p.turnKey = want;
            }
        } else {
            sendLook(x - p.lastX);
        }
        p.lastX = x;
    }

    // ---------- джойстик ----------

    private void updateStick(float x, float y) {
        float dx = x - stickCx, dy = y - stickCy;
        float len = (float) Math.hypot(dx, dy);
        if (len > stickR) {
            dx *= stickR / len;
            dy *= stickR / len;
            len = stickR;
        }
        knobDx = dx;
        knobDy = dy;

        float on = 0.34f * stickR, off = 0.24f * stickR;
        int nh = hDir, nv = vDir;
        if (dx < -on) nh = -1;
        else if (dx > on) nh = 1;
        else if (Math.abs(dx) < off || nh * dx < 0) nh = 0;
        if (dy < -on) nv = -1;
        else if (dy > on) nv = 1;
        else if (Math.abs(dy) < off || nv * dy < 0) nv = 0;

        boolean any = nh != 0 || nv != 0;
        boolean run = !menuMode && any
                && (alwaysRun || len > (runOn ? 0.80f : 0.92f) * stickR);
        applyStickKeys(nh, nv, run);
    }

    private void applyStickKeys(int nh, int nv, boolean run) {
        int hk = nh < 0 ? (menuMode ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_COMMA)
                : nh > 0 ? (menuMode ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_PERIOD) : 0;
        int vk = nv < 0 ? KeyEvent.KEYCODE_DPAD_UP
                : nv > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : 0;
        int rk = run ? KeyEvent.KEYCODE_SHIFT_LEFT : 0;

        if (hk != heldH) {
            release(heldH);
            press(hk);
            heldH = hk;
        }
        if (vk != heldV) {
            release(heldV);
            press(vk);
            heldV = vk;
        }
        if (rk != heldRun) {
            release(heldRun);
            press(rk);
            heldRun = rk;
        }
        hDir = nh;
        vDir = nv;
        runOn = run;
    }
}
