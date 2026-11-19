package dev.srwi.gboardmultiswipe.extension.typingeffects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class TypingEffectsEngineTest {

    @Test
    public void tapSpawnsExplosionAndShockwaveRingWithoutTrail() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(42L));
        engine.setConfig(TypingEffectsConfig.defaults());

        engine.onPointerDown(0, 100f, 200f, 1000L);
        engine.onPointerUp(0, 102f, 201f, 1060L);

        assertEquals("Tap should spawn configured particle burst",
                GboardTypingEffectsSettings.DEFAULT_PARTICLE_COUNT,
                engine.getParticles().size());
        assertEquals("Tap should spawn shockwave ring when enabled",
                1,
                engine.getRings().size());
        assertTrue("Tap should not leave a fading swipe trail",
                engine.collectRenderableTrails().isEmpty());
        assertTrue(engine.hasActiveEffects());
    }

    @Test
    public void swipeGeneratesRainbowTrailGlitterAndReleaseExplosion() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(7L));
        engine.setConfig(TypingEffectsConfig.defaults());

        engine.onPointerDown(0, 50f, 150f, 1000L);
        engine.onPointerMove(0, 110f, 150f, 1016L);
        engine.onPointerMove(0, 180f, 160f, 1032L);
        engine.onPointerMove(0, 260f, 175f, 1048L);

        assertEquals("Active swipe should expose 1 renderable trail",
                1, engine.collectRenderableTrails().size());
        int glitterCount = engine.getParticles().size();
        assertTrue("Swipe should emit glitter particles, got " + glitterCount,
                glitterCount > 0);

        engine.onPointerUp(0, 260f, 175f, 1064L);
        assertTrue("Release explosion should add burst particles beyond glitter",
                engine.getParticles().size() > glitterCount);
        assertEquals("Swipe release should spawn a shockwave ring",
                1, engine.getRings().size());
        assertEquals("Released swipe trail should fade gracefully",
                1, engine.collectRenderableTrails().size());
    }

    @Test
    public void multiPointerSwipesTrackIndependentTrailsSimultaneously() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(99L));
        engine.setConfig(TypingEffectsConfig.defaults());

        engine.onPointerDown(0, 40f, 120f, 1000L);
        engine.onPointerDown(1, 300f, 120f, 1004L);
        engine.onPointerMove(0, 100f, 140f, 1020L);
        engine.onPointerMove(1, 240f, 150f, 1020L);
        engine.onPointerMove(0, 150f, 160f, 1036L);
        engine.onPointerMove(1, 190f, 170f, 1036L);

        assertEquals("Two-finger MultiSwipe should render 2 simultaneous trails",
                2, engine.collectRenderableTrails().size());
    }

    @Test
    public void individualFeatureTogglesDisableEachEffectIndependently() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(1L));

        // Only rainbow trail enabled: no glitter, no tap explosion, no release explosion, no ring.
        TypingEffectsConfig trailOnly = new TypingEffectsConfig(
                true, true, true, false, false, false, false,
                TypingEffectsConfig.PALETTE_RAINBOW,
                TypingEffectsConfig.EXPLOSION_STYLE_SPARKS_RING,
                TypingEffectsConfig.GLITTER_STYLE_SPARKLES,
                12, 360, 150, 20, 5, 100, 520, 100);
        engine.setConfig(trailOnly);

        engine.onPointerDown(0, 10f, 10f, 100L);
        engine.onPointerUp(0, 11f, 11f, 120L);
        assertTrue("Tap explosion disabled -> 0 particles", engine.getParticles().isEmpty());
        assertTrue("Shockwave ring disabled -> 0 rings", engine.getRings().isEmpty());

        engine.onPointerDown(1, 10f, 10f, 200L);
        engine.onPointerMove(1, 90f, 10f, 220L);
        engine.onPointerMove(1, 180f, 10f, 240L);
        assertEquals(1, engine.collectRenderableTrails().size());
        assertTrue("Glitter disabled -> 0 particles during swipe", engine.getParticles().isEmpty());
        engine.onPointerUp(1, 180f, 10f, 260L);
        assertTrue("Release explosion disabled -> 0 particles on lift", engine.getParticles().isEmpty());
    }

    @Test
    public void masterToggleOffProducesNoEffects() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(5L));
        TypingEffectsConfig disabled = new TypingEffectsConfig(
                false, true, true, true, true, true, true,
                TypingEffectsConfig.PALETTE_NEON,
                TypingEffectsConfig.EXPLOSION_STYLE_ALL_OUT,
                TypingEffectsConfig.GLITTER_STYLE_MIXED,
                12, 360, 150, 20, 5, 100, 520, 100);
        engine.setConfig(disabled);

        engine.onPointerDown(0, 50f, 50f, 100L);
        engine.onPointerMove(0, 200f, 50f, 120L);
        engine.onPointerUp(0, 200f, 50f, 140L);

        assertFalse(engine.hasActiveEffects());
        assertTrue(engine.getParticles().isEmpty());
        assertTrue(engine.getRings().isEmpty());
        assertTrue(engine.collectRenderableTrails().isEmpty());
    }

    @Test
    public void stepExpiresAllParticlesRingsAndTrailsCleanly() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(13L));
        engine.setConfig(TypingEffectsConfig.defaults());

        engine.onPointerDown(0, 20f, 20f, 1000L);
        engine.onPointerMove(0, 120f, 20f, 1020L);
        engine.onPointerUp(0, 120f, 20f, 1040L);
        assertTrue(engine.hasActiveEffects());

        // Advance well past maximum lifetime and trail fade duration.
        boolean stillActive = engine.step(4000L);
        assertFalse("All effects should expire after lifetime elapses", stillActive);
        assertTrue(engine.getParticles().isEmpty());
        assertTrue(engine.getRings().isEmpty());
        assertTrue(engine.collectRenderableTrails().isEmpty());
    }

    @Test
    public void colorPalettesSampleDistinctOpaqueColors() {
        String[] palettes = {
                TypingEffectsConfig.PALETTE_RAINBOW,
                TypingEffectsConfig.PALETTE_NEON,
                TypingEffectsConfig.PALETTE_FIRE,
                TypingEffectsConfig.PALETTE_GALAXY,
                TypingEffectsConfig.PALETTE_PASTEL
        };
        for (String palette : palettes) {
            TypingEffectsConfig cfg = new TypingEffectsConfig(
                    true, true, true, true, true, true, true,
                    palette,
                    TypingEffectsConfig.EXPLOSION_STYLE_ALL_OUT,
                    TypingEffectsConfig.GLITTER_STYLE_MIXED,
                    12, 360, 150, 20, 5, 100, 520, 100);
            int c0 = cfg.sampleColor(0.0f);
            int c1 = cfg.sampleColor(0.35f);
            assertEquals(0xFF, (c0 >>> 24) & 0xFF);
            assertEquals(0xFF, (c1 >>> 24) & 0xFF);
            assertNotEquals("Palette " + palette + " should vary across phases", c0, c1);
        }
    }

    @Test
    public void explosionStylesProduceExpectedParticleShapes() {
        TypingEffectsEngine engine = new TypingEffectsEngine(2.0f, new Random(3L));

        // Confetti only
        engine.setConfig(new TypingEffectsConfig(
                true, false, false, false, true, false, false,
                TypingEffectsConfig.PALETTE_RAINBOW,
                TypingEffectsConfig.EXPLOSION_STYLE_CONFETTI,
                TypingEffectsConfig.GLITTER_STYLE_SPARKLES,
                12, 360, 150, 12, 5, 100, 520, 100));
        engine.onPointerDown(0, 100f, 100f, 100L);
        engine.onPointerUp(0, 100f, 100f, 120L);
        assertEquals(12, engine.getParticles().size());
        for (TypingEffectsEngine.Particle p : engine.getParticles()) {
            assertEquals(TypingEffectsEngine.SHAPE_CONFETTI, p.shape);
        }

        // All-out show mix includes all 3 shapes
        engine.clearAll();
        engine.setConfig(new TypingEffectsConfig(
                true, false, false, false, true, false, true,
                TypingEffectsConfig.PALETTE_RAINBOW,
                TypingEffectsConfig.EXPLOSION_STYLE_ALL_OUT,
                TypingEffectsConfig.GLITTER_STYLE_MIXED,
                12, 360, 150, 18, 5, 100, 520, 100));
        engine.onPointerDown(0, 100f, 100f, 200L);
        engine.onPointerUp(0, 100f, 100f, 220L);
        boolean hasSpark = false;
        boolean hasStar = false;
        boolean hasConfetti = false;
        for (TypingEffectsEngine.Particle p : engine.getParticles()) {
            if (p.shape == TypingEffectsEngine.SHAPE_SPARK) hasSpark = true;
            if (p.shape == TypingEffectsEngine.SHAPE_STAR) hasStar = true;
            if (p.shape == TypingEffectsEngine.SHAPE_CONFETTI) hasConfetti = true;
        }
        assertTrue(hasSpark);
        assertTrue(hasStar);
        assertTrue(hasConfetti);
    }
}
