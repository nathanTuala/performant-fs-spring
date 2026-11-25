package com.performantfs.demo;

import com.performantfs.auth.AuthService;
import com.performantfs.auth.AuthUser;
import com.performantfs.config.AppProperties;
import com.performantfs.nodes.ConflictPolicy;
import com.performantfs.nodes.NodeService;
import com.performantfs.uploads.UploadService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A ready-to-use account for reviewers, configured by {@code pfs.demo-email} and
 * {@code pfs.demo-password}. Created at startup with sample files (written for this repo), put
 * through the normal upload path so they are deduplicated and indexed like any upload. Its password
 * is reset to the configured one at every start, so the credentials in the README always work.
 * Leave the settings empty and there is no demo account.
 */
@Component
public class DemoAccount {

    private static final Logger log = LoggerFactory.getLogger(DemoAccount.class);

    /**
     * Folder → files, as resource name → name in the account. The searches to try live in the
     * README, not in a file here: a note listing "how do plants make food" would match that search
     * by keyword and spoil the point that no file contains those words.
     */
    private static final Map<String, Map<String, String>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("Coursework", Map.of(
                "biology-lecture-notes.md", "Biology lecture 7 - notes.md",
                "linear-algebra-notes.txt", "Linear algebra week 9.txt"));
        SAMPLES.put("Travel", Map.of(
                "lisbon-trip.txt", "Lisbon long weekend.txt",
                "packing-list.md", "Packing list.md"));
        SAMPLES.put("Home", Map.of(
                "groceries.md", "Groceries.md",
                "budget-2026.csv", "Quarterly budget 2026.csv"));
    }

    private final AppProperties props;
    private final AuthService auth;
    private final NodeService nodes;
    private final UploadService uploads;

    DemoAccount(AppProperties props, AuthService auth, NodeService nodes, UploadService uploads) {
        this.props = props;
        this.auth = auth;
        this.nodes = nodes;
        this.uploads = uploads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensure() {
        if (props.demoEmail().isBlank() || props.demoPassword().isBlank()) return;
        var existing = auth.find(props.demoEmail());
        if (existing.isPresent()) {
            auth.setPassword(existing.get().id(), props.demoPassword());
            return;
        }
        AuthUser user = auth.register(props.demoEmail(), props.demoPassword());
        seed(user);
        log.info("demo account created with sample files: user={}", user.id());
    }

    private void seed(AuthUser user) {
        Map<String, UUID> folders = new LinkedHashMap<>();
        for (var folder : SAMPLES.entrySet()) {
            UUID parent = nodes.createFolder(user.id(), user.rootId(), folder.getKey(), ConflictPolicy.RENAME).id();
            folders.put(folder.getKey(), parent);
            for (var file : folder.getValue().entrySet()) store(user, parent, file.getValue(), read(file.getKey()));
        }
        // The homework is a PDF, to show search reading inside PDFs.
        store(user, folders.get("Coursework"), "CS 374 HW5 - computability.pdf",
                pdf(new String(read("coursework-hw5.txt"), StandardCharsets.UTF_8)));
    }

    private void store(AuthUser user, UUID parent, String name, byte[] bytes) {
        uploads.uploadSmall(user.id(), parent, name, null, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static byte[] read(String resource) {
        try (InputStream in = DemoAccount.class.getResourceAsStream("/demo/" + resource)) {
            if (in == null) throw new IllegalStateException("missing demo resource " + resource);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A real PDF (text, not an image), so content and semantic search can read it. */
    static byte[] pdf(String text) {
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(font, 11);
                content.setLeading(15);
                content.newLineAtOffset(60, 720);
                for (String line : wrap(text, 90)) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> wrap(String text, int width) {
        var lines = new ArrayList<String>();
        for (String paragraph : text.split("\n")) {
            var line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() + word.length() + 1 > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
            lines.add("");
        }
        return lines;
    }
}
