package dev.jason.gboardpatches.extension.nintype;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Loads Nintype wordlists and bigram tables from compressed or plain streams.
 * Ported from Kinetica's DictionaryLoader.kt.
 */
public final class NintypeDictionaryLoader {

    private NintypeDictionaryLoader() {}

    public static NintypeTrie loadTrieFromGzipStream(
            InputStream gzipStream,
            String lang,
            boolean hasDedicatedUmlautKeys
    ) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(gzipStream)) {
            return loadTrieFromStream(gis, lang, hasDedicatedUmlautKeys);
        }
    }

    public static NintypeTrie loadTrieFromStream(
            InputStream stream,
            String lang,
            boolean hasDedicatedUmlautKeys
    ) throws IOException {
        NintypeTrie trie = new NintypeTrie(300_000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int tabIdx = trimmed.indexOf('\t');
                if (tabIdx <= 0) {
                    tabIdx = trimmed.indexOf(' ');
                }
                String rawWord;
                int freq;
                int flag = 0;

                if (tabIdx > 0) {
                    rawWord = trimmed.substring(0, tabIdx).trim();
                    String rest = trimmed.substring(tabIdx + 1).trim();
                    int secondTab = rest.indexOf('\t');
                    if (secondTab < 0) secondTab = rest.indexOf(' ');
                    try {
                        if (secondTab > 0) {
                            freq = Integer.parseInt(rest.substring(0, secondTab).trim());
                            flag = Integer.parseInt(rest.substring(secondTab + 1).trim());
                        } else {
                            freq = Integer.parseInt(rest);
                        }
                    } catch (NumberFormatException e) {
                        freq = 1000;
                    }
                } else {
                    rawWord = trimmed;
                    freq = 1000;
                }

                insertWordIntoTrie(trie, rawWord, freq, flag == 1, lang, hasDedicatedUmlautKeys);
            }
        }
        trie.compact();
        return trie;
    }

    public static void insertWordIntoTrie(
            NintypeTrie trie,
            String rawWord,
            int freq,
            boolean isProperFlag,
            String lang,
            boolean hasDedicatedUmlautKeys
    ) {
        if (rawWord == null || rawWord.isEmpty()) return;
        boolean isProperNoun = isProperFlag || (rawWord.length() > 0 && Character.isUpperCase(rawWord.charAt(0)));
        String foldedKey = NintypeAccentFolder.fold(rawWord);
        if (foldedKey.isEmpty()) return;

        trie.insert(foldedKey, freq, rawWord, isProperNoun);

        if ("de".equalsIgnoreCase(lang)) {
            String digraphKey = NintypeAccentFolder.foldGermanDigraphs(rawWord, hasDedicatedUmlautKeys);
            if (digraphKey != null && !digraphKey.equals(foldedKey) && !digraphKey.isEmpty()) {
                trie.insert(digraphKey, freq, rawWord, isProperNoun);
            }
        }
    }

    public static NintypeBigramTable loadBigramsFromGzipStream(InputStream gzipStream) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(gzipStream)) {
            return loadBigramsFromStream(gis);
        }
    }

    public static NintypeBigramTable loadBigramsFromStream(InputStream stream) throws IOException {
        NintypeBigramTable table = new NintypeBigramTable();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) {
                    String prev = parts[0];
                    String next = parts[1];
                    int count;
                    try {
                        count = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        count = 100;
                    }
                    table.put(prev, next, count);
                }
            }
        }
        table.freeze();
        return table;
    }
}
