package dev.srwi.gboardmultiswipe.extension.typingeffects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pure-Java simulation engine for Typing Effects: multi-pointer swipe trails, swipe glitter
 * emitters, tap/release particle explosions, and expanding shockwave rings.
 *
 * <p>Designed to be independent of Android {@link android.graphics.Canvas} and
 * {@link android.view.MotionEvent} so every behavior is deterministically unit-testable on JVM.
 */
public final class TypingEffectsEngine {

    public static final int SHAPE_SPARK = 0;
    public static final int SHAPE_STAR = 1;
    public static final int SHAPE_CONFETTI = 2;

    static final float SWIPE_THRESHOLD_DP = 14f;
    static final int MAX_PARTICLES = 360;
    static final int MAX_RINGS = 24;
    static final int MAX_TRAIL_POINTS = 160;
    static final int MAX_FADING_TRAILS = 12;
    static final int MAX_KEY_HIGHLIGHTS = 36;

    public static final class TrailPoint {
        public float x;
        public float y;
        public long timeMs;
        public float colorPhase;

        TrailPoint(float x, float y, long timeMs, float colorPhase) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
            this.colorPhase = colorPhase;
        }
    }

    public static final class TrailStroke {
        public final List<TrailPoint> points = new ArrayList<TrailPoint>(32);
        public boolean active;
        public long releaseTimeMs;
    }

    public static final class Particle {
        public float x;
        public float y;
        public float prevX;
        public float prevY;
        public float vx;
        public float vy;
        public float gravity;
        public float drag;
        public float sizePx;
        public float rotationDeg;
        public float angularVelocityDeg;
        public float flipPhase;
        public float flipSpeed;
        public float twinklePhase;
        public int colorArgb;
        public int shape;
        public long bornMs;
        public long lifetimeMs;
    }

    public static final class ShockwaveRing {
        public float x;
        public float y;
        public float startRadiusPx;
        public float maxRadiusPx;
        public float strokeWidthPx;
        public int colorArgb;
        public long bornMs;
        public long durationMs;
    }

    public static final class KeyHighlight {
        public int keyId;
        public float left;
        public float top;
        public float right;
        public float bottom;
        public int colorArgb;
        public long firstTouchedMs;
        public long lastTouchedMs;
        public long durationMs;
    }

    private static final class PointerState {
        final int pointerId;
        float downX;
        float downY;
        float lastX;
        float lastY;
        float totalDistancePx;
        float glitterDistanceCarryPx;
        float colorPhaseOffset;
        boolean isSwipe;
        final TrailStroke stroke;

        PointerState(int pointerId, float x, float y, float colorPhaseOffset) {
            this.pointerId = pointerId;
            this.downX = x;
            this.downY = y;
            this.lastX = x;
            this.lastY = y;
            this.colorPhaseOffset = colorPhaseOffset;
            this.stroke = new TrailStroke();
            this.stroke.active = true;
        }
    }

    private final float density;
    private final Random random;
    private final Map<Integer, PointerState> activePointers = new HashMap<Integer, PointerState>();
    private final List<TrailStroke> fadingTrails = new ArrayList<TrailStroke>();
    private final List<Particle> particles = new ArrayList<Particle>(64);
    private final List<ShockwaveRing> rings = new ArrayList<ShockwaveRing>(8);
    private final List<KeyHighlight> keyHighlights = new ArrayList<KeyHighlight>(16);

    private TypingEffectsConfig config = TypingEffectsConfig.defaults();
    private long lastStepMs = -1L;
    private float pointerPhaseCounter = 0f;
    private float keyPhaseCounter = 0f;

    public TypingEffectsEngine(float density) {
        this(density, new Random());
    }

    public TypingEffectsEngine(float density, Random random) {
        this.density = density > 0f ? density : 1f;
        this.random = random != null ? random : new Random();
    }

    public void setConfig(TypingEffectsConfig config) {
        this.config = config != null ? config : TypingEffectsConfig.defaults();
    }

    public TypingEffectsConfig getConfig() {
        return config;
    }

    public float getDensity() {
        return density;
    }

    public void onPointerDown(int pointerId, float x, float y, long nowMs) {
        if (!config.hasAnyVisualEffectEnabled()) {
            return;
        }
        // Offset each simultaneous pointer's color phase so two-finger MultiSwipe trails look
        // visually distinct and harmonious.
        float phaseOffset = pointerPhaseCounter;
        pointerPhaseCounter = (pointerPhaseCounter + 0.37f) % 1.0f;

        PointerState state = new PointerState(pointerId, x, y, phaseOffset);
        state.stroke.points.add(new TrailPoint(x, y, nowMs, computeColorPhase(nowMs, phaseOffset)));
        activePointers.put(pointerId, state);
    }

    public void onPointerMove(int pointerId, float x, float y, long nowMs) {
        if (!config.hasAnyVisualEffectEnabled()) {
            return;
        }
        PointerState state = activePointers.get(pointerId);
        if (state == null) {
            onPointerDown(pointerId, x, y, nowMs);
            return;
        }

        float dx = x - state.lastX;
        float dy = y - state.lastY;
        float segDist = (float) Math.hypot(dx, dy);
        if (segDist < 0.75f * density) {
            return;
        }

        state.totalDistancePx += segDist;
        float swipeThresholdPx = SWIPE_THRESHOLD_DP * density;
        if (!state.isSwipe && state.totalDistancePx >= swipeThresholdPx) {
            state.isSwipe = true;
        }

        float phase = computeColorPhase(nowMs, state.colorPhaseOffset + state.totalDistancePx / (360f * density));

        if (config.rainbowTrailEnabled) {
            List<TrailPoint> pts = state.stroke.points;
            pts.add(new TrailPoint(x, y, nowMs, phase));
            if (pts.size() > MAX_TRAIL_POINTS) {
                pts.remove(0);
            }
        }

        if (config.glitterEnabled && state.isSwipe) {
            emitGlitterAlongSegment(state, state.lastX, state.lastY, x, y, segDist, nowMs, phase);
        }

        state.lastX = x;
        state.lastY = y;
    }

    public void onPointerUp(int pointerId, float x, float y, long nowMs) {
        PointerState state = activePointers.remove(pointerId);
        if (state == null) {
            return;
        }

        if (config.rainbowTrailEnabled && state.isSwipe && state.stroke.points.size() >= 2) {
            state.stroke.active = false;
            state.stroke.releaseTimeMs = nowMs;
            fadingTrails.add(state.stroke);
            while (fadingTrails.size() > MAX_FADING_TRAILS) {
                fadingTrails.remove(0);
            }
        }

        if (!config.enabled) {
            return;
        }

        float phase = computeColorPhase(nowMs, state.colorPhaseOffset);
        if (state.isSwipe) {
            if (config.releaseExplosionEnabled) {
                spawnExplosion(x, y, nowMs, phase, true);
            }
        } else {
            if (config.tapExplosionEnabled) {
                spawnExplosion(x, y, nowMs, phase, false);
            }
        }
    }

    public void onCancel(long nowMs) {
        for (PointerState state : activePointers.values()) {
            if (config.rainbowTrailEnabled && state.isSwipe && state.stroke.points.size() >= 2) {
                state.stroke.active = false;
                state.stroke.releaseTimeMs = nowMs;
                fadingTrails.add(state.stroke);
            }
        }
        activePointers.clear();
    }

    public void onKeyTouched(
            int keyId,
            float left,
            float top,
            float right,
            float bottom,
            long nowMs) {
        if (!config.enabled || !config.keyHighlightEnabled) {
            return;
        }
        if (right <= left || bottom <= top) {
            return;
        }
        long durationMs = Math.max(80L, config.keyHighlightDurationMs);
        // Refresh existing highlight for the same key so holding/sliding across it stays smooth.
        for (int i = 0; i < keyHighlights.size(); i++) {
            KeyHighlight existing = keyHighlights.get(i);
            if (existing.keyId == keyId) {
                existing.left = left;
                existing.top = top;
                existing.right = right;
                existing.bottom = bottom;
                existing.lastTouchedMs = nowMs;
                existing.durationMs = durationMs;
                return;
            }
        }
        KeyHighlight highlight;
        if (keyHighlights.size() >= MAX_KEY_HIGHLIGHTS) {
            highlight = keyHighlights.remove(0);
        } else {
            highlight = new KeyHighlight();
        }
        float phase = computeColorPhase(nowMs, keyPhaseCounter);
        keyPhaseCounter = (keyPhaseCounter + 0.09f) % 1.0f;

        highlight.keyId = keyId;
        highlight.left = left;
        highlight.top = top;
        highlight.right = right;
        highlight.bottom = bottom;
        highlight.colorArgb = config.sampleColor(phase);
        highlight.firstTouchedMs = nowMs;
        highlight.lastTouchedMs = nowMs;
        highlight.durationMs = durationMs;
        keyHighlights.add(highlight);
    }

    public void clearAll() {
        activePointers.clear();
        fadingTrails.clear();
        particles.clear();
        rings.clear();
        keyHighlights.clear();
        lastStepMs = -1L;
    }

    /**
     * Advances simulation physics and prunes expired trails, particles, and rings.
     *
     * @return {@code true} if at least one visual element still needs rendering/animation.
     */
    public boolean step(long nowMs) {
        float dtSec;
        if (lastStepMs < 0L || nowMs <= lastStepMs) {
            dtSec = 1f / 60f;
        } else {
            long deltaMs = Math.min(50L, nowMs - lastStepMs);
            dtSec = deltaMs / 1000f;
        }
        lastStepMs = nowMs;

        long fadeMs = Math.max(80L, config.trailFadeMs);

        // Trim expired points from active pointer trails so trails stay crisp and tail-faded.
        for (PointerState state : activePointers.values()) {
            trimTrailPoints(state.stroke.points, nowMs - fadeMs);
        }

        // Trim and prune fading trails.
        Iterator<TrailStroke> trailIt = fadingTrails.iterator();
        while (trailIt.hasNext()) {
            TrailStroke stroke = trailIt.next();
            trimTrailPoints(stroke.points, nowMs - fadeMs);
            if (stroke.points.size() < 2 || (nowMs - stroke.releaseTimeMs) > fadeMs) {
                trailIt.remove();
            }
        }

        // Advance particles.
        Iterator<Particle> particleIt = particles.iterator();
        while (particleIt.hasNext()) {
            Particle p = particleIt.next();
            if (nowMs - p.bornMs >= p.lifetimeMs) {
                particleIt.remove();
                continue;
            }
            p.prevX = p.x;
            p.prevY = p.y;
            float dragFactor = (float) Math.pow(p.drag, dtSec * 60f);
            p.vx *= dragFactor;
            p.vy = p.vy * dragFactor + p.gravity * dtSec;
            p.x += p.vx * dtSec;
            p.y += p.vy * dtSec;
            p.rotationDeg += p.angularVelocityDeg * dtSec;
            p.flipPhase += p.flipSpeed * dtSec;
        }

        // Prune expired shockwave rings.
        Iterator<ShockwaveRing> ringIt = rings.iterator();
        while (ringIt.hasNext()) {
            ShockwaveRing ring = ringIt.next();
            if (nowMs - ring.bornMs >= ring.durationMs) {
                ringIt.remove();
            }
        }

        // Prune expired key highlights.
        Iterator<KeyHighlight> keyIt = keyHighlights.iterator();
        while (keyIt.hasNext()) {
            KeyHighlight kh = keyIt.next();
            if (nowMs - kh.lastTouchedMs >= kh.durationMs) {
                keyIt.remove();
            }
        }

        return hasActiveEffects();
    }

    public boolean hasActiveEffects() {
        if (!particles.isEmpty()
                || !rings.isEmpty()
                || !fadingTrails.isEmpty()
                || !keyHighlights.isEmpty()) {
            return true;
        }
        if (config.rainbowTrailEnabled) {
            for (PointerState state : activePointers.values()) {
                if (state.isSwipe && state.stroke.points.size() >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<TrailStroke> collectRenderableTrails() {
        List<TrailStroke> result = new ArrayList<TrailStroke>(fadingTrails.size() + activePointers.size());
        if (!config.enabled || !config.rainbowTrailEnabled) {
            return result;
        }
        result.addAll(fadingTrails);
        for (PointerState state : activePointers.values()) {
            if (state.isSwipe && state.stroke.points.size() >= 2) {
                result.add(state.stroke);
            }
        }
        return result;
    }

    public List<Particle> getParticles() {
        return particles;
    }

    public List<ShockwaveRing> getRings() {
        return rings;
    }

    public List<KeyHighlight> getKeyHighlights() {
        return keyHighlights;
    }

    public int getActivePointerCount() {
        return activePointers.size();
    }

    private float computeColorPhase(long nowMs, float offset) {
        float speedFactor = config.rainbowSpeedPercent / 100f;
        float timeCycles = (nowMs % 100000L) * 0.00045f * speedFactor;
        return timeCycles + offset;
    }

    private void emitGlitterAlongSegment(
            PointerState state,
            float x0,
            float y0,
            float x1,
            float y1,
            float segDist,
            long nowMs,
            float basePhase) {
        // Higher glitterDensity -> shorter spacing between emitted sparkles.
        float spacingDp = Math.max(3.5f, 22f - config.glitterDensity * 1.85f);
        float spacingPx = spacingDp * density;
        state.glitterDistanceCarryPx += segDist;

        int emitted = 0;
        int maxPerMove = 10;
        float sizeScale = config.particleSizePercent / 100f;
        float speedScale = config.particleSpeedPercent / 100f;
        float spreadPx = Math.max(4f, config.trailWidthDp * 0.6f) * density;

        while (state.glitterDistanceCarryPx >= spacingPx && emitted < maxPerMove) {
            state.glitterDistanceCarryPx -= spacingPx;
            float frac = 1f - (state.glitterDistanceCarryPx / Math.max(1f, segDist));
            if (frac < 0f) frac = 0f;
            if (frac > 1f) frac = 1f;

            float spawnX = x0 + (x1 - x0) * frac + (random.nextFloat() - 0.5f) * 2f * spreadPx;
            float spawnY = y0 + (y1 - y0) * frac + (random.nextFloat() - 0.5f) * 2f * spreadPx;

            int shape = pickGlitterShape();
            float angle = random.nextFloat() * (float) (Math.PI * 2.0);
            float speedPx = (18f + random.nextFloat() * 54f) * density * speedScale;
            float baseSizeDp = shape == SHAPE_STAR ? (3.5f + random.nextFloat() * 4.2f)
                    : (shape == SHAPE_CONFETTI ? (3.2f + random.nextFloat() * 3.5f)
                    : (2.2f + random.nextFloat() * 2.8f));
            long life = Math.round(config.particleLifetimeMs * (0.55f + random.nextFloat() * 0.55f));
            int color = config.sampleColor(basePhase + (random.nextFloat() - 0.5f) * 0.25f);

            addParticle(
                    spawnX,
                    spawnY,
                    (float) Math.cos(angle) * speedPx,
                    (float) Math.sin(angle) * speedPx - 12f * density,
                    (shape == SHAPE_CONFETTI ? 140f : 42f) * density,
                    0.94f,
                    baseSizeDp * density * sizeScale,
                    random.nextFloat() * 360f,
                    (random.nextFloat() - 0.5f) * 320f,
                    random.nextFloat() * 6.28f,
                    5f + random.nextFloat() * 7f,
                    random.nextFloat() * 6.28f,
                    color,
                    shape,
                    nowMs,
                    Math.max(120L, life));
            emitted++;
        }
    }

    private void spawnExplosion(
            float x,
            float y,
            long nowMs,
            float basePhase,
            boolean isSwipeRelease) {
        float intensityBoost = isSwipeRelease ? 1.25f : 1.0f;
        int count = Math.max(4, Math.round(config.particleCount * intensityBoost));
        float sizeScale = config.particleSizePercent / 100f;
        float speedScale = config.particleSpeedPercent / 100f;
        boolean allOut = TypingEffectsConfig.EXPLOSION_STYLE_ALL_OUT.equals(config.explosionStyle);

        for (int i = 0; i < count; i++) {
            int shape = pickExplosionShape(i);
            // Distribute angles evenly around the circle with organic jitter so bursts look full.
            float baseAngle = (float) (2.0 * Math.PI * i / count);
            float jitter = (random.nextFloat() - 0.5f) * (float) (2.0 * Math.PI / Math.max(6, count));
            float angle = baseAngle + jitter;

            float speedBaseDp = isSwipeRelease ? 140f : 115f;
            float speedPx = (speedBaseDp * (0.45f + random.nextFloat() * 0.95f)) * density * speedScale;
            float vx = (float) Math.cos(angle) * speedPx;
            float vy = (float) Math.sin(angle) * speedPx;

            float gravityDp;
            float drag;
            float sizeDp;
            if (shape == SHAPE_CONFETTI) {
                gravityDp = 240f;
                drag = 0.92f;
                sizeDp = 4.2f + random.nextFloat() * 4.2f;
                vy -= 45f * density; // Pop upward before fluttering down.
            } else if (shape == SHAPE_STAR) {
                gravityDp = 55f;
                drag = 0.93f;
                sizeDp = 4.5f + random.nextFloat() * 4.8f;
            } else {
                gravityDp = 120f;
                drag = 0.91f;
                sizeDp = 3.0f + random.nextFloat() * 3.5f;
            }

            float colorPhase = basePhase + (float) i / Math.max(1, count) * (allOut ? 1.0f : 0.65f);
            int color = config.sampleColor(colorPhase);
            long life = Math.round(config.particleLifetimeMs * (0.72f + random.nextFloat() * 0.56f));

            addParticle(
                    x,
                    y,
                    vx,
                    vy,
                    gravityDp * density,
                    drag,
                    sizeDp * density * sizeScale,
                    random.nextFloat() * 360f,
                    (random.nextFloat() - 0.5f) * 520f,
                    random.nextFloat() * 6.28f,
                    7f + random.nextFloat() * 10f,
                    random.nextFloat() * 6.28f,
                    color,
                    shape,
                    nowMs,
                    Math.max(150L, life));
        }

        if (config.shockwaveRingEnabled) {
            ShockwaveRing ring = new ShockwaveRing();
            ring.x = x;
            ring.y = y;
            ring.startRadiusPx = 6f * density * sizeScale;
            ring.maxRadiusPx = (isSwipeRelease ? 58f : 44f) * density * Math.min(1.5f, 0.7f + 0.3f * sizeScale);
            ring.strokeWidthPx = (isSwipeRelease ? 4.2f : 3.2f) * density;
            ring.colorArgb = config.sampleColor(basePhase + 0.1f);
            ring.bornMs = nowMs;
            ring.durationMs = Math.max(180L, Math.round(config.particleLifetimeMs * 0.62f));
            rings.add(ring);
            while (rings.size() > MAX_RINGS) {
                rings.remove(0);
            }
        }
    }

    private int pickExplosionShape(int index) {
        if (TypingEffectsConfig.EXPLOSION_STYLE_SPARKLES.equals(config.explosionStyle)) {
            return SHAPE_STAR;
        }
        if (TypingEffectsConfig.EXPLOSION_STYLE_CONFETTI.equals(config.explosionStyle)) {
            return SHAPE_CONFETTI;
        }
        if (TypingEffectsConfig.EXPLOSION_STYLE_ALL_OUT.equals(config.explosionStyle)) {
            int mod = index % 3;
            if (mod == 0) return SHAPE_SPARK;
            if (mod == 1) return SHAPE_STAR;
            return SHAPE_CONFETTI;
        }
        // Default: sparks_ring — mostly crisp sparks with occasional stars for show polish.
        return (index % 5 == 0) ? SHAPE_STAR : SHAPE_SPARK;
    }

    private int pickGlitterShape() {
        if (TypingEffectsConfig.GLITTER_STYLE_SPARKS.equals(config.glitterStyle)) {
            return SHAPE_SPARK;
        }
        if (TypingEffectsConfig.GLITTER_STYLE_CONFETTI.equals(config.glitterStyle)) {
            return SHAPE_CONFETTI;
        }
        if (TypingEffectsConfig.GLITTER_STYLE_MIXED.equals(config.glitterStyle)) {
            int r = random.nextInt(3);
            return r == 0 ? SHAPE_STAR : (r == 1 ? SHAPE_SPARK : SHAPE_CONFETTI);
        }
        return SHAPE_STAR;
    }

    private void addParticle(
            float x,
            float y,
            float vx,
            float vy,
            float gravity,
            float drag,
            float sizePx,
            float rotationDeg,
            float angularVelocityDeg,
            float flipPhase,
            float flipSpeed,
            float twinklePhase,
            int colorArgb,
            int shape,
            long bornMs,
            long lifetimeMs) {
        Particle p;
        if (particles.size() >= MAX_PARTICLES) {
            p = particles.remove(0);
        } else {
            p = new Particle();
        }
        p.x = x;
        p.y = y;
        p.prevX = x;
        p.prevY = y;
        p.vx = vx;
        p.vy = vy;
        p.gravity = gravity;
        p.drag = drag;
        p.sizePx = sizePx;
        p.rotationDeg = rotationDeg;
        p.angularVelocityDeg = angularVelocityDeg;
        p.flipPhase = flipPhase;
        p.flipSpeed = flipSpeed;
        p.twinklePhase = twinklePhase;
        p.colorArgb = colorArgb;
        p.shape = shape;
        p.bornMs = bornMs;
        p.lifetimeMs = lifetimeMs;
        particles.add(p);
    }

    private static void trimTrailPoints(List<TrailPoint> points, long cutoffMs) {
        int removeCount = 0;
        int limit = Math.max(0, points.size() - 2);
        while (removeCount < limit && points.get(removeCount).timeMs < cutoffMs) {
            removeCount++;
        }
        if (removeCount > 0) {
            points.subList(0, removeCount).clear();
        }
    }
}
