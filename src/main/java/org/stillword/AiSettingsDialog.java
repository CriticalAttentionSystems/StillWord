/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.List;

/**
 *
 * @author veronique
 */

/** Routes web subscriptions separately from local inference and developer API access. */
final class AiSettingsDialog {
    private record Preset(String name, String endpoint, String model, SummaryService.Protocol protocol) {
        @Override public String toString() { return name; }
    }
    private static final Preset CUSTOM = new Preset("Custom provider / existing settings", "", "", SummaryService.Protocol.OPENAI_COMPATIBLE);
    private static final Preset[] PRESETS = {
            CUSTOM,
            new Preset("OpenAI API", "https://api.openai.com/v1", "gpt-6-luna", SummaryService.Protocol.OPENAI_COMPATIBLE),
            new Preset("Anthropic Claude API", "https://api.anthropic.com/v1", "", SummaryService.Protocol.ANTHROPIC),
            new Preset("Google Gemini API", "https://generativelanguage.googleapis.com/v1beta/openai", "", SummaryService.Protocol.OPENAI_COMPATIBLE),
            new Preset("OpenRouter API", "https://openrouter.ai/api/v1", "", SummaryService.Protocol.OPENAI_COMPATIBLE),
            new Preset("Other OpenAI-compatible API", "", "", SummaryService.Protocol.OPENAI_COMPATIBLE)
    };

    static boolean show(Component owner, AppSettings settings) {
        JComboBox<AppSettings.AiMode> mode = new JComboBox<>(AppSettings.AiMode.values()); mode.setSelectedItem(settings.aiMode);
        JTextField endpoint = field(settings.endpoint),
                localEndpoint = field(settings.localEndpoint), localModel = field(settings.localModel),
                website = field(settings.browserWebsite), executable = field(settings.executable), language = field(settings.language);
        JComboBox<AiModelChoices.ApiModel> model = modelSelector(settings.endpoint, settings.protocol, settings.model);
        JPasswordField key = new JPasswordField(settings.apiKey, 36); shortcuts(key);
        JComboBox<Preset> presets = new JComboBox<>(PRESETS);
        JComboBox<SummaryService.Protocol> protocol = new JComboBox<>(SummaryService.Protocol.values()); protocol.setSelectedItem(settings.protocol);
        JComboBox<String> websites = new JComboBox<>(new String[]{"Custom / current website", "ChatGPT", "Claude", "Gemini", "Copilot", "Perplexity"});
        String[] siteUrls = {settings.browserWebsite, "https://chatgpt.com/", "https://claude.ai/new", "https://gemini.google.com/app", "https://copilot.microsoft.com/", "https://www.perplexity.ai/"};
        websites.addActionListener(e -> { if (websites.getSelectedIndex() > 0) website.setText(siteUrls[websites.getSelectedIndex()]); });
        // A credential for one endpoint must never silently travel to a newly selected provider.
        endpoint.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                key.setText("");
                refreshModels(model, endpoint.getText(), (SummaryService.Protocol) protocol.getSelectedItem(), modelId(model));
            }
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
        SummaryService.Protocol[] previousProtocol = {settings.protocol};
        protocol.addActionListener(e -> {
            SummaryService.Protocol selectedProtocol = (SummaryService.Protocol) protocol.getSelectedItem();
            if (selectedProtocol != previousProtocol[0]) key.setText("");
            previousProtocol[0] = selectedProtocol;
            refreshModels(model, endpoint.getText(), selectedProtocol, modelId(model));
        });
        presets.addActionListener(e -> {
            Preset preset = (Preset)presets.getSelectedItem();
            if (preset != CUSTOM) {
                if (!endpoint.getText().equals(preset.endpoint())) endpoint.setText(preset.endpoint());
                protocol.setSelectedItem(preset.protocol());
                refreshModels(model, preset.endpoint(), preset.protocol(), preset.model());
            }
        });

