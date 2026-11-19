package dev.srwi.gboardmultiswipe.extension.typingeffects;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Hardware-accelerated {@link Drawable} attached to {@code SoftKeyboardView.getOverlay()} that
 * renders Typing Effects: continuous trampoline keyboard mesh warp, key touch glow, tapered
 * rainbow/palette swipe trails with optional neon glow, shockwave rings, glowing sparks,
 * twinkling 4-point sparkle stars, and tumbling confetti.
 */
final class TypingEffectsOverlayDrawable extends Drawable {

    private static final int MESH_COLS = 24;
    private static final int MESH_ROWS = 10;
    private static final int MESH_VERTEX_COUNT = (MESH_COLS + 1) * (MESH_ROWS + 1);

    private final WeakReference<View> hostViewRef;
    private final TypingEffectsEngine engine;
    private final Paint meshPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint trailGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailMainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private final RectF confettiRect = new RectF();
    private final RectF keyRect = new RectF();
    private final RectF keyPulseRect = new RectF();
    private final float[] meshVerts = new float[MESH_VERTEX_COUNT * 2];
    private final int[] meshColors = new int[MESH_VERTEX_COUNT];

    private Bitmap captureBitmap = null;
    private Canvas captureCanvas = null;
    private boolean isCapturing = false;

    TypingEffectsOverlayDrawable(View hostView, TypingEffectsEngine engine) {
        this.hostViewRef = new WeakReference<View>(hostView);
        this.engine = engine;

        trailGlowPaint.setStyle(Paint.Style.STROKE);
        trailGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        trailMainPaint.setStyle(Paint.Style.STROKE);
        trailMainPaint.setStrokeCap(Paint.Cap.ROUND);
        trailMainPaint.setStrokeJoin(Paint.Join.ROUND);

        trailCorePaint.setStyle(Paint.Style.STROKE);
        trailCorePaint.setStrokeCap(Paint.Cap.ROUND);
        trailCorePaint.setStrokeJoin(Paint.Join.ROUND);

        ringGlowPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStyle(Paint.Style.STROKE);

        particlePaint.setStyle(Paint.Style.FILL);
        sparkStrokePaint.setStyle(Paint.Style.STROKE);
        sparkStrokePaint.setStrokeCap(Paint.Cap.ROUND);

        keyFillPaint.setStyle(Paint.Style.FILL);
        keyHaloPaint.setStyle(Paint.Style.STROKE);
        keyBorderPaint.setStyle(Paint.Style.STROKE);
    }

    void releaseResources() {
        if (captureBitmap != null) {
            captureBitmap.recycle();
            captureBitmap = null;
        }
        captureCanvas = null;
    }

    @Override
    public void draw(Canvas canvas) {
        if (isCapturing) {
            return;
        }
        TypingEffectsConfig cfg = engine.getConfig();
        if (!cfg.enabled) {
            return;
        }
        long nowMs = SystemClock.uptimeMillis();
        float density = engine.getDensity();

        // 0. Continuous Trampoline Keyboard Mat Warp (captures the whole keyboard + key glows
        //    and warps them together as one unified elastic surface!)
        boolean drewWarpedMat = false;
        if (cfg.keyboardWarpEnabled && engine.hasActiveWarp()) {
            drewWarpedMat = drawTrampolineWarpMat(canvas, cfg, nowMs, density);
        }

        // 0b. Key Touch Glow (drawn directly when keyboard mat is not actively warped)
        if (!drewWarpedMat && cfg.keyHighlightEnabled) {
            drawAllKeyHighlights(canvas, cfg, nowMs, density);
        }

        // 1. Rainbow / Palette Swipe Trails
        if (cfg.rainbowTrailEnabled) {
            List<TypingEffectsEngine.TrailStroke> trails = engine.collectRenderableTrails();
            for (int i = 0; i < trails.size(); i++) {
                drawTrail(canvas, trails.get(i), cfg, nowMs, density);
            }
        }

        // 2. Shockwave Rings
        List<TypingEffectsEngine.ShockwaveRing> rings = engine.getRings();
        for (int i = 0; i < rings.size(); i++) {
            drawShockwaveRing(canvas, rings.get(i), nowMs);
        }

        // 3. Particles (sparks, twinkling stars, confetti)
        List<TypingEffectsEngine.Particle> particles = engine.getParticles();
        for (int i = 0; i < particles.size(); i++) {
            drawParticle(canvas, particles.get(i), nowMs);
        }
    }

