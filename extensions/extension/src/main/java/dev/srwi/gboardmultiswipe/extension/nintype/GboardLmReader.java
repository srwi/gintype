package dev.srwi.gboardmultiswipe.extension.nintype;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pure-Java parser for Gboard Delight5 {@code .lm} binary language model files.
 *
 * <p>Reads only the chunks required for swipe decoding:
 * <ul>
 *   <li>Chunk {@code 0x20} ({@code MarisaSymbolTable}): nested LOUDS Marisa trie and
 *       relabeled word symbol ID table.</li>
 *   <li>Chunk {@code 0x2} ({@code CompactLmFst} / {@code NGramFst}): quantized unigram
 *       and bigram negative log probabilities mapped into {@link NintypeTrie} and
 *       {@link NintypeBigramTable}.</li>
 * </ul>
 */
public final class GboardLmReader {

    public static final int LM_MAGIC = 0x07506F06;
    private static final long CHUNK_COMPACT_LM = 0x2L;
    private static final long CHUNK_MARISA_SYMBOLS = 0x20L;
    private static final byte[] MARISA_MAGIC = "We love Marisa.\0".getBytes(StandardCharsets.US_ASCII);

    /** Cap bigrams kept in memory to the strongest continuations to bound heap and load latency. */
    static final int DEFAULT_MAX_BIGRAMS = 200_000;

    private static final int DEFAULT_BIGRAM_SCALE_COUNT = 100_000;

    private GboardLmReader() {}

    public static final class LmDictionaries {
        public final NintypeTrie trie;
        public final NintypeBigramTable bigrams;
        public final int cleanWordCount;
        public final int bigramCount;

        LmDictionaries(NintypeTrie trie, NintypeBigramTable bigrams, int cleanWordCount, int bigramCount) {
            this.trie = trie;
            this.bigrams = bigrams;
            this.cleanWordCount = cleanWordCount;
            this.bigramCount = bigramCount;
        }
    }

    /**
     * Checks quickly whether {@code file} starts with the uncompressed Gboard {@code .lm} magic.
     */
    public static boolean isLmFile(File file) {
        if (file == null || !file.isFile() || file.length() < 32L) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] hdr = new byte[4];
            raf.readFully(hdr);
            int magic = (hdr[0] & 0xFF)
                    | ((hdr[1] & 0xFF) << 8)
                    | ((hdr[2] & 0xFF) << 16)
                    | ((hdr[3] & 0xFF) << 24);
            return magic == LM_MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    public static LmDictionaries read(
            File lmFile,
            String lang,
            boolean hasDedicatedUmlautKeys
    ) throws IOException {
        return read(lmFile, lang, hasDedicatedUmlautKeys, DEFAULT_MAX_BIGRAMS);
    }

