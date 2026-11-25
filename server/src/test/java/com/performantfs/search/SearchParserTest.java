package com.performantfs.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.performantfs.http.ApiException;
import com.performantfs.search.SearchQuery.Comparison;
import com.performantfs.search.SearchQuery.DateFilter;
import com.performantfs.search.SearchQuery.SizeFilter;
import com.performantfs.search.SearchQuery.TypeFilter;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SearchParserTest {

    private static final long MiB = 1L << 20;

    @Test
    void collectsFreeText() {
        SearchQuery q = SearchParser.parse("  quarterly   report ");
        assertThat(q.text()).isEqualTo("quarterly report");
        assertThat(q.phrases()).isEmpty();
    }

    @Test
    void pullsOutQuotedPhrases() {
        SearchQuery q = SearchParser.parse("budget \"Q3 final\" draft");
        assertThat(q.text()).isEqualTo("budget draft");
        assertThat(q.phrases()).containsExactly("Q3 final");
    }

    @Test
    void parsesTypeFiltersWithAliasesAndOrLists() {
        assertThat(SearchParser.parse("type:pdf").types()).containsExactly(TypeFilter.PDF);
        assertThat(SearchParser.parse("type:image|video type:dir").types()).containsExactly(TypeFilter.IMAGE, TypeFilter.VIDEO, TypeFilter.FOLDER);
        assertThat(SearchParser.parse("type:photo,photo").types()).containsExactly(TypeFilter.IMAGE);
        assertThatThrownBy(() -> SearchParser.parse("type:spreadsheetz")).isInstanceOf(ApiException.class).hasMessageContaining("Unknown type");
    }

    @Test
    void parsesSizeFiltersWithUnitsAndComparisons() {
        assertThat(SearchParser.parse("size:>10mb").sizes()).containsExactly(new SizeFilter(Comparison.GT, 10 * MiB));
        assertThat(SearchParser.parse("size:<=1.5GB").sizes()).containsExactly(new SizeFilter(Comparison.LE, 1536 * MiB));
        assertThat(SearchParser.parse("size:500").sizes()).containsExactly(new SizeFilter(Comparison.EQ, 500));
        assertThatThrownBy(() -> SearchParser.parse("size:big")).hasMessageContaining("size:>10mb");
    }

    @Test
    void parsesModifiedDates() {
        assertThat(SearchParser.parse("modified:<2026-01-01").modified())
                .containsExactly(new DateFilter(Comparison.LT, LocalDate.of(2026, 1, 1)));
        assertThatThrownBy(() -> SearchParser.parse("modified:yesterday")).hasMessageContaining("Invalid date");
        assertThatThrownBy(() -> SearchParser.parse("modified:2026-02-31")).hasMessageContaining("Invalid date");
    }

    @Test
    void parsesInWithAQuotedFolderName() {
        SearchQuery q = SearchParser.parse("invoice in:\"Tax returns\"");
        assertThat(q.text()).isEqualTo("invoice");
        assertThat(q.inFolder()).isEqualTo("Tax returns");
        assertThat(SearchParser.parse("in:Photos").inFolder()).isEqualTo("Photos");
    }

    @Test
    void keepsUnknownKeyValueTokensAsText() {
        assertThat(SearchParser.parse("re:meeting notes").text()).isEqualTo("re:meeting notes");
    }

    @Test
    void normalisesToNfcSoItMatchesStoredNames() {
        assertThat(SearchParser.parse("café").text()).isEqualTo("café");
    }

    @Test
    void knowsWhenThereIsNothingToSearchFor() {
        assertThat(SearchParser.parse("   \"\"  ").isEmpty()).isTrue();
        assertThat(SearchParser.parse("type:pdf").isEmpty()).isFalse();
    }

    @Test
    void skipsFiltersThatAreStillBeingTypedInsteadOfFailing() {
        assertThat(SearchParser.parse("report size:").sizes()).isEmpty();
        assertThat(SearchParser.parse("report size:>").text()).isEqualTo("report");
        SearchQuery q = SearchParser.parse("type: in:");
        assertThat(q.types()).isEmpty();
        assertThat(q.inFolder()).isNull();
        assertThat(SearchParser.parse("in:\"Tax re").inFolder()).isEqualTo("Tax re");
        SearchQuery phrase = SearchParser.parse("\"report fi");
        assertThat(phrase.text()).isEmpty();
        assertThat(phrase.phrases()).containsExactly("report fi");
    }
}