        JPanel local = form();
        add(local, "Local server", localEndpoint); add(local, "Installed model", localModel);
        note(local, "<html>gpt-oss 20B is OpenAI's free open-weight model.<br>Use <b>Set up</b> beside the model dropdown to download it.<br>The model needs about 16 GB of memory and a large download.<br>GPT-5 and GPT-6 Luna use paid APIs; they are not free local models.</html>");
        JPanel browser = form();
        add(browser, "Your AI website", websites); add(browser, "Website address", website);
        note(browser, "<html>Use an AI you already subscribe to, or any other AI website.<br><b>Prepare prompt</b> copies the full transcript and your guidance.<br>Open your AI, paste and submit there, then copy its answer back<br>using <b>Paste summary</b>. This mode does not call a paid API.<br>Your website's message and context limits still apply.</html>");
        JPanel api = form();
        add(api, "Provider preset", presets); add(api, "API format", protocol); add(api, "API base URL", endpoint);
        add(api, "Model — choose or enter an available model ID", model); add(api, "API key — this session only", key);
        note(api, "<html>Automatic summaries use the selected provider's API.<br>Website subscriptions and API access may have separate billing.<br>Enter a model enabled for your API account. Keys stay in memory.<br>Changing the endpoint clears the key to protect your credentials.</html>");
        JPanel cards = new JPanel(new CardLayout()); cards.setOpaque(false);
        cards.add(local, AppSettings.AiMode.LOCAL.name()); cards.add(browser, AppSettings.AiMode.BROWSER.name()); cards.add(api, AppSettings.AiMode.API.name());
        mode.addActionListener(e -> ((CardLayout)cards.getLayout()).show(cards, ((AppSettings.AiMode)mode.getSelectedItem()).name()));
        ((CardLayout)cards.getLayout()).show(cards, settings.aiMode.name());
        JPanel form = form(); form.setBorder(new EmptyBorder(10, 12, 10, 12));
        add(form, "Summary connection", mode); form.add(cards);
        form.add(Box.createVerticalStrut(10)); add(form, "yt-dlp executable (blank = automatic)", executable); add(form, "Caption language", language);
        while (JOptionPane.showConfirmDialog(owner, form, "AI & settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                AppSettings.AiMode selected = (AppSettings.AiMode)mode.getSelectedItem();
                String selectedLanguage = language.getText().strip().isEmpty() ? "en.*" : language.getText().strip();
                if (!selectedLanguage.equals("en.*") && !selectedLanguage.matches("en(?:-[A-Za-z0-9]+)*"))
                    throw new IllegalArgumentException("Use en.* or an English caption tag such as en-US.");
                if (selected == AppSettings.AiMode.LOCAL) {
                    validateEndpoint(localEndpoint.getText(), true);
                    if (localModel.getText().isBlank()) throw new IllegalArgumentException("Enter the installed local model name.");
                } else if (selected == AppSettings.AiMode.BROWSER) BrowserAiService.validateWebsite(website.getText());
                else {
                    validateEndpoint(endpoint.getText(), false);
                    if (modelId(model).isBlank()) throw new IllegalArgumentException("Choose or enter an available model ID from your provider.");
                }
                settings.aiMode = selected; settings.localEndpoint = localEndpoint.getText().strip(); settings.localModel = localModel.getText().strip();
                settings.browserWebsite = website.getText().strip(); settings.endpoint = endpoint.getText().strip(); settings.model = modelId(model);
                settings.protocol = (SummaryService.Protocol)protocol.getSelectedItem();
                char[] password = key.getPassword(); settings.apiKey = new String(password).strip(); java.util.Arrays.fill(password, '\0');
                settings.executable = executable.getText().strip(); settings.language = selectedLanguage; settings.save();
                return true;
            } catch (IllegalArgumentException error) { JOptionPane.showMessageDialog(owner, error.getMessage(), "Check settings", JOptionPane.ERROR_MESSAGE); }
        }
        return false;
    }

    /** Fee labels appear in the list; the editable field and requests always contain the raw model ID. */
    static JComboBox<AiModelChoices.ApiModel> modelSelector(String endpoint, SummaryService.Protocol protocol, String current) {
        JComboBox<AiModelChoices.ApiModel> model = new JComboBox<>();
        model.setEditable(true);
        model.setEditor(new javax.swing.plaf.basic.BasicComboBoxEditor() {
            @Override public void setItem(Object item) { super.setItem(item instanceof AiModelChoices.ApiModel option ? option.id() : item); }
        });
        shortcuts((JTextComponent) model.getEditor().getEditorComponent());
        model.setToolTipText("Choose a listed model, or type any model ID enabled for your API account. Provider fees may apply.");
        refreshModels(model, endpoint, protocol, current);
        return model;
    }

    static String modelId(JComboBox<AiModelChoices.ApiModel> model) {
        Object item = model.getEditor().getItem();
        return item instanceof AiModelChoices.ApiModel option ? option.id() : item == null ? "" : item.toString().strip();
    }

    private static void refreshModels(JComboBox<AiModelChoices.ApiModel> model, String endpoint, SummaryService.Protocol protocol, String current) {
        List<AiModelChoices.ApiModel> choices = AiModelChoices.apiModels(endpoint, protocol, current);
        model.setModel(new DefaultComboBoxModel<>(choices.toArray(AiModelChoices.ApiModel[]::new)));
        Object selection = choices.stream().filter(option -> option.id().equals(current)).findFirst().map(option -> (Object) option).orElse(current);
        model.setSelectedItem(selection);
        model.getEditor().setItem(selection);
    }

    static void validateEndpoint(String input, boolean requireLocal) {
        URI uri;
        try { uri = URI.create(input.strip()); } catch (Exception e) { throw new IllegalArgumentException("Enter a valid API URL."); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("The API URL must have a hostname and no credentials, query, or fragment.");
        boolean local = List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        if (requireLocal && !local) throw new IllegalArgumentException("Free local mode requires a server on this Mac. Use API mode for remote services.");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(local && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Use HTTPS for remote services; HTTP is allowed only on this Mac.");
    }
    private static JPanel form() { JPanel panel = new JPanel(); panel.setOpaque(false); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel; }
    private static JTextField field(String text) { JTextField result = new JTextField(text, 38); shortcuts(result); return result; }
    private static void add(JPanel panel, String name, JComponent input) {
        JLabel label = new JLabel(name); label.setForeground(StillWord.INK); label.setFont(StillWord.BODY.deriveFont(12f));
        label.setAlignmentX(0); input.setAlignmentX(0); input.setFont(StillWord.BODY); input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        input.getAccessibleContext().setAccessibleName(name);
        panel.add(label); panel.add(Box.createVerticalStrut(3)); panel.add(input); panel.add(Box.createVerticalStrut(8));
    }
    private static void note(JPanel panel, String text) { JLabel note = new JLabel(text); note.setFont(StillWord.BODY.deriveFont(11f)); note.setForeground(StillWord.MUTED); note.setAlignmentX(0); panel.add(note); }
    private static void shortcuts(JTextComponent text) {
        int mask = System.getProperty("os.name", "").startsWith("Mac") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask), DefaultEditorKit.selectAllAction);
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, mask), DefaultEditorKit.copyAction);
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask), DefaultEditorKit.pasteAction);
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X, mask), DefaultEditorKit.cutAction);
    }
}
