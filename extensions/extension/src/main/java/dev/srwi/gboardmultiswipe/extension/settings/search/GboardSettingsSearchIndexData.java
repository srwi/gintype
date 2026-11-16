package dev.srwi.gboardmultiswipe.extension.settings.search;

import dev.srwi.gboardmultiswipe.extension.R;

final class GboardSettingsSearchIndexData {
    private GboardSettingsSearchIndexData() {
    }

    static final String[][] PATHS = {
            {"dev.srwi.gboardmultiswipe.extension.multiswipe.GboardMultiSwipeSettingsFeature"},
            {"dev.srwi.gboardmultiswipe.extension.multiswipe.GboardMultiSwipeSettingsFeature",
                    "dev.srwi.gboardmultiswipe.extension.multiswipe.GboardKineticaSettingsFeature"},
            {"dev.srwi.gboardmultiswipe.extension.multiswipe.GboardMultiSwipeSettingsFeature",
                    "dev.srwi.gboardmultiswipe.extension.multiswipe.GboardMultiSwipeDiagnosticsFeature"},
            {"dev.srwi.gboardmultiswipe.extension.thicctopbar.GboardThiccTopBarSettingsFeature"}
    };

    static final int[][] ENTRIES = {
            {0, R.string.gboard_patches_multiswipe_title, R.string.gboard_patches_multiswipe_summary, 1},
            {0, R.string.gboard_patches_multiswipe_enabled_title, R.string.gboard_patches_multiswipe_enabled_summary, 0},
            {0, R.string.gboard_patches_multiswipe_combine_enabled_title, R.string.gboard_patches_multiswipe_combine_enabled_summary, 0},
            {0, R.string.gboard_patches_multiswipe_combine_delay_title, R.string.gboard_patches_multiswipe_combine_delay_summary, 0},
            {0, R.string.gboard_patches_multiswipe_tap_to_multiswipe_enabled_title, R.string.gboard_patches_multiswipe_tap_to_multiswipe_enabled_summary, 0},
            {0, R.string.gboard_patches_multiswipe_tap_to_multiswipe_delay_title, R.string.gboard_patches_multiswipe_tap_to_multiswipe_delay_summary, 0},
            {0, R.string.gboard_patches_multiswipe_backspace_mode_title, R.string.gboard_patches_multiswipe_backspace_mode_summary, 0},
            {0, R.string.gboard_patches_multiswipe_auto_space_title, R.string.gboard_patches_multiswipe_auto_space_summary, 0},
            {0, R.string.gboard_patches_multiswipe_space_placement_title, R.string.gboard_patches_multiswipe_space_placement_summary, 0},
            {0, R.string.gboard_patches_multiswipe_auto_space_delay_title, R.string.gboard_patches_multiswipe_auto_space_delay_summary, 0},
            {0, R.string.gboard_patches_multiswipe_spacebar_timer_indicator_title, R.string.gboard_patches_multiswipe_spacebar_timer_indicator_summary, 0},
            {0, R.string.gboard_patches_multiswipe_word_ready_vibration_title, R.string.gboard_patches_multiswipe_word_ready_vibration_summary, 0},
            {1, R.string.gboard_patches_kinetica_title, R.string.gboard_patches_kinetica_summary, 1},
            {1, R.string.gboard_patches_multiswipe_beam_width_title, R.string.gboard_patches_multiswipe_beam_width_summary, 0},
            {1, R.string.gboard_patches_multiswipe_freq_weight_floor_title, R.string.gboard_patches_multiswipe_freq_weight_floor_summary, 0},
            {1, R.string.gboard_patches_multiswipe_geo_exponent_title, R.string.gboard_patches_multiswipe_geo_exponent_summary, 0},
            {1, R.string.gboard_patches_multiswipe_beam_freq_weight_title, R.string.gboard_patches_multiswipe_beam_freq_weight_summary, 0},
            {1, R.string.gboard_patches_kinetica_reset_title, R.string.gboard_patches_kinetica_reset_summary, 0},
            {2, R.string.gboard_patches_multiswipe_section_diagnostics, R.string.gboard_patches_multiswipe_diagnostics_summary, 1},
            {2, R.string.gboard_patches_multiswipe_trace_logging_title, R.string.gboard_patches_multiswipe_trace_logging_summary, 0},
            {2, R.string.gboard_patches_multiswipe_trace_export_title, R.string.gboard_patches_multiswipe_trace_export_summary, 0},
            {2, R.string.gboard_patches_multiswipe_trace_clear_title, R.string.gboard_patches_multiswipe_trace_clear_summary, 0},
            {3, R.string.gboard_patches_thicc_top_bar_title, R.string.gboard_patches_thicc_top_bar_summary, 1},
            {3, R.string.gboard_patches_thicc_top_bar_enable_title, R.string.gboard_patches_thicc_top_bar_summary, 0},
            {3, R.string.gboard_patches_thicc_top_bar_topbar_scale_title, R.string.gboard_patches_thicc_top_bar_topbar_scale_summary, 0},
            {3, R.string.gboard_patches_thicc_top_bar_swipe_sensitivity_title, R.string.gboard_patches_thicc_top_bar_swipe_sensitivity_summary, 0},
            {3, R.string.gboard_patches_thicc_top_bar_invert_swipe_title, R.string.gboard_patches_thicc_top_bar_invert_swipe_summary, 0}
    };
}