    private boolean drawTrampolineWarpMat(
            Canvas canvas,
            TypingEffectsConfig cfg,
            long nowMs,
            float density) {
        View hostView = hostViewRef.get();
        if (hostView == null) return false;
        int w = hostView.getWidth();
        int h = hostView.getHeight();
        if (w <= 0 || h <= 0) return false;

        boolean displaced = engine.fillTrampolineMesh(
                w, h, MESH_COLS, MESH_ROWS, nowMs, meshVerts, meshColors);
        if (!displaced) {
            return false;
        }

        if (captureBitmap == null
                || captureBitmap.isRecycled()
                || captureBitmap.getWidth() != w
                || captureBitmap.getHeight() != h) {
            if (captureBitmap != null && !captureBitmap.isRecycled()) {
                captureBitmap.recycle();
            }
            try {
                captureBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                captureCanvas = new Canvas(captureBitmap);
            } catch (OutOfMemoryError ignored) {
                captureBitmap = null;
                captureCanvas = null;
                return false;
            }
        }

        isCapturing = true;
        try {
            captureBitmap.eraseColor(Color.TRANSPARENT);
            drawOpaqueKeyboardBackdrop(hostView, captureCanvas);
            hostView.draw(captureCanvas);
            if (cfg.keyHighlightEnabled) {
                drawAllKeyHighlights(captureCanvas, cfg, nowMs, density);
            }
        } finally {
            isCapturing = false;
        }

        canvas.drawBitmapMesh(
                captureBitmap,
                MESH_COLS,
                MESH_ROWS,
                meshVerts,
                0,
                meshColors,
                0,
                meshPaint);
        return true;
    }

    /**
     * Draws the nearest ancestor keyboard background into the offscreen capture bitmap so gaps
     * between keys remain 100% opaque and never reveal unwarped keys underneath.
     */
    private static void drawOpaqueKeyboardBackdrop(View hostView, Canvas outCanvas) {
        float offsetX = 0f;
        float offsetY = 0f;
        View cur = hostView;
        for (int depth = 0; depth < 6 && cur != null; depth++) {
            Drawable bg = cur.getBackground();
            if (bg != null) {
                int save = outCanvas.save();
                outCanvas.translate(-offsetX, -offsetY);
                bg.draw(outCanvas);
                outCanvas.restoreToCount(save);
                return;
            }
            offsetX += cur.getX();
            offsetY += cur.getY();
            if (cur.getParent() instanceof View) {
                View parent = (View) cur.getParent();
                offsetX -= parent.getScrollX();
                offsetY -= parent.getScrollY();
                cur = parent;
            } else {
                break;
            }
        }
    }

    private void drawAllKeyHighlights(
            Canvas canvas,
            TypingEffectsConfig cfg,
            long nowMs,
            float density) {
        List<TypingEffectsEngine.KeyHighlight> keys = engine.getKeyHighlights();
        for (int i = 0; i < keys.size(); i++) {
            drawKeyHighlight(canvas, keys.get(i), cfg, nowMs, density);
        }
    }

