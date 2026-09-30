/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 *
 * @author veronique
 */

/** A small, dependency-free desktop studio. Network and process work stays off the EDT. */
public final class StillWord {
    static final Color PAPER = new Color(246, 244, 237), INK = new Color(29, 62, 53),
            MUTED = new Color(104, 119, 107), LINE = new Color(220, 225, 214),
            WHITE = new Color(255, 254, 250), GOLD = new Color(189, 139, 72);
    static final Font BODY = new Font("Avenir Next", Font.PLAIN, 13);
    private final AppSettings settings = new AppSettings();
    private JFrame frame;
    private final JPanel root = new JPanel(new BorderLayout(0, 14));
    private final JTextField url = new LinkField();
    private final JCheckBox segment = new JCheckBox("Select a portion");
    private final JTextField start = field("00:00", 6), end = field("", 6);
    private final HintArea transcript = new HintArea("Choose captions or local audio below, then get the transcript.\nYou can edit the text here before summarizing.");
    private final HintArea summary = new HintArea("Your summary will appear here.\nAdd guidance below and create a summary with your selected AI.");
    private final HintArea guidance = new HintArea("e.g. Omit introductions, announcements and closing Q&A. Focus on practical teachings.");
    private final JLabel transcriptCount = label("0 words", 11, MUTED), summaryCount = label("0 words", 11, MUTED);
    private final JLabel source = label("No video loaded", 12, MUTED), status = label("", 12, MUTED);
    private final JLabel reviewHint = label(" ", 12, MUTED);
    private final JButton playbackLink = new JButton(), copyPlaybackLink = button("Copy link", false);
    private final JLabel playbackExcerpt = label("Click any word to show its video link.", 11, MUTED);
    private final JComboBox<AiModelChoices.Choice> modelChoice = new JComboBox<>();
    private final JComboBox<SummaryStyle> summaryStyleChoice = new JComboBox<>(SummaryStyle.values());
    private final JComboBox<AppSettings.TranscriptMode> transcriptSource = new JComboBox<>(AppSettings.TranscriptMode.values());
    private final JButton fetch = button("Get transcript", true), summarize = button("Summarize", true),
            saveTranscript = button("Save transcript…", false), saveSummary = button("Save summary…", false),
            cancel = button("Cancel", false), configure = button("AI & settings", false),
            localSetup = button("Set up", false), pasteSummary = button("Paste summary", false),
            playPassage = button("Play passage", false), speechSetup = button("Set up speech", false);
    private VideoLinks videoLinks;
    private final JProgressBar progress = new JProgressBar();
    private SwingWorker<?, String> worker;
    private String title = "video", savedTranscript = "", savedSummary = "";
    private String transcriptProvenance = "";
    private SummarySource sourceType = SummarySource.UNKNOWN;
    private SummarySource summarySourceUsed = SummarySource.UNKNOWN, browserSource = SummarySource.UNKNOWN;
    private String summaryInput = "", summaryGuidance = "";
    private SummaryStyle summaryStyleUsed = SummaryStyle.AUTO, browserStyle = SummaryStyle.AUTO;
    private String browserInput = "", browserGuidance = "", browserPrompt = "";
    private int sourceVersion, browserSourceVersion = -1;
    private boolean changing, updatingModelChoices;

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Stillword");
        if (args.length > 0 && args[0].equals("--preview")) {
            System.setProperty("java.awt.headless", "true");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    configureLook();
                    StillWord app = new StillWord();
                    if (args.length > 2 && args[2].equals("--demo")) app.demo();
                    app.root.setSize(1280, 960);
                    layoutAll(app.root);
                    BufferedImage picture = new BufferedImage(1280, 960, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = picture.createGraphics();
                    app.root.printAll(graphics); graphics.dispose();
                    Path output = Path.of(args.length > 1 ? args[1] : "stillword-preview.png");
                    if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
                    ImageIO.write(picture, "png", output.toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            return;
        }
        SwingUtilities.invokeLater(() -> {
            configureLook();
            StillWord app = new StillWord();
            if (args.length > 0 && args[0].equals("--demo")) app.demo();
            app.show();
        });
    }

    private static void configureLook() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (key.toString().endsWith(".font")) UIManager.put(key, BODY);
        }
        UIManager.put("OptionPane.background", PAPER);
        UIManager.put("Panel.background", PAPER);
        UIManager.put("TextField.selectionBackground", new Color(205, 220, 205));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumb", LINE);
    }

    public StillWord() {
        root.setBackground(PAPER);
        root.setBorder(new EmptyBorder(18, 28, 12, 28));
        JPanel top = new JPanel(new BorderLayout(0, 14)) {
            @Override public Dimension getPreferredSize() {
                // Recompute against the current root height even when this panel is already valid.
                return getLayout().preferredLayoutSize(this);
            }
        };
        top.setOpaque(false);
        top.add(header(), BorderLayout.NORTH);
        top.add(new Hero(root), BorderLayout.CENTER);
        top.add(inputCard(), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        JPanel editors = transparent(new GridLayout(1, 2, 18, 0));
        editors.add(editorCard(false)); editors.add(editorCard(true));
        root.add(editors, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);

        configure.addActionListener(e -> settingsDialog());
        fetch.addActionListener(e -> fetchTranscript());
        url.addActionListener(e -> fetchTranscript());
        summarize.addActionListener(e -> summarize());
        localSetup.addActionListener(e -> setupLocalAi());
        speechSetup.addActionListener(e -> setupLocalSpeech());
        transcriptSource.setSelectedItem(settings.transcriptMode);
        transcriptSource.addActionListener(e -> {
            settings.transcriptMode = selectedTranscriptMode(); settings.save();
            updateTranscriptSource();
        });
        modelChoice.addActionListener(e -> selectModel());
        summaryStyleChoice.setSelectedItem(settings.summaryStyle);
        summaryStyleChoice.addActionListener(e -> {
            settings.summaryStyle = selectedSummaryStyle(); settings.save();
            summaryStyleChoice.setToolTipText(selectedSummaryStyle().description());
            documentChanged();
        });
        summaryStyleChoice.setToolTipText(selectedSummaryStyle().description());
        pasteSummary.addActionListener(e -> pasteBrowserSummary());
        saveTranscript.addActionListener(e -> save(false));
        saveSummary.addActionListener(e -> save(true));
        cancel.addActionListener(e -> { if (worker != null) { status.setText("Cancelling…"); worker.cancel(true); } });
        segment.addActionListener(e -> { start.setEnabled(segment.isSelected()); end.setEnabled(segment.isSelected()); });
        start.setEnabled(false); end.setEnabled(false);
        watch(transcript, this::documentChanged); watch(summary, this::documentChanged); watch(guidance, this::documentChanged);
        enableEditing(transcript); enableEditing(summary); enableEditing(guidance);
        videoLinks = new VideoLinks(transcript, summary, this::error);
        videoLinks.setPlaybackLinkListener(this::updatePlaybackLink);
        playbackLink.addActionListener(e -> videoLinks.playVisibleLink());
        copyPlaybackLink.addActionListener(e -> {
            VideoLinks.PlaybackLink link = videoLinks.currentPlaybackLink();
            if (link == null) return;
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(link.uri().toString()), null);
                status.setText("Video link copied · " + link.timestamp());
            } catch (Exception exception) { error("Could not copy the video link. You can find the full address in the link's tooltip."); }
        });
        transcript.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { }
            public void changedUpdate(DocumentEvent e) { }
            public void removeUpdate(DocumentEvent e) {
                if (!changing && transcript.getDocument().getLength() == 0) {
                    transcriptProvenance = ""; source.setText("No video loaded"); source.setToolTipText(null);
                    sourceType = SummarySource.UNKNOWN;
                    sourceVersion++;
                }
            }
        });
        summary.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { }
            public void changedUpdate(DocumentEvent e) { }
            public void removeUpdate(DocumentEvent e) {
                if (!changing && summary.getDocument().getLength() == 0) {
                    summaryInput = ""; summaryGuidance = "";
                }
            }
        });
        playPassage.addActionListener(e -> videoLinks.playTranscriptSelection());
        playPassage.setToolTipText("Click or select any transcript word to show its video link, or play the current passage directly.");
        documentChanged();
        updateTranscriptSource();
        updateProvider();
    }

    private JComponent header() {
        JPanel panel = transparent(new BorderLayout());
        JPanel brand = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel mark = new JLabel(new LeafIcon());
        mark.setBorder(new EmptyBorder(0, 0, 0, 12)); brand.add(mark);
        brand.add(label("stillword", 27, INK));
        JLabel tagline = label("  /  A SPACE FOR UNDERSTANDING", 10, MUTED);
        tagline.setBorder(new EmptyBorder(9, 15, 0, 0)); brand.add(tagline);
        panel.add(brand, BorderLayout.WEST);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton about = button("About", false); about.addActionListener(e -> about());
        actions.add(about); actions.add(configure); panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private JComponent inputCard() {
        RoundPanel card = new RoundPanel(WHITE, 18);
        card.setLayout(new BorderLayout(0, 9)); card.setBorder(new EmptyBorder(12, 18, 12, 18));
        card.add(label("START WITH A VIDEO", 10, MUTED), BorderLayout.NORTH);
        JPanel line = transparent(new BorderLayout(12, 0));
        url.setToolTipText("Paste a YouTube watch, share, Shorts, or live video link");
        url.getAccessibleContext().setAccessibleName("YouTube video link");
        line.add(url, BorderLayout.CENTER); line.add(fetch, BorderLayout.EAST); card.add(line, BorderLayout.CENTER);
        JPanel range = transparent(new FlowLayout(FlowLayout.LEFT, 9, 0));
        segment.setOpaque(false); segment.setForeground(INK); segment.setFont(BODY);
        range.add(segment); range.add(label("From", 12, MUTED)); range.add(start);
        range.add(label("to", 12, MUTED)); range.add(end);
        start.getAccessibleContext().setAccessibleName("Video segment start time");
        end.getAccessibleContext().setAccessibleName("Video segment end time");
        range.add(label("MM:SS or HH:MM:SS · blank end = video end", 11, MUTED));
        card.add(range, BorderLayout.SOUTH);
        return card;
    }

    private JComponent editorCard(boolean isSummary) {
        RoundPanel card = new RoundPanel(WHITE, 20);
        card.setLayout(new BorderLayout(0, 12)); card.setBorder(new EmptyBorder(18, 20, 16, 20));
        JPanel heading = transparent(new BorderLayout(0, 4));
        JPanel titleLine = transparent(new BorderLayout());
        titleLine.setPreferredSize(new Dimension(100, 28));
        titleLine.add(label(isSummary ? "Summary" : "Transcript", 18, INK), BorderLayout.WEST);
        if (isSummary) {
            JPanel styleLine = transparent(new FlowLayout(FlowLayout.RIGHT, 7, 0));
            styleLine.add(label("STYLE", 9, MUTED));
            summaryStyleChoice.setFont(BODY.deriveFont(11f)); summaryStyleChoice.setForeground(INK);
            summaryStyleChoice.setBackground(WHITE); summaryStyleChoice.setPreferredSize(new Dimension(152, 28));
            summaryStyleChoice.getAccessibleContext().setAccessibleName("Summary style");
            styleLine.add(summaryStyleChoice); titleLine.add(styleLine, BorderLayout.EAST);
        } else titleLine.add(label("EDITABLE", 9, MUTED), BorderLayout.EAST);
        heading.add(titleLine, BorderLayout.NORTH);
        heading.add(isSummary ? reviewHint : source, BorderLayout.SOUTH);
        card.add(heading, BorderLayout.NORTH);
        HintArea area = isSummary ? summary : transcript;
        area.setFont(new Font("Avenir Next", Font.PLAIN, 15));
        area.setMargin(new Insets(12, 1, 12, 10));
        area.getAccessibleContext().setAccessibleName(isSummary ? "Editable summary" : "Editable transcript");
        JScrollPane scroll = scroll(area); scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));
        card.add(scroll, BorderLayout.CENTER);

        JPanel bottom = transparent(new BorderLayout(0, 9));
        if (isSummary) {
            JPanel instructions = transparent(new BorderLayout(0, 5));
            instructions.add(label("SUMMARY GUIDANCE  ·  OPTIONAL", 10, MUTED), BorderLayout.NORTH);
            guidance.setFont(BODY); guidance.setRows(2); guidance.setMargin(new Insets(8, 10, 8, 10));
            guidance.setBackground(new Color(242, 244, 236));
            guidance.getAccessibleContext().setAccessibleName("Summary guidance, topics to omit or emphasize");
            JScrollPane guidanceScroll = scroll(guidance);
            guidanceScroll.setBorder(BorderFactory.createLineBorder(LINE));
            instructions.add(guidanceScroll, BorderLayout.CENTER);
            JPanel aiLine = transparent(new BorderLayout(8, 0));
            modelChoice.setFont(BODY.deriveFont(11f)); modelChoice.setForeground(INK); modelChoice.setBackground(WHITE);
            modelChoice.setPreferredSize(new Dimension(250, 34)); modelChoice.setMinimumSize(new Dimension(140, 30));
            modelChoice.getAccessibleContext().setAccessibleName("AI model and fees");
            modelChoice.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof AiModelChoices.Choice choice) label.setToolTipText(choice.detail());
                    return label;
                }
            });
            localSetup.setToolTipText("Download and prepare the free gpt-oss 20B model on this Mac");
            aiLine.add(modelChoice, BorderLayout.CENTER); aiLine.add(localSetup, BorderLayout.EAST);
            instructions.add(aiLine, BorderLayout.SOUTH);
            bottom.add(instructions, BorderLayout.NORTH);
        } else {
            JPanel speech = transparent(new BorderLayout(0, 6));
            speech.add(label("TRANSCRIPT SOURCE", 10, MUTED), BorderLayout.NORTH);
            JPanel choices = transparent(new BorderLayout(8, 0));
            transcriptSource.setFont(BODY.deriveFont(11f)); transcriptSource.setForeground(INK);
            transcriptSource.setBackground(WHITE); transcriptSource.setPreferredSize(new Dimension(225, 34));
            transcriptSource.getAccessibleContext().setAccessibleName("Transcript source");
            speechSetup.setToolTipText("Install Whisper large-v3-turbo on this Mac. Separate 1.6 GB speech-model download; free to run.");
            choices.add(transcriptSource, BorderLayout.CENTER); choices.add(speechSetup, BorderLayout.EAST);
            speech.add(choices, BorderLayout.CENTER);
            JPanel transcriptTools = transparent(new BorderLayout(0, 9));
            transcriptTools.add(playbackPanel(), BorderLayout.NORTH);
            transcriptTools.add(speech, BorderLayout.SOUTH);
            bottom.add(transcriptTools, BorderLayout.NORTH);
        }
        bottom.add(isSummary ? summaryCount : transcriptCount, BorderLayout.CENTER);
        JPanel buttons = transparent(new BorderLayout(10, 0));
        if (isSummary) {
            JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 5, 0));
            actions.add(summarize); actions.add(pasteSummary);
            buttons.add(actions, BorderLayout.WEST); buttons.add(saveSummary, BorderLayout.EAST);
        }
        else { buttons.add(playPassage, BorderLayout.WEST); buttons.add(saveTranscript, BorderLayout.EAST); }
        bottom.add(buttons, BorderLayout.SOUTH); card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private JComponent playbackPanel() {
        RoundPanel panel = new RoundPanel(new Color(239, 244, 234), 12);
        panel.setLayout(new BorderLayout(0, 3));
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));
        panel.add(label("Click any word to show its video link.", 11, MUTED), BorderLayout.NORTH);
        JPanel actions = transparent(new BorderLayout(8, 0));
        playbackLink.setFont(BODY.deriveFont(Font.BOLD, 12)); playbackLink.setForeground(INK);
        playbackLink.setHorizontalAlignment(SwingConstants.LEFT);
        playbackLink.setContentAreaFilled(false); playbackLink.setBorderPainted(false);
        playbackLink.setBorder(new EmptyBorder(3, 0, 3, 0));
        playbackLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyPlaybackLink.setFont(BODY.deriveFont(11f)); copyPlaybackLink.setBorder(new EmptyBorder(5, 9, 5, 9));
        copyPlaybackLink.getAccessibleContext().setAccessibleName("Copy the selected passage's video link");
        actions.add(playbackLink, BorderLayout.CENTER); actions.add(copyPlaybackLink, BorderLayout.EAST);
        panel.add(actions, BorderLayout.CENTER);
        playbackExcerpt.setPreferredSize(new Dimension(0, 16));
        playbackExcerpt.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(playbackExcerpt, BorderLayout.SOUTH);
        return panel;
    }

    private void updatePlaybackLink(VideoLinks.PlaybackLink link) {
        boolean available = link != null && worker == null;
        playbackLink.setText("<html><u>▶ Play video at " + (link == null ? "—" : link.timestamp()) + "</u></html>");
        playbackLink.setEnabled(available); copyPlaybackLink.setEnabled(available);
        playbackLink.setCursor(Cursor.getPredefinedCursor(available ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        String address = link == null ? null : link.uri().toString();
        playbackLink.setToolTipText(address); copyPlaybackLink.setToolTipText(address);
        playbackLink.getAccessibleContext().setAccessibleName(link == null ? "No video link for this text" : "Play video at " + link.timestamp());
        playbackLink.getAccessibleContext().setAccessibleDescription(address);
        playbackExcerpt.setText(link == null ? videoLinks != null && videoLinks.hasSource()
                ? "This text has no timestamp. Click a word in the transcript."
                : "Get a video transcript to attach playback times." : link.excerpt());
        playbackExcerpt.setToolTipText(link == null ? null : link.excerpt());
    }

    private JComponent footer() {
        JPanel panel = transparent(new BorderLayout(12, 0));
        panel.add(status, BorderLayout.CENTER);
        JPanel busy = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        progress.setPreferredSize(new Dimension(85, 5)); progress.setIndeterminate(true); progress.setVisible(false);
        cancel.setVisible(false); busy.add(progress); busy.add(cancel); panel.add(busy, BorderLayout.EAST);
        panel.setPreferredSize(new Dimension(100, 30)); return panel;
    }

    private void show() {
        frame = new JFrame("Stillword — Transcript & reflection");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(1030, 860));
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(1280, available.width - 20), Math.min(980, available.height - 20));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { close(); } });
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler((event, response) -> { if (canClose()) response.performQuit(); else response.cancelQuit(); });
        }
        frame.setVisible(true); url.requestFocusInWindow();
    }

    private boolean canClose() {
        boolean unsaved = !transcript.getText().equals(savedTranscript) || !summary.getText().equals(savedSummary);
        if ((unsaved || worker != null) && JOptionPane.showConfirmDialog(frame,
                worker != null ? "Cancel the current operation and close? Unsaved text will be lost." : "Close without saving your unsaved text?",
                "Close Stillword", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return false;
        if (worker != null) worker.cancel(true);
        return true;
    }

    private void close() { if (canClose()) frame.dispose(); }

    private boolean replaceAllowed(HintArea area, String saved, String name) {
        return area.getText().isBlank() || area.getText().equals(saved) || JOptionPane.showConfirmDialog(frame,
                "Replace the unsaved " + name + "? Save it first if you want to keep it.", "Replace " + name,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void fetchTranscript() {
        if (worker != null) return;
        final String link; final Double from, to;
        try {
            link = TranscriptService.validateUrl(url.getText());
            from = segment.isSelected() ? TextFiles.time(start.getText()) : null;
            to = segment.isSelected() ? TextFiles.time(end.getText()) : null;
            if (to != null && to <= (from == null ? 0 : from)) throw new IllegalArgumentException("The end time must be after the start time.");
        } catch (IllegalArgumentException e) { error(e.getMessage()); return; }
        boolean audio = selectedTranscriptMode() == AppSettings.TranscriptMode.LOCAL_AUDIO;
        if (audio && !new LocalSpeechService().isReady()) {
            status.setText("Select Set up speech to install the free Whisper model, then get the transcript.");
            speechSetup.requestFocusInWindow(); return;
        }
        if (!replaceAllowed(transcript, savedTranscript, "transcript")) return;
        String executable = settings.executable, language = settings.language;
        runWorker(new SwingWorker<TranscriptService.Result, String>() {
            protected TranscriptService.Result doInBackground() throws Exception {
                return audio ? new LocalSpeechService().transcribe(link, executable, from, to, this::publish)
                        : new TranscriptService().fetch(link, executable, language, from, to, this::publish);
            }
            protected void process(List<String> chunks) { if (worker == this) showProgress(chunks); }
            protected void done() {
                try {
                    TranscriptService.Result result = get();
                    title = result.title();
                    sourceType = SummarySource.fromTitle(title);
                    transcriptProvenance = result.sourceDescription();
                    changing = true; transcript.setText(result.text()); transcript.setCaretPosition(0); changing = false;
                    clearUndo(transcript);
                    videoLinks.setSource(link, result.cues());
                    sourceVersion++;
                    updateReviewHint();
                    String range = from == null && to == null ? "Full video" : "Selected portion";
                    source.setText(title); source.setToolTipText(title + " · " + range + " · " + result.sourceDescription() + " · " + result.language());
                    savedTranscript = "";
                    status.setText(range + " ready · " + result.sourceDescription() + ". Check unclear words with Play passage, then summarize.");
                    if (!summary.getText().isBlank()) status.setText("New transcript loaded. The previous summary is kept; summarize again to update it.");
                } catch (Exception e) { workerFailure(e); }
                finally { endWorker(); }
            }
        }, audio ? "Preparing audio transcription…" : "Getting captions…");
    }

    private AppSettings.TranscriptMode selectedTranscriptMode() {
        return transcriptSource.getSelectedItem() instanceof AppSettings.TranscriptMode mode
                ? mode : AppSettings.TranscriptMode.YOUTUBE_CAPTIONS;
    }

    private void updateTranscriptSource() {
        boolean audio = selectedTranscriptMode() == AppSettings.TranscriptMode.LOCAL_AUDIO;
        transcriptSource.setToolTipText(audio
                ? "Whisper large-v3-turbo transcribes downloaded audio on this Mac. Pali and unclear speech still need review."
                : "Download publisher captions when available, otherwise YouTube automatic captions.");
        speechSetup.setVisible(audio);
        fetch.setToolTipText(audio ? "Download audio and transcribe with the separate local speech model"
                : "Retrieve the video's English captions");
        root.revalidate(); root.repaint();
    }

    private void setupLocalSpeech() {
        if (worker != null) return;
        runWorker(new SwingWorker<Void, String>() {
            protected Void doInBackground() throws Exception { new LocalSpeechService().setup(this::publish); return null; }
            protected void process(List<String> chunks) { if (worker == this) showProgress(chunks); }
            protected void done() {
                try {
                    get(); transcriptSource.setSelectedItem(AppSettings.TranscriptMode.LOCAL_AUDIO);
                    settings.transcriptMode = AppSettings.TranscriptMode.LOCAL_AUDIO; settings.save();
                    updateTranscriptSource();
                    status.setText("Whisper large-v3-turbo is ready. Select Get transcript to transcribe the video's audio on this Mac.");
                } catch (Exception e) { workerFailure(e); }
                finally { endWorker(); }
            }
        }, "Setting up the free speech model · one-time 1.6 GB download…");
    }

    private void summarize() {
        if (worker != null || transcript.getText().isBlank()) return;
        if (settings.aiMode == AppSettings.AiMode.API && settings.apiKey.isBlank()
                && List.of("api.openai.com", "api.anthropic.com").contains(host(settings.endpoint))) {
            settingsDialog();
            if (settings.aiMode == AppSettings.AiMode.API && settings.apiKey.isBlank()) return;
        }
        if (settings.aiMode == AppSettings.AiMode.BROWSER) { browserHandoff(); return; }
        if (!replaceAllowed(summary, savedSummary, "summary")) return;
        String text = transcript.getText(), instructions = guidance.getText();
        SummaryStyle style = selectedSummaryStyle();
        SummarySource selectedSource = sourceType;
        SummaryService.Config config = settings.summaryConfig();
        boolean managedLocal = settings.aiMode == AppSettings.AiMode.LOCAL && isManagedLocal(config);
        runWorker(new SwingWorker<String, String>() {
            protected String doInBackground() throws Exception {
                if (managedLocal) new LocalAiService().ensureRunning(this::publish);
                return new SummaryService().summarize(text, config, instructions, style, selectedSource, this::publish);
            }
            protected void process(List<String> chunks) { if (worker == this) showProgress(chunks); }
            protected void done() {
                try {
                    SummaryReview.Parsed result = SummaryReview.parse(get());
                    changing = true; summary.setText(result.text()); summary.setCaretPosition(0); changing = false;
                    videoLinks.setSummaryMarks(result.marks());
                    updateReviewHint();
                    clearUndo(summary); savedSummary = ""; summaryInput = text; summaryGuidance = instructions;
                    summaryStyleUsed = style;
                    summarySourceUsed = selectedSource;
                    status.setText(videoLinks.linkedAmbiguityCount() > 0
                            ? "Summary ready. Click highlighted phrases to check the video; correct the transcript, then Redo summary."
                            : "Summary ready. You can correct the transcript and select Redo summary at any time.");
                } catch (Exception e) { workerFailure(e); }
                finally { endWorker(); }
            }
        }, "Preparing your summary…");
    }

    /** Equivalent default API URLs share the app-managed IPv4 Ollama server. */
    static boolean isManagedLocal(SummaryService.Config config) {
        if (config == null || config.endpoint() == null || config.model() == null
                || config.protocol() != SummaryService.Protocol.OPENAI_COMPATIBLE) return false;
        String model = config.model().strip();
        if (!model.equals(LocalAiService.MODEL) && !model.equals(LocalAiService.MODEL + ":latest")) return false;
        try {
            URI endpoint = URI.create(config.endpoint().strip());
            if (!"http".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getPort() != 11434
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
                return false;
            String host = endpoint.getHost();
            if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) return false;
            String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
            return path.isEmpty() || path.equals("/v1") || path.equals("/v1/chat/completions");
        } catch (IllegalArgumentException exception) { return false; }
    }

    private void setupLocalAi() {
        if (worker != null) return;
        runWorker(new SwingWorker<Void, String>() {
            protected Void doInBackground() throws Exception { new LocalAiService().setup(this::publish); return null; }
            protected void process(List<String> chunks) { if (worker == this) showProgress(chunks); }
            protected void done() {
                try {
                    get();
                    settings.aiMode = AppSettings.AiMode.LOCAL;
                    settings.localEndpoint = SummaryService.LOCAL_ENDPOINT; settings.localModel = LocalAiService.MODEL;
                    settings.save(); updateProvider();
                    status.setText("gpt-oss 20B is ready. Select Summarize to use it on this Mac.");
                } catch (Exception e) { workerFailure(e); }
                finally { endWorker(); }
            }
        }, "Preparing free local AI…");
    }

    private void browserHandoff() {
        final URI website; final String prompt;
        String text = transcript.getText(), instructions = guidance.getText();
        SummaryStyle style = selectedSummaryStyle();
        SummarySource selectedSource = sourceType;
        try {
            website = BrowserAiService.validateWebsite(settings.browserWebsite);
            prompt = BrowserAiService.preparePrompt(text, instructions, style, selectedSource);
        } catch (IllegalArgumentException e) { error(e.getMessage()); return; }
        JPanel panel = transparent(new BorderLayout(0, 12));
        panel.add(label("<html>Copy this prompt, paste it into your AI chat, and submit it there.<br>Then copy its answer and return to <b>Paste summary</b>.<br>Your AI subscription's message and attachment limits apply.</html>", 13, INK), BorderLayout.NORTH);
        JTextArea preview = new JTextArea(prompt, 16, 66);
        preview.setEditable(false); preview.setLineWrap(true); preview.setWrapStyleWord(true); preview.setFont(BODY); textShortcuts(preview);
        preview.setCaretPosition(0); panel.add(new JScrollPane(preview), BorderLayout.CENTER);
        int action = JOptionPane.showOptionDialog(frame, panel, "Use " + website.getHost(), JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, new String[]{"Copy & open AI", "Copy prompt", "Cancel"}, "Copy & open AI");
        if (action != 0 && action != 1) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(prompt), null);
            browserInput = text; browserGuidance = instructions; browserPrompt = prompt;
            browserStyle = style;
            browserSource = selectedSource;
            browserSourceVersion = sourceVersion;
            status.setText("Prompt copied. Submit it in your AI chat, then copy the answer and select Paste summary.");
        } catch (IllegalStateException e) { error("The clipboard is busy. Please try again."); return; }
        if (action == 0) {
            try { Desktop.getDesktop().browse(website); }
            catch (Exception e) { error("The prompt is copied. Open " + website + " in your browser and paste it there."); }
        }
    }

    private void pasteBrowserSummary() {
        if (worker != null) return;
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Copy the AI's summary first, then select Paste summary.");
            if (text.strip().equals(browserPrompt.strip())) throw new IllegalArgumentException("The clipboard still contains the prompt. Copy the AI's answer, then try again.");
            if (!replaceAllowed(summary, savedSummary, "summary")) return;
            SummaryReview.Parsed result = SummaryReview.parse(text);
            changing = true; summary.setText(result.text()); summary.setCaretPosition(0); changing = false;
            boolean sameSource = browserInput.isEmpty()
                    || browserSourceVersion == sourceVersion && browserInput.equals(transcript.getText());
            videoLinks.setSummaryMarks(sameSource ? result.marks() : List.of());
            updateReviewHint();
            clearUndo(summary); savedSummary = "";
            summaryInput = browserInput.isEmpty() ? transcript.getText() : browserInput;
            summaryGuidance = browserInput.isEmpty() ? guidance.getText() : browserGuidance;
            summaryStyleUsed = browserInput.isEmpty() ? selectedSummaryStyle() : browserStyle;
            summarySourceUsed = browserInput.isEmpty() ? sourceType : browserSource;
            status.setText("Summary pasted. Review, edit, and save it."); documentChanged();
        } catch (IllegalArgumentException e) { error(e.getMessage()); }
        catch (Exception e) { error("Could not read text from the clipboard. Copy the AI's summary and try again."); }
    }

    private void runWorker(SwingWorker<?, String> next, String message) {
        worker = next; status.setText(message); progress.setVisible(true); cancel.setVisible(true);
        documentChanged(); configure.setEnabled(false); url.setEnabled(false); segment.setEnabled(false);
        start.setEnabled(false); end.setEnabled(false); transcript.setEditable(false); summary.setEditable(false); guidance.setEditable(false);
        next.execute();
    }

    private void endWorker() {
        worker = null; progress.setVisible(false); cancel.setVisible(false); configure.setEnabled(true); url.setEnabled(true);
        segment.setEnabled(true); start.setEnabled(segment.isSelected()); end.setEnabled(segment.isSelected());
        transcript.setEditable(true); summary.setEditable(true); guidance.setEditable(true); documentChanged();
    }

    private void showProgress(List<String> chunks) { if (!chunks.isEmpty() && worker != null && !worker.isCancelled()) status.setText(chunks.get(chunks.size() - 1)); }

    private void workerFailure(Exception e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        if (cause instanceof CancellationException || cause instanceof InterruptedException) { status.setText("Cancelled. Your text is unchanged."); return; }
        status.setText("Could not complete the request. Your text is unchanged.");
        error(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    private void save(boolean isSummary) {
        HintArea area = isSummary ? summary : transcript;
        FileDialog dialog = new FileDialog(frame, isSummary ? "Save summary" : "Save transcript", FileDialog.SAVE);
        dialog.setDirectory(settings.directory);
        dialog.setFile(TextFiles.filename(title) + (isSummary ? " - summary.txt" : " - transcript.txt"));
        dialog.setVisible(true);
        if (dialog.getFile() == null) return;
        String name = dialog.getFile(); if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) name += ".txt";
        Path path = Path.of(dialog.getDirectory(), name);
        if (Files.exists(path) && JOptionPane.showConfirmDialog(frame, "Replace “" + name + "”?", "File already exists",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            TextFiles.save(path, area.getText());
            if (isSummary) savedSummary = area.getText(); else savedTranscript = area.getText();
            settings.directory = dialog.getDirectory(); settings.save();
            status.setText("Saved " + name); status.setToolTipText(path.toString()); documentChanged();
        } catch (Exception e) { error("Could not save the file: " + e.getMessage()); }
    }

    private void settingsDialog() {
        if (AiSettingsDialog.show(frame, settings)) {
            updateProvider(); status.setText("Settings updated.");
        }
    }

    private void selectModel() {
        if (updatingModelChoices || worker != null) return;
        AiModelChoices.Choice choice = (AiModelChoices.Choice) modelChoice.getSelectedItem();
        if (choice == null) return;
        if (choice.configure()) {
            settingsDialog(); updateProvider();
            return;
        }
        AiModelChoices.apply(settings, choice);
        settings.save(); updateProvider();
        status.setText(choice.mode() == AppSettings.AiMode.BROWSER
                ? "AI website selected. Prepare a prompt, then choose an available model in your AI chat."
                : "Selected " + choice.label() + ". Choose Summarize when you are ready.");
    }

    private void updateProvider() {
        boolean local = settings.aiMode == AppSettings.AiMode.LOCAL;
        boolean browser = settings.aiMode == AppSettings.AiMode.BROWSER;
        localSetup.setVisible(local); pasteSummary.setVisible(browser);
        summarize.setText(browser ? "Prepare prompt" : "Summarize");
        updatingModelChoices = true;
        try {
            modelChoice.setModel(new DefaultComboBoxModel<>(AiModelChoices.available(settings).toArray(AiModelChoices.Choice[]::new)));
            modelChoice.setSelectedItem(AiModelChoices.selected(settings));
            AiModelChoices.Choice selected = (AiModelChoices.Choice) modelChoice.getSelectedItem();
            modelChoice.setToolTipText(selected == null ? "Choose an AI model or website" : selected.detail());
        } finally { updatingModelChoices = false; }
        documentChanged(); root.revalidate(); root.repaint();
    }

    private static String host(String endpoint) {
        try { return URI.create(endpoint).getHost(); } catch (Exception e) { return "configured provider"; }
    }

    private void documentChanged() {
        if (changing) return;
        transcriptCount.setText(words(transcript.getText()) + " words"
                + (transcriptProvenance.isBlank() ? "" : " · " + transcriptProvenance)
                + (transcript.getText().equals(savedTranscript) ? "" : " · unsaved"));
        boolean stale = !summary.getText().isBlank() && !summaryInput.isEmpty() &&
                (!transcript.getText().equals(summaryInput) || !guidance.getText().equals(summaryGuidance)
                        || sourceType != summarySourceUsed);
        boolean styleChanged = !summary.getText().isBlank() && !summaryInput.isEmpty()
                && selectedSummaryStyle() != summaryStyleUsed;
        boolean unknownSource = !summary.getText().isBlank() && summaryInput.isEmpty();
        summaryCount.setText(words(summary.getText()) + " words"
                + (stale ? " · source or guidance changed" : styleChanged ? " · style changed" : unknownSource ? " · source not tracked" : "")
                + (summary.getText().equals(savedSummary) ? "" : " · unsaved"));
        boolean idle = worker == null;
        boolean browser = settings.aiMode == AppSettings.AiMode.BROWSER;
        summarize.setText(browser ? "Prepare prompt" : summary.getText().isBlank() ? "Summarize" : "Redo summary");
        summarize.setToolTipText(browser ? "Prepare a new prompt using the current transcript and guidance"
                : "Create a summary using the current edited transcript and guidance");
        playPassage.setEnabled(idle && videoLinks != null && videoLinks.hasSource() && !transcript.getText().isBlank());
        if (videoLinks != null) { updateReviewHint(); updatePlaybackLink(videoLinks.currentPlaybackLink()); }
        fetch.setEnabled(idle); summarize.setEnabled(idle && !transcript.getText().isBlank());
        localSetup.setEnabled(idle); pasteSummary.setEnabled(idle); modelChoice.setEnabled(idle); summaryStyleChoice.setEnabled(idle);
        speechSetup.setEnabled(idle); transcriptSource.setEnabled(idle);
        saveTranscript.setEnabled(idle && !transcript.getText().isBlank()); saveSummary.setEnabled(idle && !summary.getText().isBlank());
    }

    private SummaryStyle selectedSummaryStyle() {
        return summaryStyleChoice.getSelectedItem() instanceof SummaryStyle style ? style : SummaryStyle.AUTO;
    }

    private void updateReviewHint() {
        int count = videoLinks.linkedAmbiguityCount();
        reviewHint.setText(count == 0 ? " " : count + (count == 1 ? " phrase to check" : " phrases to check") + " · click highlighted text");
        reviewHint.setToolTipText(count == 0 ? null : "Play the source passage, correct the transcript, then select Redo summary.");
    }

    private void about() {
        JOptionPane.showMessageDialog(frame, aboutContent(), "About StillWord", JOptionPane.PLAIN_MESSAGE);
    }

    private JPanel aboutContent() {
        JPanel content = transparent(new BorderLayout(0, 14));
        content.add(label("<html><b>StillWord " + AppInfo.VERSION + "</b> · Transcript & reflection<br><br>"
                + "Developed by <b>" + AppInfo.DEVELOPER + "</b><br>"
                + "criticalattentionsystems.org<br>"
                + "Bugs and issues: " + AppInfo.SUPPORT_EMAIL + "<br><br>"
                + "Watercolor artwork generated from a reference photograph<br>of Bhavana Society's main meditation hall.<br><br>"
                + "An independent personal tool, with no implied endorsement.<br>"
                + "Caption boundaries determine the accuracy of selected time ranges.<br>"
                + "Summary length adapts to the content, with no page limit.</html>", 13, INK), BorderLayout.CENTER);
        JPanel actions = transparent(new GridLayout(2, 2, 8, 8));
        JButton report = button("Report a bug", true);
        report.setToolTipText("Open an email draft to " + AppInfo.SUPPORT_EMAIL);
        report.addActionListener(e -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MAIL))
                    throw new UnsupportedOperationException();
                Desktop.getDesktop().mail(AppInfo.bugReportUri());
            } catch (Exception ex) {
                error("Could not open your email application. Use Copy bug report, then email " + AppInfo.SUPPORT_EMAIL + ".");
            }
        });
        JButton copyReport = button("Copy bug report", false);
        copyReport.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(AppInfo.bugReport()), null);
                copyReport.setText("Copied");
            } catch (Exception ex) { error("Could not copy the bug report. Please email " + AppInfo.SUPPORT_EMAIL + "."); }
        });
        JButton website = button("Developer website", false);
        website.addActionListener(e -> { try { Desktop.getDesktop().browse(AppInfo.WEBSITE); }
            catch (Exception ex) { error("Could not open " + AppInfo.WEBSITE); } });
        JButton credits = button("Open artwork reference", false);
        credits.addActionListener(e -> { try { Desktop.getDesktop().browse(URI.create("https://americanbuddhism.pages.wm.edu/bhavana-society/")); } catch (Exception ex) { error("Could not open the source link."); } });
        actions.add(report); actions.add(copyReport); actions.add(website); actions.add(credits);
        content.add(actions, BorderLayout.SOUTH);
        return content;
    }

    private void demo() {
        title = "A quiet moment of attention — demo";
        source.setText(title);
        transcript.setText("DEMONSTRATION TEXT — not extracted from a video\n\nWhen we sit quietly, we begin by noticing what is already here. The breath gives us a simple place to rest our attention. We do not need to force it to be different.\n\nThoughts will come and go. When we recognize that the mind has wandered, we can return gently to the next breath. This return is part of the practice, rather than a failure of it.\n\nThe same quality of attention can accompany ordinary activities: walking, listening, or sharing a meal. We can pause, notice our intention, and respond with care.\n\nAnnouncements: our next meeting will include time for questions and a discussion of the retreat schedule.");
        guidance.setText("Omit announcements and scheduling. Focus on practical guidance.");
        summary.setText("DEMONSTRATION SUMMARY — not generated by AI\n\nA gentle return to attention\n\nThe discussion presents meditation as a practice of noticing experience with patience. The breath offers a steady point of return without needing to be controlled or changed.\n\nA wandering mind is expected. Recognizing distraction and returning to the breath is itself a meaningful part of the practice. The emphasis is on a gentle, repeated return.\n\nThis approach extends into daily life. Walking, listening, and eating become opportunities to pause, notice intention, and act with care.");
        summaryInput = transcript.getText(); summaryGuidance = guidance.getText();
        status.setText("Design preview · demonstration text only. Paste a video link to begin."); documentChanged();
    }

    private void error(String message) {
        JTextArea text = new JTextArea(message, 0, 58); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setOpaque(false); text.setFont(BODY);
        JOptionPane.showMessageDialog(frame, text, "Stillword", JOptionPane.ERROR_MESSAGE);
    }
    static int words(String text) { return text.isBlank() ? 0 : text.strip().split("\\s+").length; }
    private static void watch(JTextArea area, Runnable action) {
        area.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }
    private static void enableEditing(JTextArea area) {
        UndoManager undo = new UndoManager(); area.putClientProperty("undo", undo); area.getDocument().addUndoableEditListener(undo);
        int mask = textShortcuts(area);
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "undo");
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask | InputEvent.SHIFT_DOWN_MASK), "redo");
        area.getActionMap().put("undo", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (area.isEditable() && undo.canUndo()) undo.undo(); } });
        area.getActionMap().put("redo", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (area.isEditable() && undo.canRedo()) undo.redo(); } });
        JPopupMenu menu = new JPopupMenu();
        for (String name : List.of("Cut", "Copy", "Paste", "Select all")) {
            JMenuItem item = new JMenuItem(name); item.addActionListener(e -> { switch (name) {
                case "Cut" -> { if (area.isEditable()) area.cut(); }
                case "Copy" -> area.copy();
                case "Paste" -> { if (area.isEditable()) area.paste(); }
                default -> area.selectAll();
            } }); menu.add(item);
        }
        area.setComponentPopupMenu(menu);
    }
    private static void clearUndo(JTextArea area) { ((UndoManager)area.getClientProperty("undo")).discardAllEdits(); }
    private static int textShortcuts(JTextComponent area) {
        int mask = System.getProperty("os.name", "").startsWith("Mac") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask), DefaultEditorKit.selectAllAction);
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, mask), DefaultEditorKit.copyAction);
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask), DefaultEditorKit.pasteAction);
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X, mask), DefaultEditorKit.cutAction);
        return mask;
    }
    private static JScrollPane scroll(JTextArea area) {
        JScrollPane scroll = new JScrollPane(area); scroll.setBorder(null); scroll.getViewport().setBackground(WHITE);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }
    private static JPanel transparent(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static JLabel label(String text, int size, Color color) { JLabel l = new JLabel(text); l.setFont(BODY.deriveFont((float) size)); l.setForeground(color); return l; }
    private static JTextField field(String text, int columns) {
        JTextField field = new JTextField(text, columns); styleField(field); return field;
    }
    private static void styleField(JTextField field) {
        field.setFont(BODY.deriveFont(14f)); field.setForeground(INK); field.setBackground(WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new EmptyBorder(7, 10, 7, 10)));
        field.setDisabledTextColor(MUTED); textShortcuts(field);
    }
    private static JButton button(String text, boolean primary) { return new SoftButton(text, primary); }
    private static void layoutAll(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container container) layoutAll(container); }

    private static class RoundPanel extends JPanel {
        final Color fill; final int radius;
        RoundPanel(Color fill, int radius) { this.fill = fill; this.radius = radius; setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D g2 = (Graphics2D)g.create(); antialias(g2); g2.setColor(fill); g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius); g2.dispose(); super.paintComponent(g); }
    }
    private static final class SoftButton extends JButton {
        private final boolean primary;
        SoftButton(String text, boolean primary) {
            super(text); this.primary = primary; setFont(BODY.deriveFont(Font.BOLD, 12));
            setForeground(primary ? WHITE : INK); setOpaque(false); setContentAreaFilled(false); setBorderPainted(false);
            setFocusPainted(false); setBorder(new EmptyBorder(10, 16, 10, 16)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create(); antialias(g2);
            Color fill = primary ? INK : new Color(236, 240, 229);
            if (!isEnabled()) fill = new Color(228, 230, 220);
            else if (getModel().isRollover()) fill = primary ? new Color(48, 88, 69) : new Color(224, 231, 216);
            g2.setColor(fill); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            if (isFocusOwner()) { g2.setColor(GOLD); g2.setStroke(new BasicStroke(2)); g2.drawRoundRect(2, 2, getWidth()-5, getHeight()-5, 10, 10); }
            g2.dispose(); super.paintComponent(g);
        }
    }
    private static final class HintArea extends JTextArea {
        private final String hint;
        HintArea(String hint) { this.hint = hint; setLineWrap(true); setWrapStyleWord(true); setForeground(INK); setCaretColor(INK); setBackground(WHITE); setSelectionColor(new Color(213, 225, 208)); }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D)g.create(); antialias(g2); g2.setFont(getFont()); g2.setColor(new Color(129, 140, 126));
                FontMetrics fm = g2.getFontMetrics(); int x = getInsets().left + 2, y = getInsets().top + fm.getAscent();
                for (String line : hint.split("\n", -1)) {
                    String current = "";
                    for (String word : line.split(" ")) {
                        if (!current.isEmpty() && fm.stringWidth(current + word) > getWidth() - x - 16) { g2.drawString(current, x, y); y += fm.getHeight(); current = ""; }
                        current += word + " ";
                    }
                    g2.drawString(current, x, y); y += fm.getHeight();
                }
                g2.dispose();
            }
        }
    }

    private static final class LinkField extends JTextField {
        LinkField() { super(40); styleField(this); }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D)g.create(); antialias(g2); g2.setFont(getFont()); g2.setColor(MUTED);
                g2.drawString("Paste a YouTube video link…", getInsets().left, (getHeight() + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2); g2.dispose();
            }
        }
    }

    private static BufferedImage resource(String path) { try { return ImageIO.read(StillWord.class.getResource(path)); } catch (Exception e) { return null; } }
    private static void antialias(Graphics2D g) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); }
    private static final class Hero extends JPanel {
        private final JComponent viewport;
        final BufferedImage monastery = resource("/images/monastery.png");
        Hero(JComponent viewport) { this.viewport = viewport; setOpaque(false); }
        @Override public Dimension getPreferredSize() {
            Insets insets = viewport.getInsets();
            int width = viewport.getWidth() - insets.left - insets.right;
            // Keep the same composition as the window grows or shrinks, rather than
            // changing the artwork's share of the banner with the window's height.
            return new Dimension(900, Math.max(1, (int)Math.round((width > 0 ? width : 900) / 6.4)));
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create(); antialias(g2); int w = getWidth(), h = getHeight();
            float compositionScale = Math.max(.01f, Math.min(w / 1344f, h / 210f));
            g2.clip(new RoundRectangle2D.Double(0, 0, w, h, 22 * compositionScale, 22 * compositionScale));
            g2.setColor(new Color(250, 242, 220)); g2.fillRect(0, 0, w, h);
            int imageWidth = (int)(630 * compositionScale), artLeft = w - imageWidth;
            if (monastery != null) {
                // Contain the complete painting, including the pond and bridge, at every window size.
                double scale = Math.min(630 * compositionScale / monastery.getWidth(),
                        h / (double)monastery.getHeight());
                imageWidth = (int)Math.floor(monastery.getWidth() * scale);
                int imageHeight = (int)Math.floor(monastery.getHeight() * scale);
                artLeft = w - imageWidth;
                g2.drawImage(monastery, artLeft, (h - imageHeight) / 2, imageWidth, imageHeight, null);
            }
            // Blend the background up to the painting; never tint the artwork itself.
            float fadeStart = artLeft - 260 * compositionScale;
            float fadeEnd = Math.max(fadeStart + 1, artLeft);
            g2.setPaint(new LinearGradientPaint(fadeStart, 0, fadeEnd, 0,
                    new float[]{0,.25f,.5f,.75f,1},
                    new Color[]{INK,new Color(29,62,53,215),new Color(29,62,53,128),
                            new Color(29,62,53,40),new Color(29,62,53,0)}));
            g2.fillRect(0, 0, Math.max(0, artLeft), h);
            float textLeft = 26 * compositionScale;
            g2.setColor(new Color(224, 213, 180)); g2.setFont(BODY.deriveFont(Font.BOLD, 12 * compositionScale));
            g2.drawString("WORDS WORTH RETURNING TO", textLeft, 32 * compositionScale);
            g2.setColor(WHITE); g2.setFont(new Font("Georgia", Font.PLAIN, 45).deriveFont(45 * compositionScale));
            g2.drawString("From listening", textLeft, 83 * compositionScale);
            g2.drawString("to understanding.", textLeft, 130 * compositionScale);
            g2.setFont(BODY.deriveFont(15 * compositionScale)); g2.setColor(new Color(224,230,215));
            g2.drawString("Keep the words. Find the essence.", textLeft, 168 * compositionScale);
            g2.dispose();
        }
    }
    private static final class LeafIcon implements Icon {
        public int getIconWidth() { return 32; } public int getIconHeight() { return 34; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D)g.create(); antialias(g2); g2.translate(x, y); g2.setColor(INK); g2.setStroke(new BasicStroke(1.4f));
            Path2D leaf = new Path2D.Double(); leaf.moveTo(16, 30); leaf.curveTo(1, 23, 0, 9, 4, 5); leaf.curveTo(18, 7, 25, 18, 16, 30); g2.draw(leaf);
            leaf.reset(); leaf.moveTo(16,30); leaf.curveTo(27,26,34,15,29,8); leaf.curveTo(21,11,15,19,16,30); g2.draw(leaf);
            g2.drawLine(16,30,8,13); g2.dispose();
        }
    }
}
