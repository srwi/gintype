package dev.jason.gboardpatches.extension.settings.search;

import dev.jason.gboardpatches.extension.R;

final class GboardSettingsSearchIndexData {
    private GboardSettingsSearchIndexData() {
    }

    static final String[][] PATHS = {
            {"dev.jason.gboardpatches.extension.nintype.GboardNintypeSettingsFeature"},
            {"dev.jason.gboardpatches.extension.matrixsuggestions.GboardMatrixSuggestionsSettingsFeature"}
    };

    static final int[][] ENTRIES = {
            {0, R.string.gboard_patches_nintype_title, R.string.gboard_patches_nintype_summary, 1},
            {1, R.string.gboard_patches_matrix_suggestions_title, R.string.gboard_patches_matrix_suggestions_summary, 1}
    };
}
