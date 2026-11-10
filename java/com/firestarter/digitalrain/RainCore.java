package com.firestarter.digitalrain;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Shader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;

/**
 * Digital Rain chat background. One view per chat screen, placed right above the wallpaper.
 * Runs on vsync (Choreographer) only while the view is attached, shown and its window visible;
 * draws on every vsync from a clock driven by the vsync timestamps, heads glide by sub pixel steps,
 * and it skips vsyncs only while the app is missing frames; nothing is allocated per frame; glyphs come from two
 * ALPHA_8 atlases (sharp + glow) built once, tinted by the paint color.
 */
public final class RainCore {
    static volatile int color = 0xFF00FF41;
    static volatile boolean rotate = false;
    static volatile float rotSpeed = 20f;   // hue degrees per second
    static volatile float rainSpeed = 1f;   // multiplier
    static volatile int count = 80;          // falling streams
    static volatile int cfgVersion = 0;
    static final ArrayList<WeakReference<RainView>> views = new ArrayList<>();

    public static void setConfig(int c, boolean rot, float rs, float speed, int n) {
        color = c | 0xFF000000; rotate = rot; rotSpeed = rs; rainSpeed = speed;
        count = Math.max(5, Math.min(400, n));
        cfgVersion++;
        for (int i = views.size() - 1; i >= 0; i--) {
            final RainView v = views.get(i).get();
            if (v == null) views.remove(i); else v.post(new Runnable() { public void run() { v.configChanged(); } });
        }
    }

