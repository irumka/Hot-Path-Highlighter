package dev.hotpath;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Main plugin class. Listens for editor openings and text changes, runs the
 * full file analysis (layout + false sharing) and renders memory maps,
 * per-field hints and conflict warnings in the editor.
 */
public class HotPathHighlighter implements EditorFactoryListener {

    /** Creates the handler. The IDE calls it automatically. */
    public HotPathHighlighter() {
    }

    /**
     * Finds regions for both markers, {@code @padding} and {@code @p}.
     * @param text file text
     * @return all found regions
     */
    static ArrayList<HotRegionFinder.Region> findAll(String text) {
        ArrayList<HotRegionFinder.Region> all = HotRegionFinder.find(text, "@padding");
        all.addAll(HotRegionFinder.find(text, "@p"));
        return all;
    }

    /**
     * Called when an editor is opened. Subscribes to document changes
     * and immediately builds the layout.
     * @param e editor creation event
     */
    @Override
    public void editorCreated(EditorFactoryEvent e) {
        Editor ed = e.getEditor();
        MyListener listener = new MyListener(ed);
        ed.getDocument().addDocumentListener(listener);
        update(ed);
    }

    /** Document change listener: requests a refresh on every text edit. */
    static class MyListener implements DocumentListener {
        /** Editor whose document we are watching. */
        Editor ed;

        MyListener(Editor ed) {
            this.ed = ed;
        }

        @Override
        public void documentChanged(DocumentEvent ev) {
            update(ed);
        }
    }

    /**
     * Defers the update to the UI thread: inlays cannot be changed
     * directly inside a document event.
     * @param ed editor
     */
    static void update(Editor ed) {
        RefreshTask task = new RefreshTask(ed);
        ApplicationManager.getApplication().invokeLater(task);
    }

    /** Task for the UI queue that calls {@code refresh}. */
    static class RefreshTask implements Runnable {
        Editor ed;

        RefreshTask(Editor ed) {
            this.ed = ed;
        }

        @Override
        public void run() {
            refresh(ed);
        }
    }

    /**
     * Adds a colored hint at the end of the line containing {@code offset}.
     */
    static void addHint(Editor ed, int offset, String text, Color color) {
        InlayModel model = ed.getInlayModel();
        Hint hint = new Hint(text, color);
        model.addAfterLineEndElement(offset, true, hint);
    }

    /** Removes all own {@code Hint} end-of-line hints. */
    static void removeOldHints(Editor ed, int length) {
        InlayModel model = ed.getInlayModel();
        List<Inlay<?>> hints = model.getAfterLineEndElementsInRange(0, length);
        for (int i = 0; i < hints.size(); i++) {
            Inlay<?> inlay = hints.get(i);
            if (inlay.getRenderer() instanceof Hint) {
                Disposer.dispose(inlay);
            }
        }
    }

    /** Removes all own {@code Bar} memory maps above the structs. */
    static void removeOldBars(Editor ed, int length) {
        InlayModel model = ed.getInlayModel();
        List<Inlay<?>> bars = model.getBlockElementsInRange(0, length);
        for (int i = 0; i < bars.size(); i++) {
            Inlay<?> inlay = bars.get(i);
            if (inlay.getRenderer() instanceof Bar) {
                Disposer.dispose(inlay);
            }
        }
    }

    /**
     * Main entry point: analyzes the whole document and draws every
     * marked struct. Analysis bugs never break the editor: each struct
     * is drawn inside its own guard.
     * @param ed editor
     */
    static void refresh(Editor ed) {
        String text = ed.getDocument().getText();
        removeOldHints(ed, text.length());
        removeOldBars(ed, text.length());
        FileAnalyzer.Result result;
        try {
            result = FileAnalyzer.analyze(text);
        } catch (Exception e) {
            return;
        }
        if (result == null) {
            return;
        }
        for (int k = 0; k < result.structs.size(); k++) {
            FileAnalyzer.MarkedStruct m = result.structs.get(k);
            try {
                ensureCellsAndConflicts(m, text);
                drawMarkedStruct(ed, m);
            } catch (Exception e) {
                // Skip a broken struct, keep the rest of the file usable.
            }
        }
    }

