package dev.srwi.gboardmultiswipe.extension.nintype;

import java.text.Normalizer;

/**
 * Accent folding and German digraph expansion for Nintype Trie insertion.
 * Ported from Kinetica's AccentFolder.
 */
public final class NintypeAccentFolder {

    private NintypeAccentFolder() {}

    public static String fold(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = Character.toLowerCase(word.charAt(i));
            char mapped = mapSingleChar(c);
            if (mapped != 0) {
                sb.append(mapped);
            } else {
                String nfd = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                char base = nfd.isEmpty() ? 0 : nfd.charAt(0);
                if (base >= 'a' && base <= 'z') {
                    sb.append(base);
                }
            }
        }
        return sb.toString();
    }

    public static String foldGermanDigraphs(String word, boolean hasdedicatedUmlautKeys) {
        StringBuilder sb = new StringBuilder(word.length() + 4);
        boolean changed = false;

        for (int i = 0; i < word.length(); i++) {
            char c = Character.toLowerCase(word.charAt(i));
            switch (c) {
                case 'ß':
                    sb.append("ss");
                    changed = true;
                    break;
                case 'ä':
                    if (!hasdedicatedUmlautKeys) {
                        sb.append("ae");
                        changed = true;
                    } else {
                        sb.append('a');
                    }
                    break;
                case 'ö':
                    if (!hasdedicatedUmlautKeys) {
                        sb.append("oe");
                        changed = true;
                    } else {
                        sb.append('o');
                    }
                    break;
                case 'ü':
                    if (!hasdedicatedUmlautKeys) {
                        sb.append("ue");
                        changed = true;
                    } else {
                        sb.append('u');
                    }
                    break;
                default:
                    char mapped = mapSingleChar(c);
                    if (mapped != 0) {
                        sb.append(mapped);
                    } else {
                        String nfd = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                        char base = nfd.isEmpty() ? 0 : nfd.charAt(0);
                        if (base >= 'a' && base <= 'z') {
                            sb.append(base);
                        }
                    }
                    break;
            }
        }

        return changed ? sb.toString() : null;
    }

    public static char mapSingleChar(char c) {
        if (c >= 'a' && c <= 'z') return c;
        switch (c) {
            case 'ä':
            case 'à':
            case 'á':
            case 'â':
            case 'ã':
            case 'å':
            case 'ā':
            case 'ă':
            case 'ą':
                return 'a';
            case 'ç':
            case 'ć':
            case 'č':
                return 'c';
            case 'ď':
            case 'đ':
            case 'ð':
                return 'd';
            case 'è':
            case 'é':
            case 'ê':
            case 'ë':
            case 'ē':
            case 'ĕ':
            case 'ė':
            case 'ę':
            case 'ě':
                return 'e';
            case 'ğ':
            case 'ģ':
                return 'g';
            case 'ì':
            case 'í':
            case 'î':
            case 'ï':
            case 'ī':
            case 'į':
            case 'ı':
                return 'i';
            case 'ķ':
                return 'k';
            case 'ł':
            case 'ĺ':
            case 'ļ':
            case 'ľ':
                return 'l';
            case 'ñ':
            case 'ń':
            case 'ņ':
            case 'ň':
                return 'n';
            case 'ö':
            case 'ò':
            case 'ó':
            case 'ô':
            case 'õ':
            case 'ø':
            case 'ō':
            case 'ő':
                return 'o';
            case 'ŕ':
            case 'ř':
                return 'r';
            case 'ß':
            case 'ś':
            case 'š':
            case 'ş':
            case 'ș':
                return 's';
            case 'ť':
            case 'ţ':
            case 'ț':
            case 'þ':
                return 't';
            case 'ü':
            case 'ù':
            case 'ú':
            case 'û':
            case 'ū':
            case 'ů':
            case 'ű':
            case 'ų':
                return 'u';
            case 'ý':
            case 'ÿ':
                return 'y';
            case 'ź':
            case 'ż':
            case 'ž':
                return 'z';
            default:
                return 0;
        }
    }
}
