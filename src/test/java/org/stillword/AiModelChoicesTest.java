/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author veronique
 */

/** Offline checks for model selection, fee labels, custom settings, and credential isolation. */
public final class AiModelChoicesTest {
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        AppSettings settings = new AppSettings();
        settings.aiMode = AppSettings.AiMode.LOCAL;
        settings.localEndpoint = SummaryService.LOCAL_ENDPOINT;
        settings.localModel = LocalAiService.MODEL;
        equal(AiModelChoices.LOCAL, AiModelChoices.selected(settings));
        check(AiModelChoices.LOCAL.label().contains("Free"), "Local choice clearly states that it is free");
        check(AiModelChoices.GPT5.label().contains("Fees") && AiModelChoices.LUNA.label().contains("Fees"), "Hosted OpenAI choices disclose fees");
        check(!AiModelChoices.GPT5.label().contains("Free"), "GPT-5 is not presented as a free local model");

        settings.endpoint = SummaryService.OPENAI_ENDPOINT;
        settings.protocol = SummaryService.Protocol.OPENAI_COMPATIBLE;
        settings.apiKey = "synthetic-openai-key";
        check(AiModelChoices.apply(settings, AiModelChoices.GPT5), "GPT-5 choice can be applied");
        equal(AppSettings.AiMode.API, settings.aiMode);
        equal("gpt-5", settings.summaryConfig().model());
        equal("synthetic-openai-key", settings.apiKey);
        AiModelChoices.apply(settings, AiModelChoices.LUNA);
        equal("gpt-6-luna", settings.model);
        equal("synthetic-openai-key", settings.apiKey);
        equal(AiModelChoices.LUNA, AiModelChoices.selected(settings));

        settings.endpoint = "https://api.anthropic.com/v1";
        settings.protocol = SummaryService.Protocol.ANTHROPIC;
        settings.apiKey = "synthetic-anthropic-key";
        AiModelChoices.apply(settings, AiModelChoices.LUNA);
        equal("", settings.apiKey);
        equal(SummaryService.OPENAI_ENDPOINT, settings.endpoint);
        equal(SummaryService.Protocol.OPENAI_COMPATIBLE, settings.protocol);
        settings.protocol = SummaryService.Protocol.ANTHROPIC;
        settings.apiKey = "synthetic-wrong-protocol-key";
        AiModelChoices.apply(settings, AiModelChoices.GPT5);
        equal("", settings.apiKey);

        settings.apiKey = "synthetic-openai-key";
        AiModelChoices.apply(settings, AiModelChoices.LOCAL);
        equal(AppSettings.AiMode.LOCAL, settings.aiMode);
        equal("", settings.summaryConfig().apiKey());
        equal("synthetic-openai-key", settings.apiKey);
        List<AiModelChoices.Choice> choices = AiModelChoices.available(settings);
        for (String website : List.of("https://chatgpt.com/", "https://claude.ai/new", "https://gemini.google.com/app",
                "https://copilot.microsoft.com/", "https://www.perplexity.ai/")) {
            AiModelChoices.Choice choice = choices.stream().filter(item -> item.website().equals(website)).findFirst().orElseThrow();
            AiModelChoices.apply(settings, choice);
            equal(AppSettings.AiMode.BROWSER, settings.aiMode);
            equal(website, settings.browserWebsite);
            equal("synthetic-openai-key", settings.apiKey);
            equal(choice, AiModelChoices.selected(settings));
        }
        check(choices.stream().anyMatch(choice -> choice.website().equals("https://chatgpt.com/")
                && choice.label().contains("Free / subscription")), "ChatGPT website account options are truthful");
        AppSettings.AiMode previous = settings.aiMode;
        check(!AiModelChoices.apply(settings, AiModelChoices.CONFIGURE), "Custom AI requests settings without replacing the current connection");
        equal(previous, settings.aiMode);

        settings.aiMode = AppSettings.AiMode.API;
        settings.endpoint = "https://custom.example/v2";
        settings.model = "my-approved-model";
        settings.protocol = SummaryService.Protocol.ANTHROPIC;
        settings.apiKey = "synthetic-custom-key";
        AiModelChoices.Choice custom = AiModelChoices.selected(settings);
        check(custom.label().contains("my-approved-model") && custom.label().contains("Fees may apply"), "Actual custom model and fee status displayed");
        check(AiModelChoices.available(settings).contains(custom), "Custom configuration remains selectable");
        AiModelChoices.apply(settings, custom);
        equal("synthetic-custom-key", settings.apiKey);
        equal("https://custom.example/v2", settings.endpoint);
        equal(SummaryService.Protocol.ANTHROPIC, settings.protocol);

        settings.aiMode = AppSettings.AiMode.LOCAL;
        settings.localEndpoint = "http://localhost:1234/v1";
        settings.localModel = "another-local-model";
        custom = AiModelChoices.selected(settings);
        check(custom.label().contains("another-local-model") && custom.label().contains("Free"), "Custom local model displayed honestly");
        AiModelChoices.apply(settings, custom);
        equal("http://localhost:1234/v1", settings.localEndpoint);
        equal("another-local-model", settings.localModel);

        equal(2, AiModelChoices.apiModels(SummaryService.OPENAI_ENDPOINT, SummaryService.Protocol.OPENAI_COMPATIBLE, "gpt-5").size());
        equal(3, AiModelChoices.apiModels(SummaryService.OPENAI_ENDPOINT, SummaryService.Protocol.OPENAI_COMPATIBLE, "custom-openai-model").size());
        equal(0, AiModelChoices.apiModels("https://api.anthropic.com/v1", SummaryService.Protocol.ANTHROPIC, "").size());
        equal(1, AiModelChoices.apiModels("https://custom.example", SummaryService.Protocol.OPENAI_COMPATIBLE, "actual-model").size());

        SwingUtilities.invokeAndWait(() -> {
            JComboBox<AiModelChoices.ApiModel> combo = AiSettingsDialog.modelSelector(SummaryService.OPENAI_ENDPOINT,
                    SummaryService.Protocol.OPENAI_COMPATIBLE, "gpt-6-luna");
            check(combo.isEditable(), "Settings model dropdown accepts provider-specific model IDs");
            equal("gpt-6-luna", AiSettingsDialog.modelId(combo));
            check(combo.getItemAt(0).toString().contains("Fees"), "Dropdown list displays the fee label");
            combo.setSelectedIndex(1);
            equal("gpt-5", AiSettingsDialog.modelId(combo));
            combo.getEditor().setItem("  newly-enabled-model  ");
            equal("newly-enabled-model", AiSettingsDialog.modelId(combo));
            JComboBox<AiModelChoices.ApiModel> customCombo = AiSettingsDialog.modelSelector("https://custom.example/v1",
                    SummaryService.Protocol.ANTHROPIC, "my-provider-model");
            equal(1, customCombo.getItemCount());
            equal("my-provider-model", AiSettingsDialog.modelId(customCombo));
            customCombo.setSelectedIndex(0);
            equal("my-provider-model", AiSettingsDialog.modelId(customCombo));
        });
        System.out.println("AiModelChoicesTest: " + checks + " checks passed");
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
