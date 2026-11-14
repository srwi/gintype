package dev.srwi.gboardmultiswipe.extension.settings.search;

import dev.srwi.gboardmultiswipe.extension.R;

final class GboardSettingsSearchIndexData {
    private GboardSettingsSearchIndexData() {
    }

    static final String[][] PATHS = {
            {"dev.srwi.gboardmultiswipe.extension.multiswipe.GboardMultiSwipeSettingsFeature"},
            {"dev.srwi.gboardmultiswipe.extension.thicctopbar.GboardThiccTopBarSettingsFeature"}
    };

    static final int[][] ENTRIES = {
            {0, R.string.gboard_patches_multiswipe_title, R.string.gboard_patches_multiswipe_summary, 1},
            {1, R.string.gboard_patches_thicc_top_bar_title, R.string.gboard_patches_thicc_top_bar_summary, 1}
    };
}
