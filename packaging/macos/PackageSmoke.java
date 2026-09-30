/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author veronique
 */
public final class PackageSmoke {
    public static void main(String[] args) throws Exception {
        Path app = Path.of(args[0]).toRealPath();
        Path home = Path.of(args[1]).toAbsolutePath();
        System.setProperty("user.home", home.toString());
        Path downloader = app.resolve("Contents/app/tools/yt-dlp/yt-dlp_macos");
        if (!Path.of(TranscriptService.resolveExecutable("")).toRealPath().equals(downloader))
            throw new AssertionError("Downloader escaped installed app");
        List<String> command = new ArrayList<>(List.of(downloader.toString()));
        TranscriptService.addBundledJavaScriptRuntime(command, downloader.toString());
        if (!command.contains("node:" + app.resolve("Contents/app/tools/node/node")))
            throw new AssertionError("Bundled JavaScript runtime not selected: " + command);
        Path data = home.resolve("Library/Application Support/Stillword");
        var speech = LocalSpeechService.runtimeFiles(app.resolve("Contents/app/StillWord.jar"),
                Path.of("/private/tmp"), home, null);
        if (!speech.directory().equals(data.resolve("speech")))
            throw new AssertionError("Speech setup is not in user storage");
        if (!Files.isRegularFile(speech.setup())) throw new AssertionError("Missing bundled speech setup");
        if (!LocalAiService.dataDirectory().equals(data.resolve("local-ai")))
            throw new AssertionError("Local AI storage escaped user data directory");
        if (!AppInfo.SUPPORT_EMAIL.equals("criticalattentionsystems@gmail.com"))
            throw new AssertionError("Missing developer contact");
        if (StillWord.class.getResource("/images/monastery.png") == null)
            throw new AssertionError("Missing banner image");
        System.out.println("PASS: bundled downloader, Node, speech setup, user data paths, contact and artwork");
        if (args.length > 2) {
            TranscriptService.Result result = new TranscriptService().fetch(args[2], "", "en.*", System.out::println);
            if (result.text().isBlank()) throw new AssertionError("Empty live transcript");
            System.out.println("PASS: live caption retrieval; " + result.text().length() + " characters, " + result.cues().size() + " cues");
        }
    }
}
