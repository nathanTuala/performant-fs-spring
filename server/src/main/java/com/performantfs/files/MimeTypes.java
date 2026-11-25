package com.performantfs.files;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MIME types. The extension decides; what the client claims is only a fallback, because a client
 * can claim text/plain for an .html file.
 */
public final class MimeTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp"), Map.entry("ico", "image/x-icon"), Map.entry("svg", "image/svg+xml"),
            Map.entry("heic", "image/heic"), Map.entry("mp4", "video/mp4"), Map.entry("m4v", "video/mp4"),
            Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"), Map.entry("mkv", "video/x-matroska"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("m4a", "audio/mp4"), Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"), Map.entry("oga", "audio/ogg"), Map.entry("flac", "audio/flac"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"), Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"), Map.entry("log", "text/plain"), Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"), Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("doc", "application/msword"), Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), Map.entry("ppt", "application/vnd.ms-powerpoint"), Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"), Map.entry("gz", "application/gzip"), Map.entry("tar", "application/x-tar"),
            Map.entry("7z", "application/x-7z-compressed"), Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
            Map.entry("ts", "text/x-typescript"), Map.entry("tsx", "text/x-typescript"), Map.entry("jsx", "text/javascript"),
            Map.entry("css", "text/css"), Map.entry("py", "text/x-python"), Map.entry("go", "text/x-go"),
            Map.entry("rs", "text/x-rust"), Map.entry("java", "text/x-java"), Map.entry("c", "text/x-c"),
            Map.entry("h", "text/x-c"), Map.entry("cpp", "text/x-c++"), Map.entry("sh", "text/x-shellscript"),
            Map.entry("yml", "text/yaml"), Map.entry("yaml", "text/yaml"), Map.entry("toml", "text/plain"),
            Map.entry("sql", "text/x-sql"), Map.entry("rb", "text/x-ruby"), Map.entry("php", "text/x-php"),
            Map.entry("swift", "text/x-swift"), Map.entry("kt", "text/x-kotlin"), Map.entry("dart", "text/x-dart"));

    private static final Pattern WELL_FORMED =
            Pattern.compile("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$", Pattern.CASE_INSENSITIVE);

    private MimeTypes() {}

    public static String resolve(String name, String claimed) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String known = BY_EXTENSION.get(ext);
        if (known != null) return known;
        if (claimed != null && WELL_FORMED.matcher(claimed).matches()) return claimed.toLowerCase(Locale.ROOT);
        return "application/octet-stream";
    }

    /**
     * Types the browser may render inline from our origin. Anything that can run script (HTML,
     * SVG, XML) is excluded: it is served as an attachment, or as text/plain for previews.
     */
    public static boolean isSafeInline(String mime) {
        if (mime.equals("image/svg+xml")) return false;
        return mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") || mime.equals("application/pdf");
    }

    public static boolean isTextLike(String mime) {
        return mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/xml")
                || mime.equals("image/svg+xml");
    }
}
