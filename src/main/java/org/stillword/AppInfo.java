/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 *
 * @author veronique
 */
public final class AppInfo {
    private static final Properties DETAILS = load();
    public static final String VERSION = DETAILS.getProperty("version");
    public static final String DEVELOPER = DETAILS.getProperty("developer");
    public static final String SUPPORT_EMAIL = DETAILS.getProperty("support.email");
    public static final URI WEBSITE = URI.create(DETAILS.getProperty("website"));

    private AppInfo() { }

    private static Properties load() {
        Properties details = new Properties();
        try (var input = AppInfo.class.getResourceAsStream("/stillword.properties")) {
            if (input == null) throw new IOException("Missing application details");
            details.load(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        return details;
    }

    public static String bugReport() {
        return "What happened?\n\n\nSteps to reproduce:\n1. \n2. \n\nExpected result:\n\n\n"
                + "Error message (if any):\n\n\n"
                + "Application: StillWord " + VERSION + "\n"
                + "System: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n"
                + "Java: " + System.getProperty("java.version") + "\n";
    }

    public static URI bugReportUri() {
        return URI.create("mailto:" + SUPPORT_EMAIL + "?subject=" + encode("StillWord " + VERSION + " bug report")
                + "&body=" + encode(bugReport()));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
