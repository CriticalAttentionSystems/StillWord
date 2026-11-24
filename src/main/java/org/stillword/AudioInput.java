/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 *
 * @author veronique
 */

/** Validated input source; parsing does not download or modify the recording. */
public record AudioInput(Kind kind, String value, String title) {
    public enum Kind { YOUTUBE, AUDIO_FILE, AUDIO_URL, AUDIO_PAGE }
    public static final Set<String> EXTENSIONS = Set.of("mp3", "m4a", "wav", "aiff", "aif", "caf", "flac", "ogg", "oga", "opus", "aac", "amr", "m4b", "mp4", "wma", "webm", "aifc", "au", "snd", "mp2", "ac3");
    public static final long MAX_FILE_BYTES = 1_073_741_824L;

    public boolean isAudio() { return kind != Kind.YOUTUBE; }

    public static AudioInput parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Enter a YouTube or audio-page link, an audio URL, or choose an audio file.");
        String value = input.strip();
        try {
            if (value.startsWith("file:")) {
                URI file = URI.create(value);
                if (file.getRawAuthority() != null || file.getRawQuery() != null || file.getRawFragment() != null)
                    throw new IllegalArgumentException("Choose an audio file on this Mac.");
                return local(Path.of(file));
            }
            if (value.startsWith("/") || value.startsWith("./") || value.startsWith("../") || value.startsWith("~/")) {
                if (value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
                return local(Path.of(value));
            }
            if (!value.contains("://") && Files.exists(Path.of(value))) return local(Path.of(value));
            URI uri = URI.create(value.contains("://") ? value : "https://" + value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host.isBlank() || uri.getRawUserInfo() != null)
                throw new IllegalArgumentException("Use a public HTTP or HTTPS audio link without embedded sign-in details.");
            if (Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be").contains(host))
                return new AudioInput(Kind.YOUTUBE, TranscriptService.validateUrl(value), "YouTube audio transcript");
            String path = uri.getPath();
            // Preserve existing URL escapes and signed query parameters exactly.
            String normalized = uri.toString().split("#", 2)[0];
            return supportedName(path)
                    ? new AudioInput(Kind.AUDIO_URL, normalized, titleFor(path.substring(path.lastIndexOf('/') + 1)))
                    : new AudioInput(Kind.AUDIO_PAGE, normalized, "Audio transcript");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("The source is not a readable audio file or supported link.", e); }
    }

    private static AudioInput local(Path file) throws Exception {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || !Files.isReadable(absolute)) throw new IllegalArgumentException("The selected audio file is missing or cannot be read: " + absolute);
        long size = Files.size(absolute);
        if (size == 0 || size > MAX_FILE_BYTES) throw new IllegalArgumentException("Choose a nonempty audio file no larger than 1 GB.");
        return new AudioInput(Kind.AUDIO_FILE, absolute.toString(), titleFor(absolute.getFileName().toString()));
    }

    public static boolean supportedName(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String titleFor(String name) {
        int dot = name.lastIndexOf('.');
        String title = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').strip();
        return title.isBlank() ? "Audio transcript" : title;
    }
}