    /**
     * Safety net: when the layout calculator did not fill the byte array,
     * build it here and re-run the false sharing analysis, otherwise
     * {@link FalseSharingAnalyzer} silently skips the layout.
     * @param m    marked struct
     * @param text full document text
     */
    static void ensureCellsAndConflicts(FileAnalyzer.MarkedStruct m, String text) {
        if (m.layout == null || !m.layout.complete || m.layout.cells != null) {
            return;
        }
        m.layout.cells = buildCells(m.layout);
        int a = Math.max(0, Math.min(m.region.bStart(), text.length()));
        int b = Math.max(a, Math.min(m.region.bEnd(), text.length()));
        String block = text.substring(a, b);
        m.conflicts = FalseSharingAnalyzer.analyze(m.layout, block);
        m.risky = FalseSharingAnalyzer.riskyFields(m.conflicts);
    }

    /**
     * Builds a byte-by-byte array of field indices: index per byte, -1 for padding.
     * @param l computed layout
     * @return array of length totalSize
     */
    static int[] buildCells(LayoutCalculator.Layout l) {
        int[] cells = new int[Math.max(0, l.totalSize)];
        Arrays.fill(cells, -1);
        for (int i = 0; i < l.fields.size(); i++) {
            CppStructParser.Field f = l.fields.get(i);
            for (int b = f.offset; b < f.offset + f.size && b < cells.length; b++) {
                if (b >= 0) {
                    cells[b] = i;
                }
            }
        }
        return cells;
    }

    /**
     * Draws one analyzed struct: memory map above it, a status label on
     * the header line and per-field hints with conflict warnings.
     * @param ed editor
     * @param m  marked struct from FileAnalyzer
     */
    static void drawMarkedStruct(Editor ed, FileAnalyzer.MarkedStruct m) {
        Document doc = ed.getDocument();
        InlayModel model = ed.getInlayModel();
        int headerLine = doc.getLineNumber(m.region.bStart());

        if (m.layout == null || !m.layout.complete) {
            addHint(ed, doc.getLineEndOffset(headerLine), "layout unknown", Color.GRAY);
            return;
        }

        int[] layoutCells = m.layout.cells != null ? m.layout.cells : buildCells(m.layout);

        // Convert int[] (field indices) to String[] (field names) for the Bar renderer
        String[] stringCells = new String[layoutCells.length];
        for (int i = 0; i < layoutCells.length; i++) {
            if (layoutCells[i] >= 0 && layoutCells[i] < m.layout.fields.size()) {
                stringCells[i] = m.layout.fields.get(layoutCells[i]).name;
            }
        }

        // Mark bytes that belong to risky (conflicting) fields.
        boolean[] riskyByte = new boolean[layoutCells.length];
        HashMap<Integer, String> risky = m.risky != null ? m.risky : new HashMap<>();
        for (Integer idx : risky.keySet()) {
            if (idx == null || idx < 0 || idx >= m.layout.fields.size()) {
                continue;
            }
            CppStructParser.Field f = m.layout.fields.get(idx);
            for (int b = f.offset; b < f.offset + f.size && b < riskyByte.length; b++) {
                if (b >= 0) {
                    riskyByte[b] = true;
                }
            }
        }

        // Memory map above the struct header.
        InlayProperties props = new InlayProperties();
        props.showAbove(true);
        props.relatesToPrecedingText(false);
        Bar bar = new Bar(stringCells, riskyByte);
        model.addBlockElement(doc.getLineStartOffset(headerLine), props, bar);

        // Status label on the header line.
        String status = m.status();
        Color labelColor = Color.GRAY;
        if ("FALSE SHARING".equals(status)) {
            labelColor = new Color(255, 90, 60);
        } else if ("WARNING".equals(status)) {
            labelColor = new Color(230, 180, 60);
        } else if ("OK".equals(status)) {
            labelColor = new Color(110, 190, 110);
        }
        String label = "size " + m.layout.totalSize + ", wasted " + m.layout.wasted + " B [" + status + "]";
        if (m.conflicts != null && !m.conflicts.isEmpty()) {
            label += " \u26A0 " + m.conflicts.size();
        }
        addHint(ed, doc.getLineEndOffset(headerLine), label, labelColor);

        // Per-field hints at the semicolon line.
        for (int i = 0; i < m.layout.fields.size(); i++) {
            CppStructParser.Field f = m.layout.fields.get(i);
            int position;
            if (m.fieldEnds != null && i < m.fieldEnds.length) {
                position = m.fieldEnds[i] + 1;
            } else {
                position = m.region.bStart() + f.end + 1;
            }
            position = Math.min(position, doc.getTextLength());
            String hint = "offset " + f.offset + ", size " + f.size;
            Color color = Color.GRAY;
            String msg = risky.get(i);
            if (msg != null) {
                hint += " \u26A0 " + msg;
                color = new Color(255, 90, 60);
            }
            addHint(ed, position, hint, color);
        }
    }

