package dev.srwi.gboardmultiswipe.extension.typingeffects;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import dev.srwi.gboardmultiswipe.extension.R;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesFeatureAvailability;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesIcons;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettings;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract;
import dev.srwi.gboardmultiswipe.extension.settings.GboardSettingsText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen for Typing Effects: individual effect toggles, color palette & particle style
 * pickers, full fine-tuning sliders, and reset-to-defaults.
 */
public final class GboardTypingEffectsSettingsFeature implements GboardPatchesSettingsContract.Feature {

    private static final String TAG = "GboardPatches";

    private static final int TRAIL_WIDTH_STEP_DP = 1;
    private static final int TRAIL_FADE_STEP_MS = 20;
    private static final int RAINBOW_SPEED_STEP_PERCENT = 10;
    private static final int PARTICLE_COUNT_STEP = 2;
    private static final int GLITTER_DENSITY_STEP = 1;
    private static final int PARTICLE_SIZE_STEP_PERCENT = 10;
    private static final int PARTICLE_LIFETIME_STEP_MS = 40;
    private static final int PARTICLE_SPEED_STEP_PERCENT = 10;

    private interface IntFormatter {
        String format(int value);
    }

    private interface IntSaver {
        boolean save(int value);
    }

    private final String title;
    private final String summary;
    private final String sectionEffects;
    private final String sectionStyle;
    private final String sectionTrail;
    private final String sectionParticles;
    private final String enabledTitle;
    private final String enabledSummary;
    private final String rainbowTrailTitle;
    private final String rainbowTrailSummary;
    private final String trailGlowTitle;
    private final String trailGlowSummary;
    private final String glitterTitle;
    private final String glitterSummary;
    private final String tapExplosionTitle;
    private final String tapExplosionSummary;
    private final String releaseExplosionTitle;
    private final String releaseExplosionSummary;
    private final String shockwaveRingTitle;
    private final String shockwaveRingSummary;
    private final String paletteTitle;
    private final String paletteSummary;
    private final String[] paletteLabels;
    private final String[] paletteValues;
    private final String explosionStyleTitle;
    private final String explosionStyleSummary;
    private final String[] explosionStyleLabels;
    private final String[] explosionStyleValues;
    private final String glitterStyleTitle;
    private final String glitterStyleSummary;
    private final String[] glitterStyleLabels;
    private final String[] glitterStyleValues;
    private final String trailWidthTitle;
    private final String trailWidthSummary;
    private final String trailFadeTitle;
    private final String trailFadeSummary;
    private final String rainbowSpeedTitle;
    private final String rainbowSpeedSummary;
    private final String particleCountTitle;
    private final String particleCountSummary;
    private final String glitterDensityTitle;
    private final String glitterDensitySummary;
    private final String particleSizeTitle;
    private final String particleSizeSummary;
    private final String particleLifetimeTitle;
    private final String particleLifetimeSummary;
    private final String particleSpeedTitle;
    private final String particleSpeedSummary;
    private final String defaultValueTemplate;
    private final String resetTitle;
    private final String resetSummary;
    private final String resetConfirmTitle;
    private final String resetConfirmMessage;
    private final String resetDone;
    private final String errorTitle;
    private final String errorSummary;

