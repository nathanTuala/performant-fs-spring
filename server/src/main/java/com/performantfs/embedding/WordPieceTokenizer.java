package com.performantfs.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT's uncased WordPiece tokenizer, matching Hugging Face's {@code BertTokenizer} for the
 * MiniLM vocabulary: clean the text, split CJK characters, lowercase and strip accents, split on
 * whitespace and punctuation, then break each word into the longest vocabulary pieces
 * ({@code unaffable} → {@code una ##ffa ##ble}).
 */
public final class WordPieceTokenizer {

    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab;
    private final int cls;
    private final int sep;
    private final int unk;

    public WordPieceTokenizer(Map<String, Integer> vocab) {
        this.vocab = vocab;
        this.cls = vocab.get("[CLS]");
        this.sep = vocab.get("[SEP]");
        this.unk = vocab.get("[UNK]");
    }

    /** Reads a vocab.txt: one token per line, the line number is its id. */
    public static WordPieceTokenizer load(Path vocabFile) throws IOException {
        List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
        Map<String, Integer> vocab = new HashMap<>(lines.size() * 2);
        for (int i = 0; i < lines.size(); i++) vocab.put(lines.get(i), i);
        return new WordPieceTokenizer(vocab);
    }

    /** Token ids with [CLS] and [SEP] around them, at most {@code maxTokens} in all. */
    public long[] encode(String text, int maxTokens) {
        List<Integer> ids = new ArrayList<>();
        ids.add(cls);
        outer:
        for (String word : basicTokens(text)) {
            for (int id : wordPieces(word)) {
                if (ids.size() >= maxTokens - 1) break outer;
                ids.add(id);
            }
        }
        ids.add(sep);
        return ids.stream().mapToLong(Integer::longValue).toArray();
    }

    /** Words and punctuation marks, cleaned, lowercased and without accents. */
    static List<String> basicTokens(String text) {
        var spaced = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) return;
            if (isWhitespace(cp)) spaced.append(' ');
            else if (isCjk(cp)) spaced.append(' ').appendCodePoint(cp).append(' ');
            else spaced.appendCodePoint(cp);
        });
        List<String> out = new ArrayList<>();
        for (String word : spaced.toString().split(" ")) {
            if (word.isEmpty()) continue;
            String plain = stripAccents(word.toLowerCase(Locale.ROOT));
            var current = new StringBuilder();
            plain.codePoints().forEach(cp -> {
                if (isPunctuation(cp)) {
                    if (!current.isEmpty()) out.add(current.toString());
                    current.setLength(0);
                    out.add(Character.toString(cp));
                } else {
                    current.appendCodePoint(cp);
                }
            });
            if (!current.isEmpty()) out.add(current.toString());
        }
        return out;
    }

    /** Greedy longest-match-first; a word that can't be fully covered is [UNK]. */
    List<Integer> wordPieces(String word) {
        int[] cps = word.codePoints().toArray();
        if (cps.length > MAX_WORD_CHARS) return List.of(unk);
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < cps.length) {
            Integer found = null;
            int end = cps.length;
            while (start < end) {
                String piece = (start > 0 ? "##" : "") + new String(cps, start, end - start);
                found = vocab.get(piece);
                if (found != null) break;
                end--;
            }
            if (found == null) return List.of(unk);
            pieces.add(found);
            start = end;
        }
        return pieces;
    }

    private static String stripAccents(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        var out = new StringBuilder(decomposed.length());
        decomposed.codePoints().filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    /** ASCII symbols count as punctuation too ($, ^, `), as in BERT. */
    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true;
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    /** The CJK Unified Ideograph blocks BERT splits into single characters. */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
