package com.performantfs.search;

import com.performantfs.http.ApiException;
import com.performantfs.search.SearchQuery.Comparison;
import com.performantfs.search.SearchQuery.DateFilter;
import com.performantfs.search.SearchQuery.SizeFilter;
import com.performantfs.search.SearchQuery.TypeFilter;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses the search box syntax: free text plus filters
 * <pre>
 *   type:pdf|image|video|audio|folder|…   size:&gt;10mb   modified:&lt;2026-01-01
 *   in:"Folder name"   "exact phrase"
 * </pre>
 * Unknown keys are kept as text, so "re:meeting" still searches for it. The search box runs
 * queries as the user types, so half-typed input is accepted: a filter with no value yet
 * ("size:", "size:&gt;") is skipped, and an unclosed quote runs to the end of the input.
 */
public final class SearchParser {

    // A quoted phrase, a key:value (value possibly quoted), or a bare word.
    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"?|(\\w+):(?:\"([^\"]*)\"?|(\\S*))|(\\S+)");
    private static final Pattern SIZE = Pattern.compile("^(<=|>=|<|>|=)?(\\d+(?:\\.\\d+)?)(b|kb|mb|gb|tb)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("^(<=|>=|<|>|=)?(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern OPERATOR_ONLY = Pattern.compile("^(<=|>=|<|>|=)?$");
    private static final Map<String, Long> UNITS = Map.of("b", 1L, "kb", 1L << 10, "mb", 1L << 20, "gb", 1L << 30, "tb", 1L << 40);
    private static final Map<String, TypeFilter> ALIASES = Map.ofEntries(
            Map.entry("dir", TypeFilter.FOLDER), Map.entry("directory", TypeFilter.FOLDER),
            Map.entry("photo", TypeFilter.IMAGE), Map.entry("picture", TypeFilter.IMAGE), Map.entry("img", TypeFilter.IMAGE),
            Map.entry("movie", TypeFilter.VIDEO), Map.entry("music", TypeFilter.AUDIO), Map.entry("sound", TypeFilter.AUDIO),
            Map.entry("code", TypeFilter.TEXT), Map.entry("txt", TypeFilter.TEXT), Map.entry("document", TypeFilter.DOC),
            Map.entry("zip", TypeFilter.ARCHIVE));

    private SearchParser() {}

    public static SearchQuery parse(String input) {
        List<String> words = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        List<TypeFilter> types = new ArrayList<>();
        List<SizeFilter> sizes = new ArrayList<>();
        List<DateFilter> modified = new ArrayList<>();
        String inFolder = null;

        Matcher m = TOKEN.matcher(input);
        while (m.find()) {
            String phrase = m.group(1);
            String key = m.group(2);
            if (phrase != null) {
                if (!phrase.isBlank()) phrases.add(nfc(phrase.strip()));
                continue;
            }
            if (key == null) {
                words.add(m.group(5));
                continue;
            }
            String value = (m.group(3) != null ? m.group(3) : m.group(4)).strip();
            switch (key.toLowerCase(Locale.ROOT)) {
                case "type" -> {
                    if (!OPERATOR_ONLY.matcher(value).matches()) addTypes(value, types);
                }
                case "size" -> {
                    if (!OPERATOR_ONLY.matcher(value).matches()) sizes.add(size(value));
                }
                case "modified", "date" -> {
                    if (!OPERATOR_ONLY.matcher(value).matches()) modified.add(date(value));
                }
                case "in" -> {
                    if (!value.isEmpty()) inFolder = nfc(value);
                }
                default -> words.add(m.group()); // not a filter: keep it as text
            }
        }
        return new SearchQuery(nfc(String.join(" ", words)), phrases, types, sizes, modified, inFolder);
    }

    private static void addTypes(String value, List<TypeFilter> types) {
        for (String raw : value.toLowerCase(Locale.ROOT).split("[|,]")) {
            if (raw.isEmpty()) continue;
            TypeFilter type = ALIASES.get(raw);
            if (type == null) {
                try {
                    type = TypeFilter.valueOf(raw.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    String known = Arrays.stream(TypeFilter.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
                    throw ApiException.badRequest("Unknown type \"" + raw + "\". Try: " + known);
                }
            }
            if (!types.contains(type)) types.add(type);
        }
    }

    private static SizeFilter size(String value) {
        Matcher m = SIZE.matcher(value);
        if (!m.matches()) throw ApiException.badRequest("Invalid size \"" + value + "\". Example: size:>10mb");
        long unit = UNITS.get(m.group(3) == null ? "b" : m.group(3).toLowerCase(Locale.ROOT));
        return new SizeFilter(Comparison.of(m.group(1)), Math.round(Double.parseDouble(m.group(2)) * unit));
    }

    private static DateFilter date(String value) {
        Matcher m = DATE.matcher(value);
        try {
            if (m.matches()) return new DateFilter(Comparison.of(m.group(1)), LocalDate.parse(m.group(2)));
        } catch (DateTimeException e) {
            // fall through: e.g. 2026-02-31
        }
        throw ApiException.badRequest("Invalid date \"" + value + "\". Example: modified:<2026-01-01");
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }
}