    public GboardTypingEffectsSettingsFeature(Context context) {
        title = text(context, R.string.gboard_patches_typing_effects_title);
        summary = text(context, R.string.gboard_patches_typing_effects_summary);
        sectionEffects = text(context, R.string.gboard_patches_typing_effects_section_effects);
        sectionStyle = text(context, R.string.gboard_patches_typing_effects_section_style);
        sectionTrail = text(context, R.string.gboard_patches_typing_effects_section_trail);
        sectionParticles = text(context, R.string.gboard_patches_typing_effects_section_particles);
        enabledTitle = text(context, R.string.gboard_patches_typing_effects_enabled_title);
        enabledSummary = text(context, R.string.gboard_patches_typing_effects_enabled_summary);
        rainbowTrailTitle = text(context, R.string.gboard_patches_typing_effects_rainbow_trail_title);
        rainbowTrailSummary = text(context, R.string.gboard_patches_typing_effects_rainbow_trail_summary);
        trailGlowTitle = text(context, R.string.gboard_patches_typing_effects_trail_glow_title);
        trailGlowSummary = text(context, R.string.gboard_patches_typing_effects_trail_glow_summary);
        glitterTitle = text(context, R.string.gboard_patches_typing_effects_glitter_title);
        glitterSummary = text(context, R.string.gboard_patches_typing_effects_glitter_summary);
        tapExplosionTitle = text(context, R.string.gboard_patches_typing_effects_tap_explosion_title);
        tapExplosionSummary = text(context, R.string.gboard_patches_typing_effects_tap_explosion_summary);
        releaseExplosionTitle =
                text(context, R.string.gboard_patches_typing_effects_release_explosion_title);
        releaseExplosionSummary =
                text(context, R.string.gboard_patches_typing_effects_release_explosion_summary);
        shockwaveRingTitle =
                text(context, R.string.gboard_patches_typing_effects_shockwave_ring_title);
        shockwaveRingSummary =
                text(context, R.string.gboard_patches_typing_effects_shockwave_ring_summary);

        paletteTitle = text(context, R.string.gboard_patches_typing_effects_palette_title);
        paletteSummary = text(context, R.string.gboard_patches_typing_effects_palette_summary);
        paletteLabels = new String[] {
                text(context, R.string.gboard_patches_typing_effects_palette_rainbow),
                text(context, R.string.gboard_patches_typing_effects_palette_neon),
                text(context, R.string.gboard_patches_typing_effects_palette_fire),
                text(context, R.string.gboard_patches_typing_effects_palette_galaxy),
                text(context, R.string.gboard_patches_typing_effects_palette_pastel)
        };
        paletteValues = new String[] {
                TypingEffectsConfig.PALETTE_RAINBOW,
                TypingEffectsConfig.PALETTE_NEON,
                TypingEffectsConfig.PALETTE_FIRE,
                TypingEffectsConfig.PALETTE_GALAXY,
                TypingEffectsConfig.PALETTE_PASTEL
        };

        explosionStyleTitle =
                text(context, R.string.gboard_patches_typing_effects_explosion_style_title);
        explosionStyleSummary =
                text(context, R.string.gboard_patches_typing_effects_explosion_style_summary);
        explosionStyleLabels = new String[] {
                text(context, R.string.gboard_patches_typing_effects_explosion_style_sparks_ring),
                text(context, R.string.gboard_patches_typing_effects_explosion_style_sparkles),
                text(context, R.string.gboard_patches_typing_effects_explosion_style_confetti),
                text(context, R.string.gboard_patches_typing_effects_explosion_style_all_out)
        };
        explosionStyleValues = new String[] {
                TypingEffectsConfig.EXPLOSION_STYLE_SPARKS_RING,
                TypingEffectsConfig.EXPLOSION_STYLE_SPARKLES,
                TypingEffectsConfig.EXPLOSION_STYLE_CONFETTI,
                TypingEffectsConfig.EXPLOSION_STYLE_ALL_OUT
        };

        glitterStyleTitle =
                text(context, R.string.gboard_patches_typing_effects_glitter_style_title);
        glitterStyleSummary =
                text(context, R.string.gboard_patches_typing_effects_glitter_style_summary);
        glitterStyleLabels = new String[] {
                text(context, R.string.gboard_patches_typing_effects_glitter_style_sparkles),
                text(context, R.string.gboard_patches_typing_effects_glitter_style_sparks),
                text(context, R.string.gboard_patches_typing_effects_glitter_style_confetti),
                text(context, R.string.gboard_patches_typing_effects_glitter_style_mixed)
        };
        glitterStyleValues = new String[] {
                TypingEffectsConfig.GLITTER_STYLE_SPARKLES,
                TypingEffectsConfig.GLITTER_STYLE_SPARKS,
                TypingEffectsConfig.GLITTER_STYLE_CONFETTI,
                TypingEffectsConfig.GLITTER_STYLE_MIXED
        };

        trailWidthTitle = text(context, R.string.gboard_patches_typing_effects_trail_width_title);
        trailWidthSummary = text(context, R.string.gboard_patches_typing_effects_trail_width_summary);
        trailFadeTitle = text(context, R.string.gboard_patches_typing_effects_trail_fade_title);
        trailFadeSummary = text(context, R.string.gboard_patches_typing_effects_trail_fade_summary);
        rainbowSpeedTitle = text(context, R.string.gboard_patches_typing_effects_rainbow_speed_title);
        rainbowSpeedSummary =
                text(context, R.string.gboard_patches_typing_effects_rainbow_speed_summary);
        particleCountTitle =
                text(context, R.string.gboard_patches_typing_effects_particle_count_title);
        particleCountSummary =
                text(context, R.string.gboard_patches_typing_effects_particle_count_summary);
        glitterDensityTitle =
                text(context, R.string.gboard_patches_typing_effects_glitter_density_title);
        glitterDensitySummary =
                text(context, R.string.gboard_patches_typing_effects_glitter_density_summary);
        particleSizeTitle = text(context, R.string.gboard_patches_typing_effects_particle_size_title);
        particleSizeSummary =
                text(context, R.string.gboard_patches_typing_effects_particle_size_summary);
        particleLifetimeTitle =
                text(context, R.string.gboard_patches_typing_effects_particle_lifetime_title);
        particleLifetimeSummary =
                text(context, R.string.gboard_patches_typing_effects_particle_lifetime_summary);
        particleSpeedTitle =
                text(context, R.string.gboard_patches_typing_effects_particle_speed_title);
        particleSpeedSummary =
                text(context, R.string.gboard_patches_typing_effects_particle_speed_summary);
        defaultValueTemplate = text(context, R.string.gboard_patches_multiswipe_value_default);
        resetTitle = text(context, R.string.gboard_patches_typing_effects_reset_title);
        resetSummary = text(context, R.string.gboard_patches_typing_effects_reset_summary);
        resetConfirmTitle = text(context, R.string.gboard_patches_typing_effects_reset_confirm_title);
        resetConfirmMessage =
                text(context, R.string.gboard_patches_typing_effects_reset_confirm_message);
        resetDone = text(context, R.string.gboard_patches_typing_effects_reset_done);
        errorTitle = text(context, R.string.gboard_patches_typing_effects_error_title);
        errorSummary = text(context, R.string.gboard_patches_typing_effects_error_summary);
    }