    /** Gray or colored hint at the end of a line. */
    static class Hint implements EditorCustomElementRenderer {
        /** Hint text. */
        String txt;
        /** Hint color. */
        Color color;

        Hint(String txt, Color color) {
            this.txt = txt;
            this.color = color;
        }

        @Override
        public int calcWidthInPixels(Inlay inlay) {
            Editor e = inlay.getEditor();
            Font font = e.getColorsScheme().getFont(EditorFontType.PLAIN);
            FontMetrics metrics = e.getContentComponent().getFontMetrics(font);
            return metrics.stringWidth(txt) + 12;
        }

        @Override
        public void paint(Inlay inlay, Graphics2D g, Rectangle2D r, TextAttributes a) {
            Editor e = inlay.getEditor();
            g.setColor(color);
            g.setFont(e.getColorsScheme().getFont(EditorFontType.PLAIN));
            float x = (float) r.getX() + 8;
            float y = (float) r.getY() + e.getAscent();
            g.drawString(txt, x, y);
        }
    }

    /**
     * Memory map above the struct: one square per byte, 64 per row
     * (one cache line). Field colors come from the name hash, padding is
     * red, bytes involved in false sharing are bright red.
     */
    static class Bar implements EditorCustomElementRenderer {
        /** Field name for each byte, null for padding. */
        String[] cells;
        /** True for bytes belonging to conflicting fields. */
        boolean[] risky;
        /** Width of one square in pixels. */
        int w = 14;
        /** Height of one square in pixels. */
        int h = 16;
        /** Height of the label row above the map. */
        int labelH = 20;

        Bar(String[] cells, boolean[] risky) {
            this.cells = cells;
            this.risky = risky;
        }

        /** Number of 64-byte rows, i.e. cache lines. */
        int rows() {
            return (cells.length + 63) / 64;
        }

        @Override
        public int calcWidthInPixels(Inlay inlay) {
            int width = Math.min(cells.length, 64) * w;
            if (width < 220) {
                width = 220;
            }
            return width + 8;
        }

        @Override
        public int calcHeightInPixels(Inlay inlay) {
            return labelH + rows() * h + 6;
        }

        @Override
        public void paint(Inlay inlay, Graphics2D g, Rectangle2D r, TextAttributes a) {
            Font f = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
            g.setFont(f.deriveFont(f.getSize2D() * 0.8f));
            int x0 = (int) r.getX() + 4;
            int y0 = (int) r.getY();
            drawLabel(g, x0, y0);
            for (int b = 0; b < cells.length; b++) {
                int column = b % 64;
                int row = b / 64;
                drawCell(g, b, x0 + column * w, y0 + labelH + row * h);
            }
        }

        /** Gray label above the map: total size and cache line count. */
        void drawLabel(Graphics2D g, int x0, int y0) {
            String s = rows() > 1 ? "s" : "";
            String label = "memory map " + cells.length + " B, " + rows() + " cache line" + s;
            g.setColor(Color.GRAY);
            g.drawString(label, x0, y0 + g.getFontMetrics().getAscent() + 2);
        }

        /** Draws one byte square plus the field initial on its first byte. */
        void drawCell(Graphics2D g, int b, int x, int y) {
            HotPathSettings st = HotPathSettings.get();
            if (cells[b] == null) {
                g.setColor(st.paddingColor());
            } else if (risky != null && b < risky.length && risky[b]) {
                g.setColor(st.falseShareColor());
            } else {
                int hash = cells[b].hashCode() & 255;
                g.setColor(Color.getHSBColor(hash / 255f, 0.45f, 0.85f));
            }
            g.fillRoundRect(x, y, w - 2, h - 2, 4, 4);
            if (cells[b] == null) {
                return;
            }
            boolean first = b == 0 || !cells[b].equals(cells[b - 1]);
            if (first) {
                g.setColor(new Color(40, 40, 40));
                g.drawString(cells[b].substring(0, 1), x + 4, y + h - 5);
            }
        }
    }

