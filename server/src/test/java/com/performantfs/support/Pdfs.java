package com.performantfs.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small PDFs for tests: one page per string. */
public final class Pdfs {

    private Pdfs() {}

    public static byte[] withPages(String... pages) {
        return build(null, pages);
    }

    /** A PDF that needs a password to open. */
    public static byte[] encrypted(String password, String... pages) {
        return build(password, pages);
    }

    private static byte[] build(String password, String... pages) {
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                var page = new PDPage();
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            if (password != null) {
                var policy = new StandardProtectionPolicy(password + "-owner", password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