    @Override
    public String getEntryTitle() {
        return title;
    }

    @Override
    public String getEntrySummary() {
        return summary;
    }

    @Override
    public int getEntryIcon() {
        return GboardPatchesIcons.SPARKLE;
    }

    @Override
    public String getEntryTone() {
        return "amber";
    }

    @Override
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context, GboardPatchesFeatureAvailability.FEATURE_TYPING_EFFECTS);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        try {
            if (host == null || host.getContext() == null) {
                return buildErrorScreen();
            }
            SharedPreferences preferences = GboardPatchesSettings.preferences(host.getContext());
            GboardTypingEffectsSettings.ensureDefaults(preferences);
            TypingEffectsConfig cfg = GboardTypingEffectsSettings.readConfig(preferences);

            boolean master = cfg.enabled;
            boolean anyExplosion = master && (cfg.tapExplosionEnabled || cfg.releaseExplosionEnabled);
            boolean anyParticle = master && (cfg.glitterEnabled || cfg.tapExplosionEnabled || cfg.releaseExplosionEnabled);

            // Section 1: Individual Effect Toggles
            List<GboardPatchesSettingsContract.Row> effectRows = new ArrayList<GboardPatchesSettingsContract.Row>(7);
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    enabledTitle,
                    enabledSummary,
                    master,
                    true,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeEnabled(preferences, checked))));
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    rainbowTrailTitle,
                    rainbowTrailSummary,
                    cfg.rainbowTrailEnabled,
                    master,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeRainbowTrailEnabled(preferences, checked))));
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    trailGlowTitle,
                    trailGlowSummary,
                    cfg.trailGlowEnabled,
                    master && cfg.rainbowTrailEnabled,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeTrailGlowEnabled(preferences, checked))));
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    glitterTitle,
                    glitterSummary,
                    cfg.glitterEnabled,
                    master,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeGlitterEnabled(preferences, checked))));
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    tapExplosionTitle,
                    tapExplosionSummary,
                    cfg.tapExplosionEnabled,
                    master,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeTapExplosionEnabled(preferences, checked))));
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    releaseExplosionTitle,
                    releaseExplosionSummary,
                    cfg.releaseExplosionEnabled,
                    master,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeReleaseExplosionEnabled(preferences, checked))));
            effectRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    shockwaveRingTitle,
                    shockwaveRingSummary,
                    cfg.shockwaveRingEnabled,
                    anyExplosion,
                    checked -> saveBoolAndRefresh(host,
                            GboardTypingEffectsSettings.writeShockwaveRingEnabled(preferences, checked))));

            // Section 2: Look & Style Pickers
            List<GboardPatchesSettingsContract.Row> styleRows = new ArrayList<GboardPatchesSettingsContract.Row>(3);
            styleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    paletteTitle,
                    paletteSummary,
                    labelForValue(paletteLabels, paletteValues, cfg.colorPalette),
                    master,
                    () -> GboardPatchesSettingsContract.showSingleChoiceDialog(
                            host,
                            paletteTitle,
                            paletteLabels,
                            paletteValues,
                            cfg.colorPalette,
                            selected -> saveAndRefresh(host,
                                    GboardTypingEffectsSettings.writeColorPalette(preferences, selected)))));
            styleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    explosionStyleTitle,
                    explosionStyleSummary,
                    labelForValue(explosionStyleLabels, explosionStyleValues, cfg.explosionStyle),
                    anyExplosion,
                    () -> GboardPatchesSettingsContract.showSingleChoiceDialog(
                            host,
                            explosionStyleTitle,
                            explosionStyleLabels,
                            explosionStyleValues,
                            cfg.explosionStyle,
                            selected -> saveAndRefresh(host,
                                    GboardTypingEffectsSettings.writeExplosionStyle(preferences, selected)))));
            styleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    glitterStyleTitle,
                    glitterStyleSummary,
                    labelForValue(glitterStyleLabels, glitterStyleValues, cfg.glitterStyle),
                    master && cfg.glitterEnabled,
                    () -> GboardPatchesSettingsContract.showSingleChoiceDialog(
                            host,
                            glitterStyleTitle,
                            glitterStyleLabels,
                            glitterStyleValues,
                            cfg.glitterStyle,
                            selected -> saveAndRefresh(host,
                                    GboardTypingEffectsSettings.writeGlitterStyle(preferences, selected)))));

            // Section 3: Swipe Trail Sliders
            boolean trailActive = master && cfg.rainbowTrailEnabled;
            List<GboardPatchesSettingsContract.Row> trailRows = new ArrayList<GboardPatchesSettingsContract.Row>(3);
            trailRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    trailWidthTitle,
                    trailWidthSummary,
                    formatUnit(cfg.trailWidthDp, GboardTypingEffectsSettings.DEFAULT_TRAIL_WIDTH_DP, "dp"),
                    trailActive,
                    () -> showIntSliderDialog(
                            host,
                            trailWidthTitle,
                            trailWidthSummary,
                            GboardTypingEffectsSettings.MIN_TRAIL_WIDTH_DP,
                            GboardTypingEffectsSettings.MAX_TRAIL_WIDTH_DP,
                            TRAIL_WIDTH_STEP_DP,
                            GboardTypingEffectsSettings.DEFAULT_TRAIL_WIDTH_DP,
                            cfg.trailWidthDp,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_TRAIL_WIDTH_DP, "dp"),
                            v -> GboardTypingEffectsSettings.writeTrailWidthDp(preferences, v))));
            trailRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    trailFadeTitle,
                    trailFadeSummary,
                    formatUnit(cfg.trailFadeMs, GboardTypingEffectsSettings.DEFAULT_TRAIL_FADE_MS, "ms"),
                    trailActive,
                    () -> showIntSliderDialog(
                            host,
                            trailFadeTitle,
                            trailFadeSummary,
                            GboardTypingEffectsSettings.MIN_TRAIL_FADE_MS,
                            GboardTypingEffectsSettings.MAX_TRAIL_FADE_MS,
                            TRAIL_FADE_STEP_MS,
                            GboardTypingEffectsSettings.DEFAULT_TRAIL_FADE_MS,
                            cfg.trailFadeMs,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_TRAIL_FADE_MS, "ms"),
                            v -> GboardTypingEffectsSettings.writeTrailFadeMs(preferences, v))));
            trailRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    rainbowSpeedTitle,
                    rainbowSpeedSummary,
                    formatUnit(cfg.rainbowSpeedPercent, GboardTypingEffectsSettings.DEFAULT_RAINBOW_SPEED_PERCENT, "%"),
                    master,
                    () -> showIntSliderDialog(
                            host,
                            rainbowSpeedTitle,
                            rainbowSpeedSummary,
                            GboardTypingEffectsSettings.MIN_RAINBOW_SPEED_PERCENT,
                            GboardTypingEffectsSettings.MAX_RAINBOW_SPEED_PERCENT,
                            RAINBOW_SPEED_STEP_PERCENT,
                            GboardTypingEffectsSettings.DEFAULT_RAINBOW_SPEED_PERCENT,
                            cfg.rainbowSpeedPercent,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_RAINBOW_SPEED_PERCENT, "%"),
                            v -> GboardTypingEffectsSettings.writeRainbowSpeedPercent(preferences, v))));

            // Section 4: Particles & Explosions Sliders
            List<GboardPatchesSettingsContract.Row> particleRows = new ArrayList<GboardPatchesSettingsContract.Row>(5);
            particleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    particleCountTitle,
                    particleCountSummary,
                    formatUnit(cfg.particleCount, GboardTypingEffectsSettings.DEFAULT_PARTICLE_COUNT, ""),
                    anyExplosion,
                    () -> showIntSliderDialog(
                            host,
                            particleCountTitle,
                            particleCountSummary,
                            GboardTypingEffectsSettings.MIN_PARTICLE_COUNT,
                            GboardTypingEffectsSettings.MAX_PARTICLE_COUNT,
                            PARTICLE_COUNT_STEP,
                            GboardTypingEffectsSettings.DEFAULT_PARTICLE_COUNT,
                            cfg.particleCount,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_PARTICLE_COUNT, ""),
                            v -> GboardTypingEffectsSettings.writeParticleCount(preferences, v))));
            particleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    glitterDensityTitle,
                    glitterDensitySummary,
                    formatUnit(cfg.glitterDensity, GboardTypingEffectsSettings.DEFAULT_GLITTER_DENSITY, "/10"),
                    master && cfg.glitterEnabled,
                    () -> showIntSliderDialog(
                            host,
                            glitterDensityTitle,
                            glitterDensitySummary,
                            GboardTypingEffectsSettings.MIN_GLITTER_DENSITY,
                            GboardTypingEffectsSettings.MAX_GLITTER_DENSITY,
                            GLITTER_DENSITY_STEP,
                            GboardTypingEffectsSettings.DEFAULT_GLITTER_DENSITY,
                            cfg.glitterDensity,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_GLITTER_DENSITY, "/10"),
                            v -> GboardTypingEffectsSettings.writeGlitterDensity(preferences, v))));
            particleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    particleSizeTitle,
                    particleSizeSummary,
                    formatUnit(cfg.particleSizePercent, GboardTypingEffectsSettings.DEFAULT_PARTICLE_SIZE_PERCENT, "%"),
                    anyParticle,
                    () -> showIntSliderDialog(
                            host,
                            particleSizeTitle,
                            particleSizeSummary,
                            GboardTypingEffectsSettings.MIN_PARTICLE_SIZE_PERCENT,
                            GboardTypingEffectsSettings.MAX_PARTICLE_SIZE_PERCENT,
                            PARTICLE_SIZE_STEP_PERCENT,
                            GboardTypingEffectsSettings.DEFAULT_PARTICLE_SIZE_PERCENT,
                            cfg.particleSizePercent,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_PARTICLE_SIZE_PERCENT, "%"),
                            v -> GboardTypingEffectsSettings.writeParticleSizePercent(preferences, v))));
            particleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    particleLifetimeTitle,
                    particleLifetimeSummary,
                    formatUnit(cfg.particleLifetimeMs, GboardTypingEffectsSettings.DEFAULT_PARTICLE_LIFETIME_MS, "ms"),
                    anyParticle,
                    () -> showIntSliderDialog(
                            host,
                            particleLifetimeTitle,
                            particleLifetimeSummary,
                            GboardTypingEffectsSettings.MIN_PARTICLE_LIFETIME_MS,
                            GboardTypingEffectsSettings.MAX_PARTICLE_LIFETIME_MS,
                            PARTICLE_LIFETIME_STEP_MS,
                            GboardTypingEffectsSettings.DEFAULT_PARTICLE_LIFETIME_MS,
                            cfg.particleLifetimeMs,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_PARTICLE_LIFETIME_MS, "ms"),
                            v -> GboardTypingEffectsSettings.writeParticleLifetimeMs(preferences, v))));
            particleRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    particleSpeedTitle,
                    particleSpeedSummary,
                    formatUnit(cfg.particleSpeedPercent, GboardTypingEffectsSettings.DEFAULT_PARTICLE_SPEED_PERCENT, "%"),
                    anyParticle,
                    () -> showIntSliderDialog(
                            host,
                            particleSpeedTitle,
                            particleSpeedSummary,
                            GboardTypingEffectsSettings.MIN_PARTICLE_SPEED_PERCENT,
                            GboardTypingEffectsSettings.MAX_PARTICLE_SPEED_PERCENT,
                            PARTICLE_SPEED_STEP_PERCENT,
                            GboardTypingEffectsSettings.DEFAULT_PARTICLE_SPEED_PERCENT,
                            cfg.particleSpeedPercent,
                            v -> formatUnit(v, GboardTypingEffectsSettings.DEFAULT_PARTICLE_SPEED_PERCENT, "%"),
                            v -> GboardTypingEffectsSettings.writeParticleSpeedPercent(preferences, v))));

            // Section 5: Reset to defaults
            List<GboardPatchesSettingsContract.Row> resetRows = Collections.singletonList(
                    new GboardPatchesSettingsContract.DangerRow(
                            resetTitle,
                            resetSummary,
                            true,
                            () -> {
                                GboardTypingEffectsSettings.resetToDefaults(preferences);
                                GboardPatchesSettingsContract.refresh(host);
                                GboardPatchesSettingsContract.showMessage(host, resetDone);
                            },
                            resetConfirmTitle,
                            resetConfirmMessage));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    null,
                    title,
                    summary,
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section(sectionEffects, effectRows),
                            new GboardPatchesSettingsContract.Section(sectionStyle, styleRows),
                            new GboardPatchesSettingsContract.Section(sectionTrail, trailRows),
                            new GboardPatchesSettingsContract.Section(sectionParticles, particleRows),
                            new GboardPatchesSettingsContract.Section(
                                    null,
                                    null,
                                    GboardPatchesSettingsContract.SectionStyle.ADVANCED,
                                    resetRows)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable throwable) {
            try {
                Log.w(TAG, "Failed to render Typing Effects settings screen", throwable);
            } catch (Throwable ignored) {
            }
            return buildErrorScreen();
        }
    }

    private void showIntSliderDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            String dialogTitle,
            String description,
            int min,
            int max,
            int step,
            int defaultValue,
            int currentValue,
            IntFormatter formatter,
            IntSaver saver) {
        int totalSteps = Math.max(1, (max - min) / step);
        int initialProgress = sliderStepForInt(currentValue, min, max, step);
        int defaultProgress = sliderStepForInt(defaultValue, min, max, step);
        GboardPatchesSettingsContract.showSliderDialog(
                host,
                dialogTitle,
                description,
                totalSteps,
                initialProgress,
                defaultProgress,
                Integer.toString(min),
                Integer.toString(max),
                p -> formatter.format(intValueForSliderStep(p, min, max, step)),
                p -> {
                    int value = intValueForSliderStep(p, min, max, step);
                    if (!saver.save(value)) {
                        throw new IllegalStateException("Failed to persist " + dialogTitle);
                    }
                    GboardPatchesSettingsContract.refresh(host);
                });
    }

    private String formatUnit(int value, int defaultValue, String unit) {
        String formatted;
        if (unit.isEmpty()) {
            formatted = Integer.toString(value);
        } else if ("%".equals(unit) || "/10".equals(unit)) {
            formatted = value + unit;
        } else {
            formatted = value + " " + unit;
        }
        if (value == defaultValue) {
            return String.format(Locale.US, defaultValueTemplate, formatted);
        }
        return formatted;
    }

    private static String labelForValue(String[] labels, String[] values, String currentValue) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentValue)) {
                return labels[i];
            }
        }
        return labels[0];
    }

    private static void saveBoolAndRefresh(
            GboardPatchesSettingsContract.FeatureHost host,
            boolean ok) {
        if (!ok) {
            throw new IllegalStateException("Failed to persist Typing Effects toggle");
        }
        GboardPatchesSettingsContract.refresh(host);
    }

    private static void saveAndRefresh(
            GboardPatchesSettingsContract.FeatureHost host,
            boolean ok) {
        if (!ok) {
            throw new IllegalStateException("Failed to persist Typing Effects setting");
        }
        GboardPatchesSettingsContract.refresh(host);
    }

    static int sliderStepForInt(int value, int min, int max, int step) {
        int clamped = GboardTypingEffectsSettings.clamp(value, min, max);
        int totalSteps = Math.max(1, (max - min) / step);
        return GboardTypingEffectsSettings.clamp(Math.round((float) (clamped - min) / step), 0, totalSteps);
    }

    static int intValueForSliderStep(int progress, int min, int max, int step) {
        int totalSteps = Math.max(1, (max - min) / step);
        int clampedStep = GboardTypingEffectsSettings.clamp(progress, 0, totalSteps);
        return GboardTypingEffectsSettings.clamp(min + clampedStep * step, min, max);
    }

    private GboardPatchesSettingsContract.Screen buildErrorScreen() {
        return new GboardPatchesSettingsContract.Screen(
                title,
                null,
                title,
                summary,
                Collections.singletonList(new GboardPatchesSettingsContract.StatusBlock(
                        errorTitle,
                        errorSummary,
                        GboardPatchesSettingsContract.StatusTone.WARNING)),
                Collections.emptyList());
    }

    private static String text(Context context, int id) {
        return GboardSettingsText.get(context, id);
    }
}