    static boolean offMain(final ViewGroup g, final boolean header) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        g.post(new Runnable() { public void run() { if (header) attachHeader(g); else attach(g); } });
        return true;
    }

    public static void attach(ViewGroup content) {
        if (content == null || offMain(content, false)) return;
        for (int i = 0; i < content.getChildCount(); i++) if (content.getChildAt(i) instanceof RainView) return;
        int idx = 0;
        try {
            Class<?> c = content.getClass();
            Field f = null;
            while (c != null && f == null) {
                try { f = c.getDeclaredField("backgroundView"); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            if (f != null) {
                f.setAccessible(true);
                Object bg = f.get(content);
                if (bg instanceof View) { int j = content.indexOfChild((View) bg); if (j >= 0) idx = j + 1; }
            }
        } catch (Throwable ignore) {}
        RainView v = new RainView(content.getContext());
        content.addView(v, idx, new FrameLayout.LayoutParams(-1, -1));
        views.add(new WeakReference<>(v));
    }

    /** Rain behind a header (an ActionBar): first child, so the title and icons draw on top; dimmer. */
    public static void attachHeader(ViewGroup bar) {
        if (bar == null || offMain(bar, true)) return;
        for (int i = 0; i < bar.getChildCount(); i++) if (bar.getChildAt(i) instanceof RainView) return;
        RainView v = new RainView(bar.getContext());
        v.dim = 0.55f;
        bar.addView(v, 0, new FrameLayout.LayoutParams(-1, -1));
        views.add(new WeakReference<>(v));
    }

    public static boolean hasRain(ViewGroup g) {
        if (g == null) return false;
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof RainView) return true;
        return false;
    }

    public static void detachAll() {
        for (WeakReference<RainView> r : views) {
            RainView v = r.get();
            if (v != null) { v.stop(); if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v); }
        }
        views.clear();
    }

    /** Frames drawn, total draw time (ns) and running views, for measuring. */
    public static long frames = 0, drawNs = 0;
    public static String stats() {
        int run = 0;
        for (WeakReference<RainView> r : views) { RainView v = r.get(); if (v != null && v.running) run++; }
        return frames + " " + drawNs + " " + run;
    }

    // half width katakana, digits and a few symbols, as in the film
    static final String GLYPHS = "ｦｱｳｴｵｶｷｹｺｻｼｽｾｿﾀﾂﾃﾅﾆﾇﾈﾊﾋﾎﾏﾐﾑﾒﾓﾔﾕﾗﾘﾜ012345789Z:.\"=*+-<>¦|ç";

    static final class RainView extends View implements Choreographer.FrameCallback {
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Random rnd = new Random();
        final float[] hsv = new float[3];
        Bitmap atlas;                                       // row 0 sharp glyphs, row 1 glow
        float[] verts, texs; int[] cols4; short[] idx; int maxQuads, nq;
        int cw, ch, cols, rows, nGlyph, glowPad;
        byte[] glyph; float[] lit;                          // per cell: glyph, rain clock when lit (s)
        float[] headY, speed; int[] col, len, lastRow;      // per stream
        byte[] headGlyph;                                   // per stream: the glyph the head carries
        boolean running, posted;
        int seenVersion = -1;
        float dim = 1f;
        // 1.2.0 timing: everything moves on a rain clock advanced by the vsync timestamps, so motion is
        // continuous at any refresh rate and pauses (instead of jumping) while the view is hidden
        float clock = 0f, flipAcc = 0f;
        long drawNanos, lastNanos;                          // vsync time for the next draw, last drawn
        // frame budget: draw every vsync; skip every other one only while frames are being missed
        long prevVsync, periodNs = 16_666_667L, minInterval = Long.MAX_VALUE;
        int stride = 1, minStride = 1, frameNo, winFrames, winLate;
        long drawEma;

        RainView(Context c) { super(c); setWillNotDraw(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void configChanged() { if (seenVersion != cfgVersion) { setupStreams(); invalidate(); } }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { build(w, h); }

        void build(int w, int h) {
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            float ts = 15 * d;
            cw = Math.round(ts * 0.86f); ch = Math.round(ts * 1.12f);
            if (atlas == null) buildAtlas(ts);
            cols = Math.max(1, w / cw + 1); rows = Math.max(1, h / ch + 2);
            glyph = new byte[cols * rows]; lit = new float[cols * rows];
            for (int i = 0; i < glyph.length; i++) { glyph[i] = (byte) rnd.nextInt(nGlyph); lit[i] = -1000f; }
            setupStreams();
        }

        void buildAtlas(float ts) {
            nGlyph = GLYPHS.length();
            glowPad = Math.round(ts * 0.35f);
            int gw = cw + glowPad * 2, gh = ch + glowPad * 2;
            atlas = Bitmap.createBitmap(gw * nGlyph, gh * 2, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(ts); p.setTypeface(Typeface.MONOSPACE); p.setTextAlign(Paint.Align.CENTER); p.setColor(Color.WHITE);
            Paint pg = new Paint(p); pg.setMaskFilter(new BlurMaskFilter(ts * 0.3f, BlurMaskFilter.Blur.NORMAL));
            Canvas ca = new Canvas(atlas);
            float base = gh / 2f - (p.descent() + p.ascent()) / 2f;
            for (int i = 0; i < nGlyph; i++) {
                String s = GLYPHS.substring(i, i + 1);
                float cx = i * gw + gw / 2f;
                boolean mirror = s.charAt(0) >= 0xFF66 && s.charAt(0) <= 0xFF9F;   // katakana mirrored, as in the film
                for (int k = 0; k < 2; k++) {
                    ca.save();
                    if (mirror) ca.scale(-1, 1, cx, 0);
                    ca.drawText(s, cx, base + k * gh, k == 0 ? p : pg);
                    ca.restore();
                }
            }
            paint.setShader(new BitmapShader(atlas, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }

        void ensureBuffers() {
            int want = Math.min(16000, headY.length * (rows / 2 + 9) + headY.length * 2);
            if (verts != null && maxQuads >= want) return;
            maxQuads = want;
            verts = new float[want * 8]; texs = new float[want * 8]; cols4 = new int[want * 4]; idx = new short[want * 6];
            for (int q = 0; q < want; q++) {
                int b = q * 4, o = q * 6;
                idx[o] = (short) b; idx[o + 1] = (short) (b + 1); idx[o + 2] = (short) (b + 2);
                idx[o + 3] = (short) b; idx[o + 4] = (short) (b + 2); idx[o + 5] = (short) (b + 3);
            }
        }

        /** One glyph quad; y is the cell top in pixels (fractional for gliding heads). */
        void quad(int g, int x, float y, int row, int argb, int gw, int gh) {
            if (nq >= maxQuads) return;
            int v = nq * 8, cI = nq * 4;
            float l = x * cw - glowPad, t = y - glowPad, rr = l + gw, bb = t + gh;
            float sl = g * gw, st = row * gh, sr = sl + gw, sb = st + gh;
            verts[v] = l; verts[v + 1] = t; verts[v + 2] = rr; verts[v + 3] = t;
            verts[v + 4] = rr; verts[v + 5] = bb; verts[v + 6] = l; verts[v + 7] = bb;
            texs[v] = sl; texs[v + 1] = st; texs[v + 2] = sr; texs[v + 3] = st;
            texs[v + 4] = sr; texs[v + 5] = sb; texs[v + 6] = sl; texs[v + 7] = sb;
            cols4[cI] = argb; cols4[cI + 1] = argb; cols4[cI + 2] = argb; cols4[cI + 3] = argb;
            nq++;
        }

        void setupStreams() {
            seenVersion = cfgVersion;
            if (cols == 0) return;
            int n = count;
            // a header holds a few rows: scale the streams to its height (a phone screen is about 60 rows)
            if (dim < 1f) n = Math.max(12, count * rows / 30);
            headY = new float[n]; speed = new float[n]; col = new int[n]; len = new int[n]; lastRow = new int[n];
            headGlyph = new byte[n];
            for (int i = 0; i < n; i++) spawn(i, true);
            ensureBuffers();
        }

        void spawn(int i, boolean anywhere) {
            col[i] = rnd.nextInt(cols);
            speed[i] = 6f + rnd.nextFloat() * 16f;                // cells per second before the multiplier
            len[i] = 6 + rnd.nextInt(Math.max(4, rows / 2));
            headY[i] = anywhere ? rnd.nextFloat() * (rows + len[i]) - len[i] : -rnd.nextInt(rows / 2 + 1) - 1;
            lastRow[i] = (int) Math.floor(headY[i]);
            headGlyph[i] = (byte) rnd.nextInt(nGlyph);
        }

        // ---- lifecycle: run only while it can be seen ----
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); update(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stop(); }
        @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); update(); }
        @Override public void onVisibilityAggregated(boolean vis) { super.onVisibilityAggregated(vis); update(); }

        void update() {
            boolean want = isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
            if (want && !running) {
                running = true; lastNanos = 0; drawNanos = 0; prevVsync = 0;
                try {
                    android.view.Display dp = getDisplay();
                    float hz = dp != null ? dp.getRefreshRate() : 60f;
                    if (hz >= 20f && hz <= 250f) periodNs = (long) (1e9f / hz);
                } catch (Throwable ignore) {}
                setStrideFloor();
                stride = minStride; frameNo = 0; winFrames = 0; winLate = 0; minInterval = Long.MAX_VALUE;
                if (!posted) { posted = true; Choreographer.getInstance().postFrameCallback(this); }
            } else if (!want) stop();
        }

        /** The header moves at most 60 times a second (the chat list's glass effects redraw with it); the chat rain every vsync. */
        void setStrideFloor() { minStride = dim < 1f ? Math.max(1, Math.round(16_666_667f / periodNs)) : 1; }

        void stop() { running = false; if (posted) { Choreographer.getInstance().removeFrameCallback(this); posted = false; } }

        @Override public void doFrame(long nanos) {
            posted = false;
            if (!running) return;
            if (getWindowVisibility() != VISIBLE || !isShown()) { running = false; return; }
            // frame budget: count vsyncs that came late (missed) over about a second
            if (prevVsync != 0) {
                long iv = nanos - prevVsync;
                if (iv < minInterval) minInterval = iv;
                if (iv > periodNs + (periodNs >> 1)) winLate++;
            }
            prevVsync = nanos;
            if (++winFrames >= 64) {
                if (minInterval > 4_000_000L && minInterval < 34_000_000L) { periodNs = minInterval; setStrideFloor(); }
                boolean heavy = winLate > 6 || drawEma > 2_500_000L;
                if (heavy && stride < 4) stride++;
                else if (!heavy && winLate <= 1 && stride > minStride) stride--;
                if (stride < minStride) stride = minStride;
                winFrames = 0; winLate = 0; minInterval = Long.MAX_VALUE;
            }
            if (++frameNo >= stride) { frameNo = 0; drawNanos = nanos; invalidate(); }
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        static float smooth(float x) { return x <= 0f ? 0f : x >= 1f ? 1f : x * x * (3f - 2f * x); }

        @Override protected void onDraw(Canvas c) {
            long st = System.nanoTime();
            c.drawColor(Color.BLACK);
            if (glyph == null || headY == null) return;
            // time comes from the vsync that asked for this frame; a redraw without one keeps the same frame
            long fn = drawNanos;
            float dt = 0f;
            if (fn != 0 && lastNanos != 0 && fn > lastNanos) {
                dt = (fn - lastNanos) / 1e9f;
                if (dt > 0.25f) dt = 0f;                         // a long stall pauses the rain, never jumps it
            }
            if (fn != 0) lastNanos = fn;
            clock += dt;
            float now = clock;
            int base = color;
            if (rotate) {
                Color.colorToHSV(color, hsv);
                hsv[0] = (hsv[0] + now * rotSpeed) % 360f;
                base = Color.HSVToColor(hsv);
            }
            int br = Color.red(base), bgc = Color.green(base), bb = Color.blue(base);
            float mult = rainSpeed;
            int n = headY.length;
            // advance streams; each new row a head enters is lit with the glyph the head carried there
            for (int i = 0; i < n; i++) {
                headY[i] += speed[i] * mult * dt;
                int r = (int) Math.floor(headY[i]);
                while (lastRow[i] < r) {
                    lastRow[i]++;
                    int rr = lastRow[i];
                    byte g = (byte) rnd.nextInt(nGlyph);
                    headGlyph[i] = g;
                    if (rr >= 0 && rr < rows) { int k = rr * cols + col[i]; lit[k] = now; glyph[k] = g; }
                }
                if (headY[i] - len[i] > rows) spawn(i, false);
            }
            // a few glyphs inside the trails change at random, at the same rate whatever the refresh rate
            flipAcc += cols * rows * 0.004f * 24f * dt;
            while (flipAcc >= 1f) { flipAcc -= 1f; int k = rnd.nextInt(glyph.length); glyph[k] = (byte) rnd.nextInt(nGlyph); }
            int gw = cw + glowPad * 2, gh = ch + glowPad * 2;
            nq = 0;
            // glow behind the leading glyphs, gliding with them
            int glowA = (int) (170 * dim);
            for (int i = 0; i < n; i++) {
                float hy = headY[i];
                if (hy > -1f && hy < rows) quad(headGlyph[i], col[i], hy * ch, 1, Color.argb(glowA, br, bgc, bb), gw, gh);
            }
            // trails: bright just behind the head, fading continuously into dark color and then to nothing
            for (int i = 0; i < n; i++) {
                float sp = speed[i] * mult;
                float life = len[i] / Math.max(0.5f, sp);
                int x = col[i];
                int lr = lastRow[i];
                int rTop = Math.max(0, lr - len[i]);
                int rBot = Math.min(rows - 1, lr);
                float emerge = smooth((headY[i] - lr) * 1.5f);   // the newest cell comes out from under the head
                for (int r = rTop; r <= rBot; r++) {
                    int k = r * cols + x;
                    float age = now - lit[k];
                    if (age < 0 || age > life) continue;
                    float b = 1f - age / life;
                    float lum = 0.22f + 0.78f * b * b;
                    float lift = b > 0.85f ? (b - 0.85f) * 1.1f : 0f;
                    float fa = b < 0.15f ? b / 0.15f : 1f;          // last stretch fades to zero, no pop
                    if (r == lr) fa *= emerge;
                    int a = (int) ((30 + 225 * b) * dim * fa);
                    if (a <= 0) continue;
                    int cr = (int) Math.min(255, br * lum + 255 * lift);
                    int cg = (int) Math.min(255, bgc * lum + 255 * lift);
                    int cb = (int) Math.min(255, bb * lum + 255 * lift);
                    quad(glyph[k], x, r * ch, 0, Color.argb(a, cr, cg, cb), gw, gh);
                }
            }
            // leading glyphs: white tinted with the base color, at their exact (sub pixel) position
            int head = Color.argb((int) (255 * dim), 225 + br / 9, 235 + bgc / 13, 225 + bb / 9);
            for (int i = 0; i < n; i++) {
                float hy = headY[i];
                if (hy > -1f && hy < rows) quad(headGlyph[i], col[i], hy * ch, 0, head, gw, gh);
            }
            // one draw call for every glyph on screen
            if (nq > 0) c.drawVertices(Canvas.VertexMode.TRIANGLES, nq * 8, verts, 0, texs, 0, cols4, 0, idx, 0, nq * 6, paint);
            long took = System.nanoTime() - st;
            drawEma += (took - drawEma) >> 3;
            frames++; drawNs += took;
        }

    }
}
