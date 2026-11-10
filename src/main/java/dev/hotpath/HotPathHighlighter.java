package dev.hotpath;

import analysis.*;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import core.FalseSharingDetector;
import core.GlobalAtomicsAudit;
import core.ProducerConsumerAnalyzer;
import org.jetbrains.annotations.NotNull;
import ui.HFTSettings;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main plugin engine: Asynchronous memory layout analysis and inlay rendering.
 * Fully non-blocking, immune to EDT queue flooding and multi-editor race conditions.
 */
public class HotPathHighlighter implements EditorFactoryListener {

    private static final Map<Editor, Alarm> EDITOR_ALARMS = new ConcurrentHashMap<>();
    private static final int DEBOUNCE_DELAY_MS = 250;

    public HotPathHighlighter() {
    }

    static ArrayList<HotRegionFinder.Region> findAll(String text) {
        return HotRegionFinder.findAllStructs(text);
    }

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent e) {
        Editor ed = e.getEditor();

        ed.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent ev) {
                update(ed);
            }
        });

        ed.addEditorMouseMotionListener(new EditorMouseMotionListener() {
            @Override
            public void mouseMoved(@NotNull EditorMouseEvent event) {
                handleMouseHover(ed, event);
            }
        });

        update(ed);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor ed = event.getEditor();
        Alarm alarm = EDITOR_ALARMS.remove(ed);
        if (alarm != null) {
            alarm.cancelAllRequests();
            Disposer.dispose(alarm);
        }
        if (ed.getDocument() != null) {
            removeOldHints(ed, ed.getDocument().getTextLength());
            removeOldBars(ed, ed.getDocument().getTextLength());
        }
    }

    private static void handleMouseHover(Editor ed, EditorMouseEvent event) {
        HFTSettings.State state = HFTSettings.getInstance().getState();
        if (!state.hoverTooltips || !state.showMemoryMapBar) return;

        Point p = event.getMouseEvent().getPoint();
        InlayModel model = ed.getInlayModel();
        List<Inlay<?>> inlays = model.getBlockElementsInRange(0, ed.getDocument().getTextLength());

        for (Inlay<?> inlay : inlays) {
            if (inlay.getRenderer() instanceof Bar) {
                Rectangle bounds = inlay.getBounds();
                if (bounds != null && bounds.contains(p)) {
                    Bar bar = (Bar) inlay.getRenderer();
                    bar.showHoverTooltip(ed, p, bounds);
                    return;
                }
            }
        }
    }

    public static void update(Editor ed) {
        if (ed == null || ed.isDisposed()) return;
        Alarm alarm = EDITOR_ALARMS.computeIfAbsent(ed, k -> new Alarm(Alarm.ThreadToUse.SWING_THREAD));
        alarm.cancelAllRequests();
        alarm.addRequest(() -> {
            if (!ed.isDisposed()) {
                scheduleBackgroundRefresh(ed);
            }
        }, DEBOUNCE_DELAY_MS);
    }

    public static void refreshAllEditors() {
        Application app = ApplicationManager.getApplication();
        Runnable task = () -> {
            Editor[] editors = EditorFactory.getInstance().getAllEditors();
            for (Editor editor : editors) {
                if (!editor.isDisposed()) {
                    update(editor);
                }
            }
        };

        if (app.isDispatchThread()) {
            task.run();
        } else {
            app.invokeLater(task);
        }
    }

    private static class AnalysisResultPayload {
        final String text;
        final FileAnalyzer.Result result;
        final List<MisalignedAllocatorDetector.MisalignedContainerIssue> allocIssues;
        final Map<FileAnalyzer.MarkedStruct, Map<String, AccessPatternAnalyzer.FieldAnalysis>> hotColdMaps;
        final List<GlobalAtomicsAudit.AuditConflict> globalConflicts;

        AnalysisResultPayload(String text, FileAnalyzer.Result result,
                              List<MisalignedAllocatorDetector.MisalignedContainerIssue> allocIssues,
                              Map<FileAnalyzer.MarkedStruct, Map<String, AccessPatternAnalyzer.FieldAnalysis>> hotColdMaps,
                              List<GlobalAtomicsAudit.AuditConflict> globalConflicts) {
            this.text = text;
            this.result = result;
            this.allocIssues = allocIssues;
            this.hotColdMaps = hotColdMaps;
            this.globalConflicts = globalConflicts;
        }
    }

    private static void scheduleBackgroundRefresh(Editor ed) {
        if (ed.isDisposed()) return;

        ReadAction.nonBlocking(() -> {
                    if (ed.isDisposed()) return null;
                    String text = ed.getDocument().getText();
                    HFTSettings.State state = HFTSettings.getInstance().getState();
                    if (state.autoEnableCpp && !isCppFile(ed)) return null;

                    FileAnalyzer.Result result;
                    try {
                        result = FileAnalyzer.analyze(text);
                    } catch (Exception e) {
                        return null;
                    }
                    if (result == null || result.structs == null) return null;

                    List<CppStructParser.StructInfo> allInfos = new ArrayList<>();
                    for (FileAnalyzer.MarkedStruct m : result.structs) {
                        if (m.info != null) allInfos.add(m.info);
                    }

                    List<MisalignedAllocatorDetector.MisalignedContainerIssue> allocIssues =
                            state.misalignedAllocatorDetection ? MisalignedAllocatorDetector.checkFile(text, allInfos) : Collections.emptyList();

                    Map<FileAnalyzer.MarkedStruct, Map<String, AccessPatternAnalyzer.FieldAnalysis>> hotColdMaps = new HashMap<>();
                    for (FileAnalyzer.MarkedStruct m : result.structs) {
                        try {
                            Map<String, AccessPatternAnalyzer.FieldAnalysis> map = ensureCellsAndConflicts(m, text, state);
                            hotColdMaps.put(m, map);
                        } catch (Exception ignored) {}
                    }

                    List<GlobalAtomicsAudit.AuditConflict> globalConflicts = state.globalAtomicsAudit
                            ? GlobalAtomicsAudit.audit(text, state.cacheLineSize)
                            : Collections.emptyList();

                    return new AnalysisResultPayload(text, result, allocIssues, hotColdMaps, globalConflicts);
                })
                .expireWhen(ed::isDisposed)
                .finishOnUiThread(ModalityState.any(), payload -> {
                    if (ed.isDisposed() || payload == null) return;
                    applyHighlights(ed, payload);
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static void applyHighlights(Editor ed, AnalysisResultPayload payload) {
        String text = payload.text;
        removeOldHints(ed, text.length());
        removeOldBars(ed, text.length());

        HFTSettings.State state = HFTSettings.getInstance().getState();

        for (FileAnalyzer.MarkedStruct m : payload.result.structs) {
            try {
                Map<String, AccessPatternAnalyzer.FieldAnalysis> hotColdMap = payload.hotColdMaps.get(m);
                drawMarkedStruct(ed, m, state, hotColdMap, payload.allocIssues);
            } catch (Exception ignored) {}
        }

        for (GlobalAtomicsAudit.AuditConflict gc : payload.globalConflicts) {
            int lineNum = ed.getDocument().getLineNumber(gc.second.offsetInDoc);
            int lineEnd = ed.getDocument().getLineEndOffset(lineNum);
            addHint(ed, lineEnd, " [potential global false sharing: contiguous in .bss/.data]", Color.RED);
        }
    }

    public static void removeOldHints(Editor ed, int length) {
        InlayModel model = ed.getInlayModel();
        List<Inlay<?>> hints = model.getAfterLineEndElementsInRange(0, length);
        for (Inlay<?> inlay : hints) {
            if (inlay.getRenderer() instanceof Hint) Disposer.dispose(inlay);
        }
    }

    public static void removeOldBars(Editor ed, int length) {
        InlayModel model = ed.getInlayModel();
        List<Inlay<?>> bars = model.getBlockElementsInRange(0, length);
        for (Inlay<?> inlay : bars) {
            if (inlay.getRenderer() instanceof Bar) Disposer.dispose(inlay);
        }
    }

    private static boolean isCppFile(Editor ed) {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(ed.getDocument());
        if (vf == null) return false;
        String ext = vf.getExtension();
        if (ext == null) return false;
        String lower = ext.toLowerCase();
        return lower.equals("cpp") || lower.equals("cxx") || lower.equals("cc")
                || lower.equals("c") || lower.equals("h") || lower.equals("hpp")
                || lower.equals("hxx");
    }

    static Map<String, AccessPatternAnalyzer.FieldAnalysis> ensureCellsAndConflicts(
            FileAnalyzer.MarkedStruct m, String text, HFTSettings.State state) {
        if (m.layout == null || !m.layout.complete) return null;
        if (m.layout.cells == null) m.layout.cells = buildCells(m.layout);

        int a = Math.max(0, Math.min(m.region.bStart(), text.length()));
        int b = Math.max(a, Math.min(m.region.bEnd(), text.length()));
        String block = text.substring(a, b);

        CppStructParser.StructInfo info = CppStructParser.parse(block);
        if (info == null) return null;

        ProducerConsumerAnalyzer.StructProfile profile = ProducerConsumerAnalyzer.analyze(info, block);

        List<FalseSharingDetector.CacheField> cacheFields = new ArrayList<>();
        for (int i = 0; i < m.layout.fields.size(); i++) {
            CppStructParser.Field f = m.layout.fields.get(i);
            ProducerConsumerAnalyzer.FieldProfile fp = profile.getFieldProfile(f.name);
            boolean isAtomic = fp != null && fp.isAtomic;
            boolean isProducer = fp != null && fp.isProducer();
            boolean isConsumer = fp != null && fp.isConsumer();
            boolean isArray = fp != null && fp.isArray;
            int arrCount = fp != null ? fp.arraySize : 1;

            cacheFields.add(new FalseSharingDetector.CacheField(
                    f.name, f.offset, f.size, state.cacheLineSize, isAtomic, isProducer, isConsumer, isArray, arrCount
            ));
        }

        FalseSharingDetector.Result detectorResult = FalseSharingDetector.detect(cacheFields, state.cacheLineSize, profile);

        m.risky = new HashMap<>();
        for (FalseSharingDetector.Conflict c : detectorResult.conflicts) {
            for (int i = 0; i < m.layout.fields.size(); i++) {
                CppStructParser.Field f = m.layout.fields.get(i);
                if (f.name.equals(c.first.name) || f.name.equals(c.second.name)) {
                    m.risky.put(i, c.description);
                }
            }
        }

        return AccessPatternAnalyzer.analyze(info, block);
    }

    static int[] buildCells(LayoutCalculator.Layout l) {
        int limit = Math.min(l.totalSize, 16384);
        int[] cells = new int[Math.max(0, limit)];
        Arrays.fill(cells, -1);
        for (int i = 0; i < l.fields.size(); i++) {
            CppStructParser.Field f = l.fields.get(i);
            for (int b = f.offset; b < f.offset + f.size && b < cells.length; b++) {
                if (b >= 0) cells[b] = i;
            }
        }
        return cells;
    }

    static void drawMarkedStruct(Editor ed, FileAnalyzer.MarkedStruct m, HFTSettings.State state,
                                 Map<String, AccessPatternAnalyzer.FieldAnalysis> hotColdMap,
                                 List<MisalignedAllocatorDetector.MisalignedContainerIssue> allocIssues) {
        Document doc = ed.getDocument();
        InlayModel model = ed.getInlayModel();
        int headerLine = doc.getLineNumber(m.region.bStart());

        if (m.layout == null || !m.layout.complete) {
            addHint(ed, doc.getLineEndOffset(headerLine), "layout unknown", Color.GRAY);
            return;
        }

        int[] layoutCells = m.layout.cells != null ? m.layout.cells : buildCells(m.layout);

        String[] stringCells = new String[layoutCells.length];
        for (int i = 0; i < layoutCells.length; i++) {
            if (layoutCells[i] >= 0 && layoutCells[i] < m.layout.fields.size()) {
                stringCells[i] = m.layout.fields.get(layoutCells[i]).name;
            }
        }

        boolean[] riskyByte = new boolean[layoutCells.length];
        HashMap<Integer, String> risky = (m.risky != null && state.falseSharingDetection) ? m.risky : new HashMap<>();
        if (state.falseSharingDetection) {
            for (Integer idx : risky.keySet()) {
                if (idx == null || idx < 0 || idx >= m.layout.fields.size()) continue;
                CppStructParser.Field f = m.layout.fields.get(idx);
                for (int b = f.offset; b < f.offset + f.size && b < riskyByte.length; b++) {
                    if (b >= 0) riskyByte[b] = true;
                }
            }
        }

        if (state.showMemoryMapBar && (state.cacheLineBoundaries || state.paddingVisualization)) {
            InlayProperties props = new InlayProperties();
            props.showAbove(true);
            props.relatesToPrecedingText(false);
            Bar bar = new Bar(stringCells, riskyByte, state.cacheLineSize, state, m.layout);
            model.addBlockElement(doc.getLineStartOffset(headerLine), props, bar);
        }

        boolean simdHazard = checkSimdHazard(m);
        boolean hasAllocMismatch = false;
        for (MisalignedAllocatorDetector.MisalignedContainerIssue issue : allocIssues) {
            if (issue.structName.equals(m.info.name)) {
                hasAllocMismatch = true;
                break;
            }
        }

        List<StoreForwardingDetector.StlfHazard> stlfHazards =
                state.storeForwardingDetection ? StoreForwardingDetector.detect(m.layout) : Collections.emptyList();

        int bStart = Math.max(0, Math.min(m.region.bStart(), doc.getTextLength()));
        int bEnd = Math.max(bStart, Math.min(m.region.bEnd(), doc.getTextLength()));
        String blockText = doc.getText().substring(bStart, bEnd);

        boolean hasVtableHazard = state.virtualCallLinter && VirtualCallLinter.hasVirtualCallHazards(m.info, blockText);

        String status;
        if (simdHazard) {
            status = "SIGSEGV HAZARD";
        } else if (state.falseSharingDetection && !risky.isEmpty()) {
            status = "FALSE SHARING";
        } else if (state.paddingVisualization && m.layout.wasted > 0) {
            status = "WARNING";
        } else {
            status = "OK";
        }

        Color labelColor = ("SIGSEGV HAZARD".equals(status) || "FALSE SHARING".equals(status)) ? Color.RED
                : "WARNING".equals(status) ? new Color(220, 160, 50) : new Color(90, 170, 90);

        StringBuilder label = new StringBuilder(m.layout.totalSize + "B");
        if (state.paddingVisualization && m.layout.wasted > 0) {
            label.append(" (pad ").append(m.layout.wasted).append("B)");
        }
        label.append(" [").append(status).append("]");

        if (state.zeroAllocLinter && ZeroAllocLinter.hasZeroAllocHazards(m.info, blockText)) {
            label.append(" [alloc hazard]");
            labelColor = Color.RED;
        }

        if (hasAllocMismatch) {
            label.append(" [allocator mismatch]");
            labelColor = Color.RED;
        }

        if (!stlfHazards.isEmpty()) {
            label.append(" [stlf hazard]");
            labelColor = Color.RED;
        }

        if (hasVtableHazard) {
            label.append(" [vtable hazard]");
            labelColor = new Color(220, 140, 50);
        }

        addHint(ed, doc.getLineEndOffset(headerLine), label.toString(), labelColor);

        boolean showAnyHints = state.showFieldOffsets || state.showPaddingBadges
                || state.showSplitBadges || state.showFalseSharingBadges || state.showHotColdBadges;

        if (showAnyHints) {
            int lineSize = state.cacheLineSize > 0 ? state.cacheLineSize : 64;
            for (int i = 0; i < m.layout.fields.size(); i++) {
                CppStructParser.Field f = m.layout.fields.get(i);
                int position = (m.fieldEnds != null && i < m.fieldEnds.length)
                        ? m.fieldEnds[i] + 1 : m.region.bStart() + f.end + 1;
                position = Math.min(position, doc.getTextLength());

                StringBuilder hint = new StringBuilder();
                Color color = Color.GRAY;

                if (state.showFieldOffsets) {
                    if (f.isBitfield) {
                        hint.append("off ").append(f.offset).append(":").append(f.bitOffsetInUnit).append(", ").append(f.bits).append("b");
                    } else {
                        hint.append("off ").append(f.offset).append(", ").append(f.size).append("B");
                    }
                }

                if (state.showSplitBadges && state.cacheLineBoundaries && f.size > 1 && !f.isBitfield) {
                    if (f.offset / lineSize != (f.offset + f.size - 1) / lineSize) {
                        hint.append(" [split]");
                        color = Color.RED;
                    }
                }

                if (state.showHotColdBadges && hotColdMap != null) {
                    AccessPatternAnalyzer.FieldAnalysis fa = hotColdMap.get(f.name);
                    if (fa != null) {
                        if (fa.temperature == AccessPatternAnalyzer.Temperature.COLD) {
                            hint.append(" [cold]");
                            if (color.equals(Color.GRAY)) color = new Color(170, 120, 190);
                        } else if (fa.temperature == AccessPatternAnalyzer.Temperature.HOT) {
                            hint.append(" [hot]");
                            if (color.equals(Color.GRAY)) color = new Color(255, 140, 0);
                        }
                    }
                }

                if (state.showPaddingBadges && state.paddingVisualization && !f.isBitfield) {
                    int fieldEnd = f.offset + f.size;
                    int nextOffset = (i + 1 < m.layout.fields.size()) ? m.layout.fields.get(i + 1).offset : m.layout.totalSize;
                    int pad = nextOffset - fieldEnd;
                    if (pad > 0) {
                        hint.append(" [+").append(pad).append("B pad]");
                        if (color.equals(Color.GRAY)) color = new Color(220, 140, 50);
                    }
                }

                String msg = risky.get(i);
                if (state.showFalseSharingBadges && msg != null && state.falseSharingDetection) {
                    if (msg.contains("Array False Sharing")) {
                        hint.append(" [array conflict]");
                        color = Color.RED;
                    } else if (msg.contains("Write-Write Ping-Pong")) {
                        hint.append(" [write ping-pong]");
                        color = Color.RED;
                    } else if (msg.contains("Read Invalidation")) {
                        hint.append(" [read invalidation]");
                        color = new Color(240, 110, 40);
                    } else {
                        hint.append(" [conflict]");
                        color = Color.RED;
                    }
                }

                for (StoreForwardingDetector.StlfHazard hazard : stlfHazards) {
                    if (hazard.loadField.name.equals(f.name)) {
                        if (hazard.type == StoreForwardingDetector.StlfHazardType.FOUR_K_ALIASING) {
                            hint.append(" [4k alias]");
                        } else {
                            hint.append(" [stlf stall]");
                        }
                        color = Color.RED;
                    }
                }

                if (hint.length() > 0) {
                    addHint(ed, position, hint.toString().trim(), color);
                }
            }
        }
    }

    private static boolean checkSimdHazard(FileAnalyzer.MarkedStruct m) {
        if (m.layout == null || m.layout.fields == null) return false;
        int maxSimd = 0;
        for (CppStructParser.Field f : m.layout.fields) {
            String t = f.type.toLowerCase();
            if (t.contains("__m512")) maxSimd = Math.max(maxSimd, 64);
            else if (t.contains("__m256")) maxSimd = Math.max(maxSimd, 32);
            else if (t.contains("__m128")) maxSimd = Math.max(maxSimd, 16);
        }
        if (maxSimd > 0) {
            return m.info.explicitStructAlign < maxSimd;
        }
        return false;
    }

    static void addHint(Editor ed, int offset, String text, Color color) {
        ed.getInlayModel().addAfterLineEndElement(offset, true, new Hint(text, color));
    }

    static class Hint implements EditorCustomElementRenderer {
        String txt;
        Color color;

        Hint(String txt, Color color) {
            this.txt = txt;
            this.color = color;
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            Font font = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
            return inlay.getEditor().getContentComponent().getFontMetrics(font).stringWidth(txt) + 10;
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics2D g, @NotNull Rectangle2D r, @NotNull TextAttributes a) {
            Editor e = inlay.getEditor();
            g.setColor(color);
            g.setFont(e.getColorsScheme().getFont(EditorFontType.PLAIN));
            g.drawString(txt, (float) r.getX() + 6, (float) r.getY() + e.getAscent());
        }
    }

    static class Bar implements EditorCustomElementRenderer {
        String[] cells;
        boolean[] risky;
        int cacheLineSize;
        HFTSettings.State state;
        LayoutCalculator.Layout layout;
        int w = 13;
        int h = 15;
        int labelH = 18;
        private long lastTooltipTime = 0;

        Bar(String[] cells, boolean[] risky, int cacheLineSize, HFTSettings.State state, LayoutCalculator.Layout layout) {
            this.cells = cells;
            this.risky = risky;
            this.cacheLineSize = cacheLineSize > 0 ? cacheLineSize : 64;
            this.state = state;
            this.layout = layout;
        }

        int rows() {
            int actual = (cells.length + cacheLineSize - 1) / cacheLineSize;
            if (state.maxBarRows > 0) {
                return Math.min(actual, state.maxBarRows);
            }
            return actual;
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return Math.max(Math.min(cells.length, cacheLineSize) * w, 180) + 6;
        }

        @Override
        public int calcHeightInPixels(@NotNull Inlay inlay) {
            return labelH + rows() * h + 4;
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics2D g, @NotNull Rectangle2D r, @NotNull TextAttributes a) {
            Font f = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
            g.setFont(f.deriveFont(f.getSize2D() * 0.75f));
            int x0 = (int) r.getX() + 2;
            int y0 = (int) r.getY();

            int visibleRows = rows();
            int maxBytesToPaint = Math.min(cells.length, visibleRows * cacheLineSize);

            String s = visibleRows > 1 ? "s" : "";
            g.setColor(Color.GRAY);
            String title = cells.length + "B (" + visibleRows + " line" + s + ", " + cacheLineSize + "B)";
            if (cells.length > maxBytesToPaint) title += " [truncated for performance]";
            g.drawString(title, x0, y0 + g.getFontMetrics().getAscent());

            for (int b = 0; b < maxBytesToPaint; b++) {
                int column = b % cacheLineSize;
                int row = b / cacheLineSize;
                int cx = x0 + column * w;
                int cy = y0 + labelH + row * h;

                if (cells[b] == null) {
                    g.setColor(state.paddingVisualization ? new Color(230, 75, 75) : new Color(60, 60, 60));
                } else if (risky != null && b < risky.length && risky[b] && state.falseSharingDetection) {
                    g.setColor(Color.RED);
                } else {
                    g.setColor(resolveCellColor(cells[b]));
                }
                g.fillRoundRect(cx, cy, w - 2, h - 2, 2, 2);

                if (cells[b] != null && (b == 0 || !cells[b].equals(cells[b - 1]))) {
                    g.setColor(new Color(30, 30, 30));
                    g.drawString(cells[b].substring(0, 1), cx + 3, cy + h - 5);
                }
            }
        }

        private Color resolveCellColor(String fieldName) {
            int hash = fieldName.hashCode() & 255;
            return Color.getHSBColor(hash / 255f, 0.40f, 0.80f);
        }

        public void showHoverTooltip(Editor editor, Point mousePoint, Rectangle inlayBounds) {
            if (System.currentTimeMillis() - lastTooltipTime < 400) return;

            int relX = mousePoint.x - (inlayBounds.x + 2);
            int relY = mousePoint.y - (inlayBounds.y + labelH);
            if (relX < 0 || relY < 0) return;

            int col = relX / w;
            int row = relY / h;
            int byteIndex = row * cacheLineSize + col;
            if (byteIndex < 0 || byteIndex >= cells.length) return;

            StringBuilder html = new StringBuilder("<html><div style='font-family:sans-serif;font-size:11px;padding:3px;'>");
            html.append("<b>Byte Offset:</b> ").append(byteIndex).append(" / ").append(cells.length).append("<br>");
            html.append("<b>Cache Line:</b> #").append(byteIndex / cacheLineSize)
                    .append(" [").append((byteIndex / cacheLineSize) * cacheLineSize).append("..")
                    .append(((byteIndex / cacheLineSize) + 1) * cacheLineSize - 1).append("]<br>");

            String fieldName = cells[byteIndex];
            if (fieldName == null) {
                html.append("<b style='color:#e74c3c;'>Status:</b> Wasted Padding Gap<br>");
            } else {
                html.append("<b>Field:</b> <span style='color:#2980b9;'>").append(fieldName).append("</span><br>");
                if (layout != null && layout.fields != null) {
                    for (CppStructParser.Field f : layout.fields) {
                        if (f.name.equals(fieldName)) {
                            html.append("<b>Type:</b> ").append(f.type).append("<br>");
                            html.append("<b>Span:</b> ").append(f.offset).append("..").append(f.offset + f.size - 1)
                                    .append(" (").append(f.size).append("B)<br>");
                            break;
                        }
                    }
                }
                if (risky != null && byteIndex < risky.length && risky[byteIndex]) {
                    html.append("<b style='color:red;'>Hazard:</b> False Sharing Collision Detected!<br>");
                }
            }
            html.append("</div></html>");

            lastTooltipTime = System.currentTimeMillis();
            HintManager.getInstance().showInformationHint(editor, html.toString());
        }
    }

    public static class Optimize implements IntentionAction {
        public Optimize() {}

        String text = "Optimize struct layout";
        String repl;
        int from;
        int to;

        boolean prepare(Editor ed) {
            String doc = ed.getDocument().getText();
            int caret = ed.getCaretModel().getOffset();
            ArrayList<HotRegionFinder.Region> regions = findAll(doc);
            for (HotRegionFinder.Region r : regions) {
                if (caret < r.mStart() || caret > r.bEnd()) continue;

                String block = doc.substring(r.bStart(), Math.min(r.bEnd(), doc.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                if (info == null || info.fields.size() < 2) return false;

                HashMap<String, int[]> locals = new HashMap<>();
                LayoutCalculator.Layout before = LayoutCalculator.compute(info, locals);
                if (!before.complete) return false;

                ArrayList<CppStructParser.Field> sorted = new ArrayList<>(info.fields);
                sorted.sort((a, b) -> Integer.compare(b.align, a.align));
                CppStructParser.StructInfo sortedInfo = copyWith(info, sorted);
                LayoutCalculator.Layout after = LayoutCalculator.compute(sortedInfo, locals);
                if (!after.complete || after.totalSize >= before.totalSize) return false;

                CppStructParser.Field first = info.fields.get(0);
                CppStructParser.Field last = info.fields.get(info.fields.size() - 1);

                int firstStartInDoc = r.bStart() + first.start;
                int lineStart = doc.lastIndexOf('\n', firstStartInDoc);
                from = (lineStart != -1) ? lineStart + 1 : firstStartInDoc;

                int nonWs = from;
                while (nonWs < doc.length() && (doc.charAt(nonWs) == ' ' || doc.charAt(nonWs) == '\t')) nonWs++;
                String indent = doc.substring(from, nonWs);
                if (indent.isEmpty()) indent = "    ";

                int lastEndInDoc = r.bStart() + last.end;
                int lineEnd = doc.indexOf('\n', lastEndInDoc);
                to = (lineEnd != -1) ? lineEnd : Math.min(lastEndInDoc + 1, doc.length());

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sorted.size(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(sorted.get(i).toFullCode(indent));
                }
                repl = sb.toString();

                text = "Optimize struct layout (" + before.totalSize + " -> " + after.totalSize + "B)";
                return true;
            }
            return false;
        }

        CppStructParser.StructInfo copyWith(CppStructParser.StructInfo src, ArrayList<CppStructParser.Field> fields) {
            CppStructParser.StructInfo copy = new CppStructParser.StructInfo();
            copy.name = src.name;
            copy.union = src.union;
            copy.hasVptr = src.hasVptr;
            copy.fields = fields;
            copy.typedefs = src.typedefs;
            copy.notes = src.notes;
            return copy;
        }

        @NotNull
        @Override
        public String getText() {
            return text;
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return "HFT Optimizations";
        }

        @Override
        public boolean isAvailable(@NotNull Project p, Editor ed, PsiFile f) {
            return ed != null && prepare(ed);
        }

        @Override
        public void invoke(@NotNull Project p, Editor ed, PsiFile f) {
            if (prepare(ed)) ed.getDocument().replaceString(from, to, repl);
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }
    }
}