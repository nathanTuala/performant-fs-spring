package com.performantfs.search;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Pulls searchable text out of file bytes. The bytes decide what a file is, not its name: the
 * same blob can be stored under many names, and a name can lie.
 * <ul>
 *   <li>PDF (a {@code %PDF-} header) is read with PDFBox;
 *   <li>UTF-8 text (no NUL bytes, valid UTF-8 at the start) is read as is: plain text, Markdown,
 *       CSV, JSON, source code;
 *   <li>everything else is unsupported.
 * </ul>
 * Every input is untrusted, so each reader is bounded in bytes read, characters kept, pages
 * parsed and time spent. Whitespace is collapsed to single spaces, which keeps snippets on one
 * line and the stored text small.
 */
public final class TextExtractor {

    /** What came out of a file. */
    public sealed interface Result {
        record Text(String text) implements Result {}

        record Unsupported() implements Result {}

        record Failed(String reason) implements Result {}
    }

    /**
     * @param maxChars    text kept per file; the rest is not searchable. Also keeps the tsvector
     *                    well under Postgres's 1 MB limit.
     * @param maxPdfBytes larger PDFs are skipped (a PDF is parsed in memory)
     * @param maxPdfPages pages read per PDF
     * @param timeLimit   time spent per file, checked as each page starts and as text comes out
     */
    public record Limits(int maxChars, long maxPdfBytes, int maxPdfPages, Duration timeLimit) {
        public static final Limits DEFAULT = new Limits(500_000, 64L << 20, 1_000, Duration.ofSeconds(20));
    }

    private static final int SNIFF_BYTES = 8 * 1024;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    // Runs of whitespace and control characters (NUL is not allowed in Postgres text at all).
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Cntrl}\\u00A0\\u2028\\u2029\\uFEFF]+");

    private final Limits limits;

    public TextExtractor(Limits limits) {
        this.limits = limits;
    }

    public Result extract(InputStream source, long size) throws IOException {
        var in = new BufferedInputStream(source, SNIFF_BYTES);
        in.mark(SNIFF_BYTES);
        byte[] head = in.readNBytes(SNIFF_BYTES);
        in.reset();
        if (isPdf(head)) return size > limits.maxPdfBytes() ? new Result.Failed("too large") : pdf(in);
        if (isUtf8Text(head)) return text(in);
        return new Result.Unsupported();
    }

    /** The spec lets a PDF header sit anywhere in the first 1024 bytes. */
    static boolean isPdf(byte[] head) {
        int end = Math.min(head.length, 1024) - PDF_MAGIC.length;
        outer:
        for (int i = 0; i <= end; i++) {
            for (int j = 0; j < PDF_MAGIC.length; j++) if (head[i + j] != PDF_MAGIC[j]) continue outer;
            return true;
        }
        return false;
    }

    /**
     * Valid UTF-8 with no NUL bytes. A multi-byte character cut off by the end of the sample
     * is fine: only bytes that can never be UTF-8 count against it.
     */
    static boolean isUtf8Text(byte[] head) {
        if (head.length == 0) return false;
        for (byte b : head) if (b == 0) return false;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CoderResult result = decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length), false);
        return !result.isError();
    }

    private Result text(InputStream in) throws IOException {
        // Four bytes per character at most, so this always yields maxChars when there is that much.
        byte[] bytes = in.readNBytes(Math.multiplyExact(limits.maxChars(), 4));
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return new Result.Text(clean(decoder.decode(ByteBuffer.wrap(bytes))));
        } catch (CharacterCodingException e) { // not thrown with REPLACE
            return new Result.Failed("undecodable");
        }
    }

    private Result pdf(InputStream in) throws IOException {
        var out = new StringBuilder();
        long deadline = System.nanoTime() + limits.timeLimit().toNanos();
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            var stripper = new BoundedStripper(out, limits.maxChars(), deadline);
            stripper.setEndPage(limits.maxPdfPages());
            stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            return new Result.Failed("encrypted");
        } catch (Full e) {
            // Enough text: index what we have.
        } catch (OutOfTime e) {
            if (out.isEmpty()) return new Result.Failed("too slow");
        } catch (IOException | RuntimeException e) {
            return new Result.Failed("unreadable");
        }
        return new Result.Text(clean(out));
    }

    private String clean(CharSequence raw) {
        String text = SPACE.matcher(raw).replaceAll(" ").strip();
        if (text.length() <= limits.maxChars()) return text;
        int end = limits.maxChars();
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--; // don't split a character in two
        return text.substring(0, end);
    }

    /** Stops PDFBox once it has produced enough text or used up its time. */
    private static final class BoundedStripper extends PDFTextStripper {
        private final StringBuilder out;
        private final int maxChars;
        private final long deadline;

        BoundedStripper(StringBuilder out, int maxChars, long deadline) {
            this.out = out;
            this.maxChars = maxChars;
            this.deadline = deadline;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            checkTime();
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            checkTime();
            out.append(text, 0, Math.clamp(maxChars - out.length(), 0, text.length())).append(' ');
            if (out.length() >= maxChars) throw new Full();
        }

        @Override
        protected void writeLineSeparator() {
            out.append(' ');
        }

        @Override
        protected void writeWordSeparator() {
            out.append(' ');
        }

        private void checkTime() {
            if (System.nanoTime() > deadline) throw new OutOfTime();
        }
    }

    private static final class Full extends RuntimeException {
        Full() {
            super(null, null, false, false);
        }
    }

    private static final class OutOfTime extends RuntimeException {
        OutOfTime() {
            super(null, null, false, false);
        }
    }
}