    private void drawKeyHighlight(
            Canvas canvas,
            TypingEffectsEngine.KeyHighlight kh,
            TypingEffectsConfig cfg,
            long nowMs,
            float density) {
        float fadeT = clamp01((nowMs - kh.lastTouchedMs) / (float) Math.max(1L, kh.durationMs));
        if (fadeT >= 1f) return;

        // Smooth ease-out fade so the key stays radiant briefly, then dissolves like an RGB keycap.
        float alphaFactor = (1f - fadeT) * (1f - fadeT);

        // Subtle elastic pop-in during the first 75ms of touch, aligned directly to the key's
        // real background pill outline.
        float popT = clamp01((nowMs - kh.firstTouchedMs) / 75f);
        float popExpandPx = (1f - popT) * 1.2f * density;

        keyRect.set(
                kh.left - popExpandPx,
                kh.top - popExpandPx,
                kh.right + popExpandPx,
                kh.bottom + popExpandPx);

        float width = keyRect.width();
        float height = keyRect.height();
        if (width <= 0f || height <= 0f) return;

        float maxRadius = Math.min(width, height) * 0.5f;
        float cornerRadius = kh.cornerRadiusPx >= 0f
                ? Math.min(maxRadius, kh.cornerRadiusPx + popExpandPx)
                : Math.min(8f * density, Math.min(width, height) * 0.24f);
        int baseColor = kh.colorArgb;
        int brightColor = TypingEffectsConfig.blendTowardWhite(baseColor, 0.58f);

        boolean isSoftFill = TypingEffectsConfig.KEY_HIGHLIGHT_STYLE_SOFT_FILL.equals(cfg.keyHighlightStyle);
        boolean isPlasma = TypingEffectsConfig.KEY_HIGHLIGHT_STYLE_PLASMA_PULSE.equals(cfg.keyHighlightStyle);

        if (isSoftFill) {
            // Translucent glowing glass fill + delicate bright rim
            keyFillPaint.setColor(TypingEffectsConfig.withAlpha(baseColor, Math.round(92f * alphaFactor)));
            canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyFillPaint);

            keyBorderPaint.setStrokeWidth(1.6f * density);
            keyBorderPaint.setColor(TypingEffectsConfig.withAlpha(brightColor, Math.round(185f * alphaFactor)));
            canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyBorderPaint);
        } else {
            // Neon Glow & Plasma Pulse: outer halo + rich translucent fill + crisp neon rim + top gloss
            keyHaloPaint.setStrokeWidth(5.8f * density * (0.65f + 0.35f * alphaFactor));
            keyHaloPaint.setColor(TypingEffectsConfig.withAlpha(baseColor, Math.round(74f * alphaFactor)));
            canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyHaloPaint);

            keyFillPaint.setColor(TypingEffectsConfig.withAlpha(baseColor, Math.round(68f * alphaFactor)));
            canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyFillPaint);

            // Crisp luminous neon border
            keyBorderPaint.setStrokeWidth(2.1f * density);
            keyBorderPaint.setColor(TypingEffectsConfig.withAlpha(brightColor, Math.round(235f * alphaFactor)));
            canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyBorderPaint);

            // Plasma Pulse: expanding outer light frame radiating off the key
            if (isPlasma) {
                float pulseT = clamp01((nowMs - kh.firstTouchedMs) / (float) Math.max(120L, kh.durationMs));
                if (pulseT < 1f) {
                    float eased = 1f - (1f - pulseT) * (1f - pulseT);
                    float growPx = eased * 9.5f * density;
                    float pulseAlpha = (1f - pulseT) * (1f - pulseT);
                    keyPulseRect.set(
                            keyRect.left - growPx,
                            keyRect.top - growPx,
                            keyRect.right + growPx,
                            keyRect.bottom + growPx);
                    keyBorderPaint.setStrokeWidth(Math.max(1f, 2.2f * density * (1f - 0.55f * pulseT)));
                    keyBorderPaint.setColor(TypingEffectsConfig.withAlpha(
                            brightColor, Math.round(175f * pulseAlpha)));
                    canvas.drawRoundRect(
                            keyPulseRect,
                            cornerRadius + growPx * 0.4f,
                            cornerRadius + growPx * 0.4f,
                            keyBorderPaint);
                }
            }
        }
    }

    private void drawTrail(
            Canvas canvas,
            TypingEffectsEngine.TrailStroke stroke,
            TypingEffectsConfig cfg,
            long nowMs,
            float density) {
        List<TypingEffectsEngine.TrailPoint> pts = stroke.points;
        int n = pts.size();
        if (n < 2) return;

        float fadeMs = Math.max(80f, cfg.trailFadeMs);
        float strokeFade = stroke.active ? 1f : clamp01(1f - (nowMs - stroke.releaseTimeMs) / fadeMs);
        if (strokeFade <= 0.01f) return;

        float maxWidthPx = cfg.trailWidthDp * density;
        boolean glow = cfg.trailGlowEnabled;

        for (int i = 1; i < n; i++) {
            TypingEffectsEngine.TrailPoint p0 = pts.get(i - 1);
            TypingEffectsEngine.TrailPoint p1 = pts.get(i);

            float posRatio = (float) i / (float) (n - 1); // 0 at tail -> 1 at fingertip
            float ageRatio = clamp01(1f - (nowMs - p1.timeMs) / fadeMs);
            float weight = posRatio * ageRatio * strokeFade;
            if (weight <= 0.015f) continue;

            // Smooth tapered width: slender tail blooming into full width at the fingertip.
            float width = Math.max(1.5f * density, maxWidthPx * (0.22f + 0.78f * posRatio) * (0.55f + 0.45f * ageRatio));
            int baseColor = cfg.sampleColor(p1.colorPhase);

            if (glow) {
                int glowAlpha = Math.round(78f * weight);
                trailGlowPaint.setStrokeWidth(width * 2.15f);
                trailGlowPaint.setColor(TypingEffectsConfig.withAlpha(baseColor, glowAlpha));
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailGlowPaint);
            }

            int mainAlpha = Math.round(235f * weight);
            trailMainPaint.setStrokeWidth(width);
            trailMainPaint.setColor(TypingEffectsConfig.withAlpha(baseColor, mainAlpha));
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailMainPaint);

            if (glow) {
                int coreColor = TypingEffectsConfig.blendTowardWhite(baseColor, 0.72f);
                int coreAlpha = Math.round(190f * weight * posRatio);
                trailCorePaint.setStrokeWidth(width * 0.36f);
                trailCorePaint.setColor(TypingEffectsConfig.withAlpha(coreColor, coreAlpha));
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailCorePaint);
            }
        }
    }

    private void drawShockwaveRing(
            Canvas canvas,
            TypingEffectsEngine.ShockwaveRing ring,
            long nowMs) {
        float t = clamp01((nowMs - ring.bornMs) / (float) Math.max(1L, ring.durationMs));
        if (t >= 1f) return;

        // Ease-out cubic expansion
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        float radius = ring.startRadiusPx + (ring.maxRadiusPx - ring.startRadiusPx) * eased;
        float alphaFactor = (1f - t) * (1f - t);

        // Soft outer halo
        ringGlowPaint.setStrokeWidth(ring.strokeWidthPx * 2.6f * (1f - 0.5f * t));
        ringGlowPaint.setColor(TypingEffectsConfig.withAlpha(ring.colorArgb, Math.round(85f * alphaFactor)));
        canvas.drawCircle(ring.x, ring.y, radius, ringGlowPaint);

        // Crisp luminous inner ring
        int crispColor = TypingEffectsConfig.blendTowardWhite(ring.colorArgb, 0.55f);
        ringPaint.setStrokeWidth(Math.max(1f, ring.strokeWidthPx * (1f - 0.65f * t)));
        ringPaint.setColor(TypingEffectsConfig.withAlpha(crispColor, Math.round(230f * alphaFactor)));
        canvas.drawCircle(ring.x, ring.y, radius, ringPaint);
    }

    private void drawParticle(
            Canvas canvas,
            TypingEffectsEngine.Particle p,
            long nowMs) {
        float lifeT = clamp01((nowMs - p.bornMs) / (float) Math.max(1L, p.lifetimeMs));
        if (lifeT >= 1f) return;

        // Smooth fade-out curve (stays bright for first 35%, then dissolves cleanly)
        float alphaFactor = lifeT < 0.35f ? 1f : clamp01(1f - (lifeT - 0.35f) / 0.65f);
        float scale = 1f - 0.45f * lifeT;

        if (p.shape == TypingEffectsEngine.SHAPE_SPARK) {
            drawSpark(canvas, p, alphaFactor, scale);
        } else if (p.shape == TypingEffectsEngine.SHAPE_STAR) {
            drawStar(canvas, p, lifeT, alphaFactor, scale);
        } else {
            drawConfetti(canvas, p, alphaFactor, scale);
        }
    }

    private void drawSpark(
            Canvas canvas,
            TypingEffectsEngine.Particle p,
            float alphaFactor,
            float scale) {
        float size = Math.max(1.5f, p.sizePx * scale);
        // Stretch slightly along velocity vector for energetic motion-blur sparks.
        float tailX = p.x - p.vx * 0.028f;
        float tailY = p.y - p.vy * 0.028f;

        // Soft colored glow streak
        sparkStrokePaint.setStrokeWidth(size * 1.9f);
        sparkStrokePaint.setColor(TypingEffectsConfig.withAlpha(p.colorArgb, Math.round(95f * alphaFactor)));
        canvas.drawLine(tailX, tailY, p.x, p.y, sparkStrokePaint);

        // Main colored streak
        sparkStrokePaint.setStrokeWidth(size);
        sparkStrokePaint.setColor(TypingEffectsConfig.withAlpha(p.colorArgb, Math.round(240f * alphaFactor)));
        canvas.drawLine(tailX, tailY, p.x, p.y, sparkStrokePaint);

        // Hot white core dot at leading tip
        particlePaint.setColor(TypingEffectsConfig.withAlpha(
                TypingEffectsConfig.blendTowardWhite(p.colorArgb, 0.8f),
                Math.round(230f * alphaFactor)));
        canvas.drawCircle(p.x, p.y, size * 0.48f, particlePaint);
    }

    private void drawStar(
            Canvas canvas,
            TypingEffectsEngine.Particle p,
            float lifeT,
            float alphaFactor,
            float scale) {
        // Twinkle oscillation
        float twinkle = 0.62f + 0.38f * (float) Math.sin(p.twinklePhase + lifeT * 18f);
        float radius = Math.max(2f, p.sizePx * scale * (0.75f + 0.35f * twinkle));
        float inner = radius * 0.28f;

        canvas.save();
        canvas.translate(p.x, p.y);
        canvas.rotate(p.rotationDeg);

        // Soft circular aura behind the sparkle
        particlePaint.setColor(TypingEffectsConfig.withAlpha(
                p.colorArgb, Math.round(70f * alphaFactor * twinkle)));
        canvas.drawCircle(0f, 0f, radius * 1.15f, particlePaint);

        // 4-point diamond sparkle star
        starPath.rewind();
        starPath.moveTo(0f, -radius);
        starPath.lineTo(inner, -inner);
        starPath.lineTo(radius, 0f);
        starPath.lineTo(inner, inner);
        starPath.lineTo(0f, radius);
        starPath.lineTo(-inner, inner);
        starPath.lineTo(-radius, 0f);
        starPath.lineTo(-inner, -inner);
        starPath.close();

        int starColor = TypingEffectsConfig.blendTowardWhite(p.colorArgb, 0.45f);
        particlePaint.setColor(TypingEffectsConfig.withAlpha(
                starColor, Math.round(245f * alphaFactor * twinkle)));
        canvas.drawPath(starPath, particlePaint);

        // Bright white glint center
        particlePaint.setColor(TypingEffectsConfig.withAlpha(
                0xFFFFFFFF, Math.round(220f * alphaFactor * twinkle)));
        canvas.drawCircle(0f, 0f, inner * 0.7f, particlePaint);

        canvas.restore();
    }

    private void drawConfetti(
            Canvas canvas,
            TypingEffectsEngine.Particle p,
            float alphaFactor,
            float scale) {
        float w = Math.max(2f, p.sizePx * scale * 1.25f);
        float flip = 0.25f + 0.75f * Math.abs((float) Math.cos(p.flipPhase));
        float h = Math.max(1.2f, p.sizePx * scale * 0.58f * flip);

        canvas.save();
        canvas.translate(p.x, p.y);
        canvas.rotate(p.rotationDeg);
        confettiRect.set(-w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f);
        particlePaint.setColor(TypingEffectsConfig.withAlpha(p.colorArgb, Math.round(238f * alphaFactor)));
        canvas.drawRoundRect(confettiRect, h * 0.25f, h * 0.25f, particlePaint);
        canvas.restore();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public void setAlpha(int alpha) {
        // Alpha is driven per-element by the particle/trail lifetimes.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Not used.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