    /**
     * Alt+Enter intention: reorders fields by descending alignment when
     * that shrinks the struct. Thread annotations are preserved.
     */
    public static class Optimize implements IntentionAction {

        /** Creates the action. The IDE calls it automatically. */
        public Optimize() {
        }

        /** Menu item text; prepare() appends before/after sizes. */
        String text = "Optimize struct layout";
        /** Replacement text for the field declarations. */
        String repl;
        /** Start of the replaced range in the document. */
        int from;
        /** End of the replaced range in the document. */
        int to;

        /**
         * Checks the struct under the caret and prepares the replacement
         * when sorting by alignment reduces the total size.
         * @param ed editor
         * @return {@code true} when the optimization is applicable
         */
        boolean prepare(Editor ed) {
            String doc = ed.getDocument().getText();
            int caret = ed.getCaretModel().getOffset();
            ArrayList<HotRegionFinder.Region> regions = findAll(doc);
            for (int k = 0; k < regions.size(); k++) {
                HotRegionFinder.Region r = regions.get(k);
                if (caret < r.mStart() || caret > r.bEnd()) {
                    continue;
                }
                String block = doc.substring(r.bStart(), Math.min(r.bEnd(), doc.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                if (info == null || info.fields.size() < 2) {
                    return false;
                }
                HashMap<String, int[]> locals = new HashMap<>();
                LayoutCalculator.Layout before = LayoutCalculator.compute(info, locals);
                if (!before.complete) {
                    return false;
                }
                ArrayList<CppStructParser.Field> sorted = new ArrayList<>(info.fields);
                sorted.sort((a, b) -> Integer.compare(b.align, a.align));
                CppStructParser.StructInfo sortedInfo = copyWith(info, sorted);
                LayoutCalculator.Layout after = LayoutCalculator.compute(sortedInfo, locals);
                if (!after.complete || after.totalSize >= before.totalSize) {
                    return false;
                }
                CppStructParser.Field first = info.fields.get(0);
                CppStructParser.Field last = info.fields.get(info.fields.size() - 1);
                String indent = findIndent(doc, r.bStart(), first);
                repl = buildText(sorted, indent);
                from = r.bStart() + first.start;
                to = r.bStart() + last.end + 1;
                text = "Optimize struct layout (" + before.totalSize + " -> " + after.totalSize + " B)";
                return true;
            }
            return false;
        }

        /** Copies struct metadata with a new field list. */
        CppStructParser.StructInfo copyWith(CppStructParser.StructInfo src,
                                            ArrayList<CppStructParser.Field> fields) {
            CppStructParser.StructInfo copy = new CppStructParser.StructInfo();
            copy.name = src.name;
            copy.union = src.union;
            copy.hasVptr = src.hasVptr;
            copy.fields = fields;
            copy.typedefs = src.typedefs;
            copy.notes = src.notes;
            return copy;
        }

        /** Indentation of the first field, four spaces as fallback. */
        String findIndent(String doc, int regionStart, CppStructParser.Field first) {
            String raw = doc.substring(regionStart + first.start, regionStart + first.end);
            String lead = raw.substring(0, raw.length() - raw.stripLeading().length());
            if (lead.contains("\n")) {
                return lead.substring(lead.lastIndexOf('\n') + 1);
            }
            return "    ";
        }

        /** Assembles the reordered declarations, keeping thread tags. */
        String buildText(ArrayList<CppStructParser.Field> sorted, String indent) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                CppStructParser.Field f = sorted.get(i);
                sb.append("\n").append(indent).append(f.decl).append(";");
                if (f.thread != null) {
                    sb.append(" // @thread ").append(f.thread);
                }
            }
            return sb.toString();
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public String getFamilyName() {
            return "Hot path";
        }

        @Override
        public boolean isAvailable(Project p, Editor ed, PsiFile f) {
            return ed != null && prepare(ed);
        }

        @Override
        public void invoke(Project p, Editor ed, PsiFile f) {
            if (prepare(ed)) {
                ed.getDocument().replaceString(from, to, repl);
            }
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }
    }
}