    public static LmDictionaries read(
            File lmFile,
            String lang,
            boolean hasDedicatedUmlautKeys,
            int maxBigrams
    ) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(lmFile, "r");
             FileChannel ch = raf.getChannel()) {
            long fileLen = ch.size();
            if (fileLen < 16L) {
                throw new IOException("LM file too small: " + lmFile);
            }
            ByteBuffer fileHdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, fileHdr, 0L);
            fileHdr.flip();
            int magic = fileHdr.getInt();
            if (magic != LM_MAGIC) {
                throw new IOException("Unsupported LM magic 0x" + Integer.toHexString(magic) + " in " + lmFile);
            }

            long symOff = -1L;
            int symSize = 0;
            long fstOff = -1L;
            int fstSize = 0;

            ByteBuffer chunkHdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            long pos = 16L;
            while (pos + 16L <= fileLen) {
                chunkHdr.clear();
                readFully(ch, chunkHdr, pos);
                chunkHdr.flip();
                long chunkType = chunkHdr.getLong();
                long chunkSize = chunkHdr.getLong();
                long dataOff = pos + 16L;
                if (chunkSize < 0 || dataOff + chunkSize > fileLen) {
                    throw new IOException("Corrupt LM chunk header at " + pos + " (size=" + chunkSize + ")");
                }
                if (chunkType == CHUNK_MARISA_SYMBOLS) {
                    symOff = dataOff;
                    symSize = checkChunkSize(chunkSize, "MarisaSymbolTable");
                } else if (chunkType == CHUNK_COMPACT_LM) {
                    fstOff = dataOff;
                    fstSize = checkChunkSize(chunkSize, "CompactLmFst");
                }
                pos = dataOff + chunkSize;
                if (symOff >= 0 && fstOff >= 0) {
                    break;
                }
            }

            if (symOff < 0 || fstOff < 0) {
                throw new IOException("Missing required LM chunks (0x20=" + (symOff >= 0)
                        + ", 0x2=" + (fstOff >= 0) + ") in " + lmFile);
            }

            byte[] symData = readChunkBytes(ch, symOff, symSize);
            byte[] fstData = readChunkBytes(ch, fstOff, fstSize);
            return parseChunks(symData, symOff, fstData, fstOff, lang, hasDedicatedUmlautKeys, maxBigrams);
        }
    }

    /**
     * Parses an entire in-memory {@code .lm} stream (convenient for tests).
     */
    public static LmDictionaries read(
            InputStream stream,
            String lang,
            boolean hasDedicatedUmlautKeys
    ) throws IOException {
        return readFromBytes(readAllBytes(stream), lang, hasDedicatedUmlautKeys, DEFAULT_MAX_BIGRAMS);
    }

    public static LmDictionaries readFromBytes(
            byte[] lmBytes,
            String lang,
            boolean hasDedicatedUmlautKeys,
            int maxBigrams
    ) throws IOException {
        if (lmBytes.length < 16) {
            throw new IOException("LM buffer too small");
        }
        ByteBuffer buf = ByteBuffer.wrap(lmBytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt(0);
        if (magic != LM_MAGIC) {
            throw new IOException("Unsupported LM magic 0x" + Integer.toHexString(magic));
        }

        long symOff = -1L;
        byte[] symData = null;
        long fstOff = -1L;
        byte[] fstData = null;

        int pos = 16;
        while (pos + 16 <= lmBytes.length) {
            long chunkType = buf.getLong(pos);
            long chunkSize = buf.getLong(pos + 8);
            int dataOff = pos + 16;
            if (chunkSize < 0 || dataOff + chunkSize > lmBytes.length) {
                throw new IOException("Corrupt LM chunk at " + pos);
            }
            int len = (int) chunkSize;
            if (chunkType == CHUNK_MARISA_SYMBOLS) {
                symOff = dataOff;
                symData = Arrays.copyOfRange(lmBytes, dataOff, dataOff + len);
            } else if (chunkType == CHUNK_COMPACT_LM) {
                fstOff = dataOff;
                fstData = Arrays.copyOfRange(lmBytes, dataOff, dataOff + len);
            }
            pos = dataOff + len;
        }

        if (symData == null || fstData == null) {
            throw new IOException("Missing required LM chunks (0x20=" + (symData != null)
                    + ", 0x2=" + (fstData != null) + ")");
        }
        return parseChunks(symData, symOff, fstData, fstOff, lang, hasDedicatedUmlautKeys, maxBigrams);
    }

    private static LmDictionaries parseChunks(
            byte[] symData,
            long symFileOffset,
            byte[] fstData,
            long fstFileOffset,
            String lang,
            boolean hasDedicatedUmlautKeys,
            int maxBigrams
    ) throws IOException {
        String[] symWords = parseMarisaSymbolTable(symData, symFileOffset);
        return parseCompactLm(symWords, fstData, fstFileOffset, lang, hasDedicatedUmlautKeys, maxBigrams);
    }

    // -------------------------------------------------------------------------
    // Chunk 0x20: MarisaSymbolTable + relabel map
    // -------------------------------------------------------------------------

    private static String[] parseMarisaSymbolTable(byte[] data, long chunkFileOffset) throws IOException {
        if (data.length < 32) {
            throw new IOException("MarisaSymbolTable chunk too small");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int nameLen = buf.getInt(4);
        if (nameLen < 0 || 8 + nameLen + 12 > data.length) {
            throw new IOException("Invalid MarisaSymbolTable nameLen=" + nameLen);
        }
        int digestLenPos = 8 + nameLen;
        int digestLen = buf.getInt(digestLenPos);
        int mszPos = digestLenPos + 4 + digestLen;
        if (digestLen < 0 || mszPos + 8 > data.length) {
            throw new IOException("Invalid MarisaSymbolTable digestLen=" + digestLen);
        }
        long marisaSizeLong = buf.getLong(mszPos);
        int headerEnd = mszPos + 8;
        int marisaOff = align16Relative(chunkFileOffset, headerEnd);
        if (!matchesAt(data, marisaOff, MARISA_MAGIC)) {
            int scanned = findBytes(data, MARISA_MAGIC, headerEnd, Math.min(data.length, headerEnd + 64));
            if (scanned < 0) {
                throw new IOException("Marisa magic not found in symbol table");
            }
            marisaOff = scanned;
        }
        int marisaSize = checkChunkSize(marisaSizeLong, "marisaTrie");
        if (marisaOff + marisaSize + 16 > data.length) {
            throw new IOException("Marisa trie exceeds symbol chunk bounds");
        }

        MarisaReader reader = new MarisaReader(data, marisaOff + 16, marisaOff + marisaSize);
        byte[][] keys = decodeLoudsLevel(reader, 0).termKeys;

        int afterMarisa = marisaOff + marisaSize;
        // u64 block size precedes 16-byte aligned (stride, byteLen, packed indices)
        int relOff = align16Relative(chunkFileOffset, afterMarisa + 8);
        if (relOff + 16 > data.length) {
            throw new IOException("Relabel header out of bounds");
        }
        long strideLong = buf.getLong(relOff);
        long byteLenLong = buf.getLong(relOff + 8);
        if (strideLong <= 0 || strideLong > 8 || byteLenLong < 0 || relOff + 16 + byteLenLong > data.length) {
            throw new IOException("Invalid relabel table stride=" + strideLong + ", len=" + byteLenLong);
        }
        int stride = (int) strideLong;
        int count = (int) (byteLenLong / stride);
        int tblStart = relOff + 16;

        String[] words = new String[count];
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        for (int sym = 0; sym < count; sym++) {
            int val = readLittleEndianInt(data, tblStart + sym * stride, stride);
            int kid = val - 1;
            if (kid < 0 || kid >= keys.length) {
                continue;
            }
            byte[] raw = keys[kid];
            if (raw.length == 0 || hasControlBytePrefix(raw)) {
                continue;
            }
            try {
                String decoded = utf8.decode(ByteBuffer.wrap(raw)).toString();
                utf8.reset();
                if (isUsableVocabularyWord(decoded)) {
                    words[sym] = decoded;
                }
            } catch (CharacterCodingException ignored) {
                utf8.reset();
            }
        }
        return words;
    }

    private static boolean hasControlBytePrefix(byte[] raw) {
        int b0 = raw[0] & 0xFF;
        return b0 < 0x20 || (b0 >= 0x7F && b0 <= 0x9F);
    }

    static boolean isUsableVocabularyWord(String word) {
        int len = word.length();
        if (len == 0) return false;
        if ("<epsilon>".equals(word) || "<UNK>".equals(word)) return false;
        for (int i = 0; i < len; i++) {
            char c = word.charAt(i);
            if (c == '_' || Character.isDigit(c)) {
                return false;
            }
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                return false;
            }
            if (!Character.isLetter(c) && c != '\'' && c != '-') {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Chunk 0x2: CompactLmFst / NGramFst unigrams + bigrams
    // -------------------------------------------------------------------------

    private static LmDictionaries parseCompactLm(
            String[] symWords,
            byte[] data,
            long chunkFileOffset,
            String lang,
            boolean hasDedicatedUmlautKeys,
            int maxBigrams
    ) throws IOException {
        if (data.length < 120) {
            throw new IOException("CompactLmFst chunk too small");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int numWords = buf.getInt(70);
        int numTail = buf.getInt(74);
        float scale = buf.getFloat(79);
        long ngramSize = buf.getLong(83);
        if (numWords <= 0 || numTail < 0 || numTail > numWords || ngramSize < 24 || scale <= 0f) {
            throw new IOException("Invalid CompactLmFst header: numWords=" + numWords
                    + ", numTail=" + numTail + ", scale=" + scale + ", ngramSize=" + ngramSize);
        }

        int ngramOff = align16Relative(chunkFileOffset, 91);
        long bsOffLong = ngramOff + ((ngramSize + 7L) & ~7L);
        int bsBytes = ((numWords + 63) / 64) * 8;
        if (bsOffLong + bsBytes + numTail > data.length) {
            throw new IOException("CompactLmFst tail weights exceed chunk length");
        }
        int bsOff = (int) bsOffLong;
        int tailOff = bsOff + bsBytes;

        long numStates = buf.getLong(ngramOff);
        long numFutures = buf.getLong(ngramOff + 8);
        long numFinal = buf.getLong(ngramOff + 16);
        int sInt = checkChunkSize(numStates, "numStates");
        int fInt = checkChunkSize(numFutures, "numFutures");
        int nfinInt = checkChunkSize(numFinal, "numFinal");

        int b1 = ((2 * sInt + 1 + 63) / 64) * 8;
        int b2 = ((fInt + sInt + 1 + 63) / 64) * 8;
        int b3 = ((sInt + 63) / 64) * 8;

        int oCtxBv = ngramOff + 24;
        int oFutBv = oCtxBv + b1;
        int oCtxWords = oFutBv + b2 + b3;
        int oFutWords = oCtxWords + (sInt + 1) * 2;
        int oCtxWeights = oFutWords + fInt * 2;
        int oFutWeights = oCtxWeights + (sInt + 1) + nfinInt;
        if (oFutWeights + fInt > data.length) {
            throw new IOException("NGramFst arrays exceed chunk bounds");
        }

        int numComp = numWords - numTail;
        int[] compToSym = new int[numComp];
        int symCap = Math.max(numWords, symWords.length);
        int[] symToQ = new int[symCap];
        Arrays.fill(symToQ, 255);

        int compIdx = 0;
        int tailIdx = 0;
        for (int sym = 0; sym < numWords; sym++) {
            int wordOff = bsOff + ((sym >>> 6) << 3);
            long w = buf.getLong(wordOff);
            if (((w >>> (sym & 63)) & 1L) != 0L) {
                if (compIdx < numComp) {
                    compToSym[compIdx++] = sym;
                }
            } else if (tailIdx < numTail) {
                symToQ[sym] = data[tailOff + tailIdx++] & 0xFF;
            }
        }

        // State 0 futures in fut_bv_ hold unigram weights for compressed symbols.
        int futBits = fInt + sInt + 1;
        int firstOne = findFirstSetBit(buf, oFutBv, futBits);
        int uCount = 0;
        if (firstOne >= 0) {
            int bitPos = firstOne;
            while (bitPos < futBits && getBit(buf, oFutBv, bitPos)) {
                uCount++;
                bitPos++;
            }
        }
        int limitU = Math.min(uCount, fInt);
        for (int i = 0; i < limitU; i++) {
            int cid = buf.getShort(oFutWords + i * 2) & 0xFFFF;
            if (cid < compIdx) {
                symToQ[compToSym[cid]] = data[oFutWeights + i] & 0xFF;
            }
        }

        // Determine clean word count and [qMin, qMax] to calibrate Gboard -ln(P) to Kinetica [1..255].
        int usableLimit = Math.min(numWords, symWords.length);
        int cleanCount = 0;
        int qMin = 255;
        int qMax = 0;
        for (int sym = 0; sym < usableLimit; sym++) {
            if (symWords[sym] == null) continue;
            cleanCount++;
            int q = symToQ[sym];
            if (q < qMin) qMin = q;
            if (q > qMax) qMax = q;
        }
        if (qMin > qMax) {
            qMin = 0;
            qMax = 255;
        }
        float denomLn = Math.max(15.5f, (qMax - qMin) * scale * 1.1f);

        int initCap = Math.min(Math.max(4096, cleanCount * 2), 360_000);
        NintypeTrie trie = new NintypeTrie(initCap);
        for (int sym = 0; sym < usableLimit; sym++) {
            String word = symWords[sym];
            if (word == null) continue;
            int q = symToQ[sym];
            float deltaLn = Math.max(0f, (q - qMin) * scale);
            int freqByte = Math.max(1, Math.min(255, Math.round(255f * (1f - deltaLn / denomLn))));
            boolean isProper = Character.isUpperCase(word.charAt(0));
            NintypeDictionaryLoader.insertWordIntoTrie(
                    trie, word, freqByte, isProper, lang, hasDedicatedUmlautKeys);
        }
        trie.compact();

        // Extract Level-1 bigram transitions using ctx_bv_ + fut_bv_.
        NintypeBigramTable bigrams = new NintypeBigramTable();
        int bigramCount = extractBigrams(
                bigrams, symWords, usableLimit, compToSym, compIdx,
                buf, oCtxBv, sInt, oFutBv, futBits,
                oCtxWords, oFutWords, oFutWeights,
                scale, maxBigrams);

        return new LmDictionaries(trie, bigrams, cleanCount, bigramCount);
    }

    private static int extractBigrams(
            NintypeBigramTable table,
            String[] symWords,
            int usableLimit,
            int[] compToSym,
            int compCount,
            ByteBuffer buf,
            int oCtxBv,
            int numStates,
            int oFutBv,
            int futBits,
            int oCtxWords,
            int oFutWords,
            int oFutWeights,
            float scale,
            int maxBigrams
    ) {
        if (maxBigrams <= 0 || numStates <= 1) {
            table.freeze();
            return 0;
        }

        // In ctx_bv_, bit 0=1, bit 1=0 (super-root), followed by consecutive 1s for Level-1 context states 1..l1States.
        int ctxBits = 2 * numStates + 1;
        int l1States = 0;
        int cPos = 2;
        while (cPos < ctxBits && getBit(buf, oCtxBv, cPos)) {
            l1States++;
            cPos++;
        }
        if (l1States <= 0) {
            table.freeze();
            return 0;
        }

        // Pass 1: build 256-bin histogram of quantized bigram weights for clean word pairs.
        int[] hist = new int[256];
        int totalCleanBigrams = walkLevel1Futures(
                symWords, usableLimit, compToSym, compCount,
                buf, l1States, oFutBv, futBits,
                oCtxWords, oFutWords, oFutWeights,
                255, hist, null, 0f);

        int qCutoff = 255;
        if (totalCleanBigrams > maxBigrams) {
            int acc = 0;
            qCutoff = 0;
            for (int q = 0; q < 256; q++) {
                if (acc + hist[q] > maxBigrams) {
                    break;
                }
                acc += hist[q];
                qCutoff = q;
            }
            if (acc == 0) {
                qCutoff = 0;
            }
        }

        // Pass 2: insert all clean bigrams up to qCutoff into NintypeBigramTable.
        int inserted = walkLevel1Futures(
                symWords, usableLimit, compToSym, compCount,
                buf, l1States, oFutBv, futBits,
                oCtxWords, oFutWords, oFutWeights,
                qCutoff, null, table, scale);

        table.freeze();
        return inserted;
    }

    private static int walkLevel1Futures(
            String[] symWords,
            int usableLimit,
            int[] compToSym,
            int compCount,
            ByteBuffer buf,
            int l1States,
            int oFutBv,
            int futBits,
            int oCtxWords,
            int oFutWords,
            int oFutWeights,
            int qCutoff,
            int[] outHist,
            NintypeBigramTable outTable,
            float scale
    ) {
        int zeros = 0;
        int ones = 0;
        int rangeStart = 0;
        int total = 0;

        int numWords64 = (futBits + 63) / 64;
        for (int wi = 0; wi < numWords64; wi++) {
            long word = buf.getLong(oFutBv + wi * 8);
            int baseBit = wi << 6;
            int validBits = Math.min(64, futBits - baseBit);
            for (int b = 0; b < validBits; b++) {
                if (((word >>> b) & 1L) != 0L) {
                    ones++;
                } else {
                    int stateIdx = zeros - 1;
                    if (stateIdx >= 1 && stateIdx <= l1States) {
                        int prevCid = buf.getShort(oCtxWords + stateIdx * 2) & 0xFFFF;
                        if (prevCid < compCount) {
                            int prevSym = compToSym[prevCid];
                            String prevWord = (prevSym < usableLimit) ? symWords[prevSym] : null;
                            if (prevWord != null) {
                                for (int fi = rangeStart; fi < ones; fi++) {
                                    int nextCid = buf.getShort(oFutWords + fi * 2) & 0xFFFF;
                                    if (nextCid >= compCount) continue;
                                    int nextSym = compToSym[nextCid];
                                    String nextWord = (nextSym < usableLimit) ? symWords[nextSym] : null;
                                    if (nextWord == null) continue;

                                    int q = buf.get(oFutWeights + fi) & 0xFF;
                                    if (q <= qCutoff) {
                                        total++;
                                        if (outHist != null) {
                                            outHist[q]++;
                                        }
                                        if (outTable != null) {
                                            int count = Math.max(1, (int) Math.round(
                                                    DEFAULT_BIGRAM_SCALE_COUNT * Math.exp(-q * scale)));
                                            outTable.put(prevWord, nextWord, count);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    zeros++;
                    rangeStart = ones;
                    if (zeros > l1States + 1) {
                        return total;
                    }
                }
            }
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // MARISA LOUDS Trie O(N) BFS decoder
    // -------------------------------------------------------------------------

    private static final class MarisaBitVector {
        final ByteBuffer wordBuf;
        final int wordOffset;
        final int size;
        final int num1s;

        MarisaBitVector(ByteBuffer wordBuf, int wordOffset, int size, int num1s) {
            this.wordBuf = wordBuf;
            this.wordOffset = wordOffset;
            this.size = size;
            this.num1s = num1s;
        }

        boolean get(int bitIndex) {
            long word = wordBuf.getLong(wordOffset + ((bitIndex >>> 6) << 3));
            return ((word >>> (bitIndex & 63)) & 1L) != 0L;
        }
    }

    private static final class MarisaFlatVector {
        final byte[] units;
        final int valSize;
        final int mask;

        MarisaFlatVector(byte[] units, int valSize, int mask) {
            this.units = units;
            this.valSize = valSize;
            this.mask = mask;
        }

        int get(int idx) {
            if (valSize == 0) return 0;
            long bitPos = (long) idx * valSize;
            int bytePos = (int) (bitPos >>> 3);
            long chunk = 0L;
            int avail = Math.min(8, units.length - bytePos);
            for (int i = 0; i < avail; i++) {
                chunk |= (long) (units[bytePos + i] & 0xFF) << (i * 8);
            }
            return (int) ((chunk >>> (bitPos & 7)) & (mask & 0xFFFFFFFFL));
        }
    }

    private static final class MarisaReader {
        final byte[] data;
        final ByteBuffer buf;
        final int end;
        int pos;

        MarisaReader(byte[] data, int pos, int end) {
            this.data = data;
            this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.pos = pos;
            this.end = end;
        }

        byte[] readVecBytes() throws IOException {
            int n = readVecLen();
            byte[] out = Arrays.copyOfRange(data, pos, pos + n);
            pos += (n + 7) & ~7;
            return out;
        }

        int readVecLen() throws IOException {
            if (pos + 8 > end) {
                throw new IOException("MARISA vector header out of bounds");
            }
            long nLong = buf.getLong(pos);
            pos += 8;
            int n = checkChunkSize(nLong, "marisaVector");
            int aligned = (n + 7) & ~7;
            if (pos + aligned > end) {
                throw new IOException("MARISA vector body out of bounds: " + n);
            }
            return n;
        }

        void skipVec() throws IOException {
            int n = readVecLen();
            pos += (n + 7) & ~7;
        }

        MarisaBitVector readBitVector() throws IOException {
            int vecByteLen = readVecLen();
            int unitsStart = pos;
            pos += (vecByteLen + 7) & ~7;
            if (pos + 8 > end) {
                throw new IOException("MARISA bitvector metadata out of bounds");
            }
            int size = buf.getInt(pos);
            int num1s = buf.getInt(pos + 4);
            pos += 8;
            // Skip select0, select1, rank1s accelerator tables
            skipVec();
            skipVec();
            skipVec();

            int needWordsBytes = ((size + 63) / 64) * 8;
            ByteBuffer bvBuf;
            int bvOff;
            if (unitsStart + needWordsBytes <= end && vecByteLen >= needWordsBytes) {
                bvBuf = buf;
                bvOff = unitsStart;
            } else {
                byte[] padded = new byte[Math.max(8, needWordsBytes)];
                System.arraycopy(data, unitsStart, padded, 0, Math.min(vecByteLen, padded.length));
                bvBuf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN);
                bvOff = 0;
            }
            return new MarisaBitVector(bvBuf, bvOff, size, num1s);
        }

        MarisaFlatVector readFlatVector() throws IOException {
            byte[] units = readVecBytes();
            if (pos + 16 > end) {
                throw new IOException("MARISA flatvector metadata out of bounds");
            }
            int valSize = buf.getInt(pos);
            int mask = buf.getInt(pos + 4);
            pos += 16;
            return new MarisaFlatVector(units, valSize, mask);
        }
    }

    private static final class DecodedLouds {
        final byte[][] nodeStrings;
        final byte[][] termKeys;

        DecodedLouds(byte[][] nodeStrings, byte[][] termKeys) {
            this.nodeStrings = nodeStrings;
            this.termKeys = termKeys;
        }
    }

    private static DecodedLouds decodeLoudsLevel(MarisaReader r, int depth) throws IOException {
        MarisaBitVector louds = r.readBitVector();
        MarisaBitVector term = r.readBitVector();
        MarisaBitVector links = r.readBitVector();
        byte[] bases = r.readVecBytes();
        MarisaFlatVector extras = r.readFlatVector();
        byte[] tailBuf = r.readVecBytes();
        MarisaBitVector tailEnd = r.readBitVector();

        byte[][] childStrings = null;
        if (links.num1s > 0 && tailBuf.length == 0) {
            childStrings = decodeLoudsLevel(r, depth + 1).nodeStrings;
        }

        // Skip cache vector + num_l1_nodes + config_flags
        r.skipVec();
        if (r.pos + 8 > r.end) {
            throw new IOException("MARISA trailing fields out of bounds");
        }
        r.pos += 8;

        int numNodes = bases.length;
        byte[][] nodeStrings = new byte[numNodes][];
        if (numNodes > 0) {
            nodeStrings[0] = new byte[0];
        }

        int nid = 0;
        int parentId = -1;
        int linkIdx = 0;
        for (int bIdx = 0; bIdx < louds.size; bIdx++) {
            if (louds.get(bIdx)) {
                if (nid > 0 && nid < numNodes) {
                    byte[] parent = (parentId >= 0 && parentId < numNodes) ? nodeStrings[parentId] : new byte[0];
                    byte[] edge;
                    if (links.get(nid)) {
                        int linkId = (bases[nid] & 0xFF) | (extras.get(linkIdx++) << 8);
                        if (childStrings != null) {
                            edge = (linkId >= 0 && linkId < childStrings.length) ? childStrings[linkId] : new byte[0];
                        } else {
                            edge = sliceTail(tailBuf, tailEnd, linkId);
                        }
                        byte[] combined = new byte[parent.length + edge.length];
                        if (depth == 0) {
                            System.arraycopy(parent, 0, combined, 0, parent.length);
                            System.arraycopy(edge, 0, combined, parent.length, edge.length);
                        } else {
                            // Marisa stores nested tail tries in reversed suffix order.
                            System.arraycopy(edge, 0, combined, 0, edge.length);
                            System.arraycopy(parent, 0, combined, edge.length, parent.length);
                        }
                        nodeStrings[nid] = combined;
                    } else {
                        byte[] combined = new byte[parent.length + 1];
                        if (depth == 0) {
                            System.arraycopy(parent, 0, combined, 0, parent.length);
                            combined[parent.length] = bases[nid];
                        } else {
                            combined[0] = bases[nid];
                            System.arraycopy(parent, 0, combined, 1, parent.length);
                        }
                        nodeStrings[nid] = combined;
                    }
                }
                nid++;
            } else {
                parentId++;
            }
        }

        byte[][] termKeys = new byte[Math.max(0, term.num1s)][];
        if (term.num1s > 0) {
            int ti = 0;
            for (int i = 0; i < numNodes && ti < termKeys.length; i++) {
                if (term.get(i)) {
                    termKeys[ti++] = nodeStrings[i];
                }
            }
            if (ti < termKeys.length) {
                termKeys = Arrays.copyOf(termKeys, ti);
            }
        }
        return new DecodedLouds(nodeStrings, termKeys);
    }

    private static byte[] sliceTail(byte[] tailBuf, MarisaBitVector tailEnd, int start) {
        if (start < 0 || start >= tailBuf.length) {
            return new byte[0];
        }
        if (tailEnd.size > 0) {
            int p = start;
            while (p < tailBuf.length && p < tailEnd.size && !tailEnd.get(p)) {
                p++;
            }
            int end = Math.min(tailBuf.length, p + 1);
            return Arrays.copyOfRange(tailBuf, start, end);
        }
        int end = start;
        while (end < tailBuf.length && tailBuf[end] != 0) {
            end++;
        }
        return Arrays.copyOfRange(tailBuf, start, end);
    }

    // -------------------------------------------------------------------------
    // Low-level IO & alignment helpers
    // -------------------------------------------------------------------------

    private static int align16Relative(long chunkFileOffset, int relOffset) {
        long abs = chunkFileOffset + relOffset;
        long aligned = (abs + 15L) & ~15L;
        return relOffset + (int) (aligned - abs);
    }

    private static boolean getBit(ByteBuffer buf, int baseOffset, int bitIndex) {
        long word = buf.getLong(baseOffset + ((bitIndex >>> 6) << 3));
        return ((word >>> (bitIndex & 63)) & 1L) != 0L;
    }

    private static int findFirstSetBit(ByteBuffer buf, int baseOffset, int totalBits) {
        int words = (totalBits + 63) / 64;
        for (int wi = 0; wi < words; wi++) {
            long w = buf.getLong(baseOffset + wi * 8);
            if (w != 0L) {
                int bit = (wi << 6) + Long.numberOfTrailingZeros(w);
                return bit < totalBits ? bit : -1;
            }
        }
        return -1;
    }

    private static int readLittleEndianInt(byte[] data, int offset, int stride) {
        int val = 0;
        for (int i = 0; i < stride; i++) {
            val |= (data[offset + i] & 0xFF) << (i * 8);
        }
        return val;
    }

    private static boolean matchesAt(byte[] buf, int offset, byte[] needle) {
        if (offset < 0 || offset + needle.length > buf.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (buf[offset + i] != needle[i]) return false;
        }
        return true;
    }

    private static int findBytes(byte[] buf, byte[] needle, int start, int end) {
        int max = Math.min(end, buf.length) - needle.length;
        for (int i = Math.max(0, start); i <= max; i++) {
            if (matchesAt(buf, i, needle)) return i;
        }
        return -1;
    }

    private static int checkChunkSize(long size, String label) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE - 16) {
            throw new IOException(label + " too large: " + size);
        }
        return (int) size;
    }

    private static byte[] readChunkBytes(FileChannel ch, long offset, int size) throws IOException {
        byte[] out = new byte[size];
        ByteBuffer dst = ByteBuffer.wrap(out);
        readFully(ch, dst, offset);
        return out;
    }

    private static void readFully(FileChannel ch, ByteBuffer dst, long offset) throws IOException {
        long pos = offset;
        while (dst.hasRemaining()) {
            int n = ch.read(dst, pos);
            if (n < 0) {
                throw new EOFException("Unexpected EOF at offset " + pos);
            }
            pos += n;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[16_384];
        int n;
        while ((n = in.read(tmp)) >= 0) {
            if (n > 0) {
                baos.write(tmp, 0, n);
            }
        }
        return baos.toByteArray();
    }
}
