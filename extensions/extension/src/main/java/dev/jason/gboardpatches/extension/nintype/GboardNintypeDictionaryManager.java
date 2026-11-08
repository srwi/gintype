package dev.jason.gboardpatches.extension.nintype;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.UserDictionary;
import android.util.Log;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Nintype prefix Tries and BigramTables per active Gboard language and bridges
 * candidate validation with Gboard's native Delight5Facilitator dictionary.
 */
public final class GboardNintypeDictionaryManager {

    public static final class LanguagePack {
        public final String lang;
        public final NintypeTrie trie;
        public final NintypeBigramTable bigrams;

        public LanguagePack(String lang, NintypeTrie trie, NintypeBigramTable bigrams) {
            this.lang = lang;
            this.trie = trie;
            this.bigrams = bigrams;
        }
    }

    private static final String DELIGHT5_FACILITATOR_CLASS =
            "com.google.android.apps.inputmethod.libs.delight5.Delight5Facilitator";

    private static final Map<String, LanguagePack> PACKS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final ExecutorService LOADER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GboardNintypeDictLoader");
        t.setDaemon(true);
        return t;
    });

    private static volatile Class<?> facilitatorClass;
    private static volatile Method getFacilitatorNoArgMethod;
    private static volatile Method getFacilitatorContextMethod;
    private static volatile Method getLocaleMethod;
    private static volatile Field decoderWrapperField;
    private static volatile Method checkWordsMethod;
    private static volatile boolean reflectionProbed = false;

    private GboardNintypeDictionaryManager() {}

    public static String detectActiveLanguage(Context context) {
        try {
            Object facilitator = getDelight5Facilitator(context);
            if (facilitator != null && getLocaleMethod != null) {
                Object localeObj = getLocaleMethod.invoke(facilitator);
                if (localeObj instanceof Locale) {
                    String lang = ((Locale) localeObj).getLanguage();
                    if (lang != null && !lang.isEmpty()) {
                        String norm = lang.toLowerCase(Locale.ROOT);
                        if ("de".equals(norm) || "en".equals(norm)) {
                            return norm;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Locale def = Locale.getDefault();
            if (def != null && "de".equalsIgnoreCase(def.getLanguage())) {
                return "de";
            }
        } catch (Throwable ignored) {
        }
        return "de";
    }

    public static LanguagePack getOrScheduleLoad(Context context, String lang, boolean hasDedicatedUmlauts) {
        String key = normalizeSupportedLang(lang);
        LanguagePack existing = PACKS.get(key);
        if (existing != null) {
            return existing;
        }
        if (context == null) return null;

        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        if ( Boolean.TRUE.equals(LOADING.putIfAbsent(key, Boolean.TRUE))) {
            return null;
        }

        LOADER_EXECUTOR.execute(() -> {
            try {
                AssetManager assets = appContext.getAssets();
                NintypeTrie trie;
                try (InputStream ws = assets.open("nintype/" + key + "_wordlist.txt.gz")) {
                    trie = NintypeDictionaryLoader.loadTrieFromGzipStream(ws, key, hasDedicatedUmlauts);
                }
                loadUserDictionaryIntoTrie(appContext, trie, key);
                NintypeBigramTable bigrams;
                try (InputStream bs = assets.open("nintype/" + key + "_bigrams.txt.gz")) {
                    bigrams = NintypeDictionaryLoader.loadBigramsFromGzipStream(bs);
                }
                PACKS.put(key, new LanguagePack(key, trie, bigrams));
            } catch (Throwable ignored) {
            } finally {
                LOADING.remove(key);
            }
        });
        return PACKS.get(key);
    }

    public static LanguagePack loadBlockingIfNeeded(Context context, String lang, boolean hasDedicatedUmlauts) {
        String key = normalizeSupportedLang(lang);
        LanguagePack existing = PACKS.get(key);
        if (existing != null) return existing;
        if (context == null) return null;

        synchronized (GboardNintypeDictionaryManager.class) {
            existing = PACKS.get(key);
            if (existing != null) return existing;
            try {
                AssetManager assets = context.getAssets();
                NintypeTrie trie;
                try (InputStream ws = assets.open("nintype/" + key + "_wordlist.txt.gz")) {
                    trie = NintypeDictionaryLoader.loadTrieFromGzipStream(ws, key, hasDedicatedUmlauts);
                }
                loadUserDictionaryIntoTrie(context, trie, key);
                NintypeBigramTable bigrams;
                try (InputStream bs = assets.open("nintype/" + key + "_bigrams.txt.gz")) {
                    bigrams = NintypeDictionaryLoader.loadBigramsFromGzipStream(bs);
                }
                LanguagePack pack = new LanguagePack(key, trie, bigrams);
                PACKS.put(key, pack);
                return pack;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static void loadUserDictionaryIntoTrie(Context context, NintypeTrie trie, String lang) {
        if (context == null || trie == null) return;
        try {
            ContentResolver cr = context.getContentResolver();
            if (cr == null) return;
            Uri uri = UserDictionary.Words.CONTENT_URI;
            String[] projection = new String[] {
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY,
                    UserDictionary.Words.LOCALE
            };
            try (Cursor cursor = cr.query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int wordCol = cursor.getColumnIndex(UserDictionary.Words.WORD);
                    int freqCol = cursor.getColumnIndex(UserDictionary.Words.FREQUENCY);
                    do {
                        String word = (wordCol >= 0) ? cursor.getString(wordCol) : null;
                        if (word != null && word.length() >= 2) {
                            int rawFreq = (freqCol >= 0) ? cursor.getInt(freqCol) : 128;
                            int boostedFreq = Math.max(25_000, rawFreq * 350);
                            boolean isProper = Character.isUpperCase(word.charAt(0));
                            NintypeDictionaryLoader.insertWordIntoTrie(
                                    trie, word, boostedFreq, isProper, lang, false);
                        }
                    } while (cursor.moveToNext());
                }
            }
        } catch (Throwable t) {
            Log.w("GboardNintypeDict", "UserDictionary query skipped: " + t.getMessage());
        }
    }

    /**
     * Queries Gboard's live Delight5 native decoder (checkWords) to verify which candidate
     * words are present in Gboard's active language model or user dictionary.
     */
    public static boolean[] checkWordsInGboard(Context context, List<String> words) {
        if (words == null || words.isEmpty()) return null;
        try {
            Object facilitator = getDelight5Facilitator(context);
            if (facilitator == null) return null;

            if (decoderWrapperField == null || checkWordsMethod == null) {
                synchronized (GboardNintypeDictionaryManager.class) {
                    if (decoderWrapperField == null || checkWordsMethod == null) {
                        for (Field f : facilitator.getClass().getDeclaredFields()) {
                            f.setAccessible(true);
                            Object val = f.get(facilitator);
                            if (val == null) continue;
                            for (Method m : val.getClass().getDeclaredMethods()) {
                                Class<?>[] params = m.getParameterTypes();
                                if (params.length == 1
                                        && List.class.isAssignableFrom(params[0])
                                        && m.getReturnType() == boolean[].class) {
                                    m.setAccessible(true);
                                    decoderWrapperField = f;
                                    checkWordsMethod = m;
                                    break;
                                }
                            }
                            if (checkWordsMethod != null) break;
                        }
                    }
                }
            }

            if (decoderWrapperField != null && checkWordsMethod != null) {
                Object wrapper = decoderWrapperField.get(facilitator);
                if (wrapper != null) {
                    Object res = checkWordsMethod.invoke(wrapper, words);
                    if (res instanceof boolean[]) {
                        return (boolean[]) res;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Learns a committed word into the active NintypeTrie & BigramTable so user-typed
     * vocabulary is immediately available for subsequent 2-finger swipes.
     */
    public static void recordCommittedWord(String prevWord, String committedWord, String lang) {
        if (committedWord == null || committedWord.length() < 2) return;
        String key = normalizeSupportedLang(lang);
        LanguagePack pack = PACKS.get(key);
        if (pack == null) return;

        int existingFreq = pack.trie.lookupFrequency(committedWord);
        int boostedFreq = Math.max(existingFreq + 250, 15_000);
        NintypeDictionaryLoader.insertWordIntoTrie(
                pack.trie, committedWord, boostedFreq, false, key, false);
        if (prevWord != null && !prevWord.isEmpty()) {
            pack.bigrams.put(prevWord, committedWord, 50);
        }
    }

    private static String normalizeSupportedLang(String lang) {
        if (lang == null) return "de";
        String lower = lang.toLowerCase(Locale.ROOT);
        if (lower.startsWith("en")) return "en";
        return "de";
    }

    private static Object getDelight5Facilitator(Context context) {
        probeFacilitatorReflection(context);
        try {
            if (getFacilitatorNoArgMethod != null) {
                Object inst = getFacilitatorNoArgMethod.invoke(null);
                if (inst != null) return inst;
            }
            if (getFacilitatorContextMethod != null && context != null) {
                return getFacilitatorContextMethod.invoke(null, context);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void probeFacilitatorReflection(Context context) {
        if (reflectionProbed) return;
        synchronized (GboardNintypeDictionaryManager.class) {
            if (reflectionProbed) return;
            try {
                ClassLoader cl = context != null
                        ? context.getClassLoader()
                        : GboardNintypeDictionaryManager.class.getClassLoader();
                Class<?> cls = Class.forName(DELIGHT5_FACILITATOR_CLASS, false, cl);
                facilitatorClass = cls;
                for (Method m : cls.getDeclaredMethods()) {
                    int mods = m.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mods) && m.getReturnType() == cls) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 0) {
                            m.setAccessible(true);
                            getFacilitatorNoArgMethod = m;
                        } else if (params.length == 1 && Context.class.isAssignableFrom(params[0])) {
                            m.setAccessible(true);
                            getFacilitatorContextMethod = m;
                        }
                    } else if (!java.lang.reflect.Modifier.isStatic(mods)
                            && m.getParameterCount() == 0
                            && m.getReturnType() == Locale.class) {
                        m.setAccessible(true);
                        getLocaleMethod = m;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                reflectionProbed = true;
            }
        }
    }
}
