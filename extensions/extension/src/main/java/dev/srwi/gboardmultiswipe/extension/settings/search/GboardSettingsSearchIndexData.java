package dev.srwi.gboardmultiswipe.extension.settings.search;

import dev.srwi.gboardmultiswipe.extension.R;

final class GboardSettingsSearchIndexData {
    private GboardSettingsSearchIndexData() {
    }

    static final String[][] PATHS = {
            {"dev.srwi.gboardmultiswipe.extension.nintype.GboardNintypeSettingsFeature"},
            {"dev.srwi.gboardmultiswipe.extension.matrixsuggestions.GboardMatrixSuggestionsSettingsFeature"}
    };

    static final int[][] ENTRIES = {
            {0, R.string.gboard_patches_nintype_title, R.string.gboard_patches_nintype_summary, 1},
            {1, R.string.gboard_patches_matrix_suggestions_title, R.string.gboard_patches_matrix_suggestions_summary, 1}
    };
}
