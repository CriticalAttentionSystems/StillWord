/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author veronique
 */

/** Model and account choices. Applying a choice only changes configuration; it never contacts an AI. */
final class AiModelChoices {
    record Choice(String label, AppSettings.AiMode mode, String endpoint, String model,
                  SummaryService.Protocol protocol, String website, boolean configure, String detail) {
        @Override public String toString() { return label; }
    }

    record ApiModel(String id, String label) {
        @Override public String toString() { return label; }
    }

    static final Choice LOCAL = new Choice("gpt-oss 20B — Free, local", AppSettings.AiMode.LOCAL,
            SummaryService.LOCAL_ENDPOINT, LocalAiService.MODEL, SummaryService.Protocol.OPENAI_COMPATIBLE, "", false,
            "OpenAI's free open-weight model. Runs on this Mac after a one-time download; no API fees.");
    static final Choice LUNA = api("GPT-6 Luna — Fees, API", "gpt-6-luna");
    static final Choice GPT5 = api("GPT-5 — Fees, API", "gpt-5");
    static final Choice CONFIGURE = new Choice("Custom AI…", null, "", "", null, "", true,
            "Choose a custom model, provider, website, or local server in AI settings.");
    private static final List<Choice> PRESETS = List.of(LOCAL, LUNA, GPT5,
            browser("ChatGPT — Free / subscription", "https://chatgpt.com/"),
            browser("Claude — Your plan, browser", "https://claude.ai/new"),
            browser("Gemini — Your plan, browser", "https://gemini.google.com/app"),
            browser("Copilot — Your plan, browser", "https://copilot.microsoft.com/"),
            browser("Perplexity — Your plan, browser", "https://www.perplexity.ai/"));

    private AiModelChoices() { }

    static List<Choice> available(AppSettings settings) {
        List<Choice> result = new ArrayList<>(PRESETS);
        Choice current = selected(settings);
        if (!result.contains(current)) result.add(current);
        result.add(CONFIGURE);
        return List.copyOf(result);
    }

    static Choice selected(AppSettings settings) {
        for (Choice choice : PRESETS) {
            if (choice.mode() != settings.aiMode) continue;
            if (settings.aiMode == AppSettings.AiMode.LOCAL && StillWord.isManagedLocal(settings.summaryConfig())) return choice;
            if (settings.aiMode == AppSettings.AiMode.API && Objects.equals(choice.endpoint(), settings.endpoint)
                    && Objects.equals(choice.model(), settings.model) && choice.protocol() == settings.protocol) return choice;
            if (settings.aiMode == AppSettings.AiMode.BROWSER && Objects.equals(choice.website(), settings.browserWebsite)) return choice;
        }
        return switch (settings.aiMode) {
            case LOCAL -> new Choice(settings.localModel + " — Free, local", settings.aiMode,
                    settings.localEndpoint, settings.localModel, SummaryService.Protocol.OPENAI_COMPATIBLE, "", false,
                    settings.localModel + " · " + settings.localEndpoint + " · Runs on this Mac without API fees.");
            case API -> new Choice(settings.model + " — Fees may apply", settings.aiMode,
                    settings.endpoint, settings.model, settings.protocol, "", false,
                    settings.model + " · " + settings.endpoint + " · Your provider's API rates apply.");
            case BROWSER -> new Choice(websiteName(settings.browserWebsite) + " — Your plan", settings.aiMode,
                    "", "", null, settings.browserWebsite, false,
                    settings.browserWebsite + " · Prepare a prompt, submit it in your account, and paste the answer back.");
        };
    }

    /** Returns false for the settings action. Keys only survive changes that retain their API endpoint and protocol. */
    static boolean apply(AppSettings settings, Choice choice) {
        if (choice == null || choice.configure()) return false;
        switch (choice.mode()) {
            case LOCAL -> { settings.localEndpoint = choice.endpoint(); settings.localModel = choice.model(); }
            case BROWSER -> settings.browserWebsite = choice.website();
            case API -> {
                if (!Objects.equals(settings.endpoint, choice.endpoint()) || settings.protocol != choice.protocol()) settings.apiKey = "";
                settings.endpoint = choice.endpoint(); settings.model = choice.model(); settings.protocol = choice.protocol();
            }
        }
        settings.aiMode = choice.mode();
        return true;
    }

    static List<ApiModel> apiModels(String endpoint, SummaryService.Protocol protocol, String current) {
        List<ApiModel> result = new ArrayList<>();
        if (isOpenAiEndpoint(endpoint) && protocol == SummaryService.Protocol.OPENAI_COMPATIBLE) {
            result.add(new ApiModel("gpt-6-luna", "GPT-6 Luna — Fees, API"));
            result.add(new ApiModel("gpt-5", "GPT-5 — Fees, API"));
        }
        String id = current == null ? "" : current.strip();
        if (!id.isEmpty() && result.stream().noneMatch(option -> option.id().equals(id)))
            result.add(new ApiModel(id, id + " — Fees may apply"));
        return List.copyOf(result);
    }

    private static boolean isOpenAiEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint.strip());
            return "https".equalsIgnoreCase(uri.getScheme()) && "api.openai.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception exception) { return false; }
    }

    private static Choice api(String label, String model) {
        return new Choice(label, AppSettings.AiMode.API, SummaryService.OPENAI_ENDPOINT, model,
                SummaryService.Protocol.OPENAI_COMPATIBLE, "", false,
                model + " uses the paid OpenAI API and requires an API key. ChatGPT subscriptions do not pay for API usage.");
    }

    private static Choice browser(String label, String website) {
        return new Choice(label, AppSettings.AiMode.BROWSER, "", "", null, website, false,
                "Use your account in the browser. Available models and fees depend on your plan; select the model on that website.");
    }

    private static String websiteName(String website) {
        try { String host = URI.create(website).getHost(); return host == null ? "Custom AI website" : host; }
        catch (Exception exception) { return "Custom AI website"; }
    }
}
