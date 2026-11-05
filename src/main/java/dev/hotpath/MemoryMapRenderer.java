package dev.hotpath;

import java.awt.Color;

/**
 * Draws a memory map: one square per byte, cache lines as rows, field
 * colors derived from the field name, padding ghosts and red false
 * sharing stripes. Used both as an editor inlay renderer and inside the
 * tool window through {@code paintInto}.
 */
public class MemoryMapRenderer {

    /** Layout to draw. */
    public final LayoutCalculator.Layout layout;
    /** Conflicts that should be painted as stripes, may be empty. */
    public final java.util.HashMap<Integer, String> risky;
    /** Square width in pixels. */
    public int w = 14;
    /** Square height in pixels. */
    public int h = 16;
    /** Height of the caption row above the squares. */
    public int labelH = 20;

    /**
     * Creates a renderer for one layout.
     *
     * @param layout computed layout with cells
     * @param risky  map of field index to warning message
     */
    public MemoryMapRenderer(LayoutCalculator.Layout layout, java.util.HashMap<Integer, String> risky) {
        this.layout = layout;
        this.risky = risky != null ? risky : new java.util.HashMap<>();
    }

    /**
     * Number of rows, i.e. cache lines.
     *
     * @return row count
     */
    public int rows() {
        if (layout.cells == null) {
            return 0;
        }
        int line = HotPathSettings.get().cacheLine();
        return (layout.cells.length + line - 1) / line;
    }

    /**
     * Preferred pixel width of the whole map.
     *
     * @return width including padding
     */
    public int preferredWidth() {
        int line = HotPathSettings.get().cacheLine();
        int width = Math.min(layout.cells != null ? layout.cells.length : 0, line) * w;
        if (width < 220) {
            width = 220;
        }
        return width + 8;
    }

    /**
     * Preferred pixel height of the whole map.
     *
     * @return caption plus all rows
     */
    public int preferredHeight() {
        return labelH + rows() * h + 6;
    }

    /**
     * Paints the map into any graphics context.
     *
     * @param g  graphics
     * @param x0 left edge
     * @param y0 top edge
     */
    public void paintInto(java.awt.Graphics2D g, int x0, int y0) {
        HotPathSettings st = HotPathSettings.get();
        if (layout.cells == null) {
            g.setColor(Color.GRAY);
            g.drawString("memory map unavailable: layout unknown", x0, y0 + labelH);
            return;
        }
        java.awt.Font f = g.getFont().deriveFont(g.getFont().getSize2D() * 0.8f);
        g.setFont(f);
        int line = st.cacheLine();

        drawCaption(g, x0, y0, st);

        for (int b = 0; b < layout.cells.length; b++) {
            int column = b % line;
            int row = b / line;
            int x = x0 + 4 + column * w;
            int y = y0 + labelH + row * h;
            drawCell(g, b, x, y, st);
        }
        drawCacheRuler(g, x0, y0, st);
    }

    /**
     * Caption line above the map: size, lines, score.
     *
     * @param g  graphics
     * @param x0 left edge
     * @param y0 top edge
     * @param st settings
     */
    void drawCaption(java.awt.Graphics2D g, int x0, int y0, HotPathSettings st) {
        String s = rows() > 1 ? "s" : "";
        String label = "memory map " + layout.totalSize + " B, " + rows() + " cache line" + s;
        if (layout.score >= 0) {
            label = label + ", health " + layout.score + "/100";
        }
        g.setColor(Color.GRAY);
        g.drawString(label, x0 + 4, y0 + g.getFontMetrics().getAscent() + 2);
    }

    /**
     * Small offset ruler under the last row: 0, 64, 128...
     *
     * @param g  graphics
     * @param x0 left edge
     * @param y0 top edge
     * @param st settings
     */
    void drawCacheRuler(java.awt.Graphics2D g, int x0, int y0, HotPathSettings st) {
        int line = st.cacheLine();
        int y = y0 + labelH + rows() * h + 4;
        g.setColor(st.cacheLineColor());
        for (int start = line; start < layout.cells.length; start += line) {
            int col = start % line;
            int x = x0 + 4 + col * w;
            // Separator is drawn per row already; here only label the boundary.
            if (col == 0) {
                g.drawString(String.valueOf(start), x - 6, y + g.getFontMetrics().getAscent());
            }
        }
    }

    /**
     * Paints one byte square plus optional first-letter badge and the
     * false sharing stripe over risky fields.
     *
     * @param g  graphics
     * @param b  byte index
     * @param x  left edge of the square
     * @param y  top edge of the square
     * @param st settings
     */
    void drawCell(java.awt.Graphics2D g, int b, int x, int y, HotPathSettings st) {
        int owner = layout.cells[b];
        if (owner < 0) {
            g.setColor(st.paddingColor());
            g.fillRoundRect(x, y, w - 2, h - 2, 4, 4);
            return;
        }
        CppStructParser.Field fld = layout.fields.get(owner);
        g.setColor(colorOf(fld.name, st));
        g.fillRoundRect(x, y, w - 2, h - 2, 4, 4);

        boolean first = b == 0 || layout.cells[b - 1] != owner;
        if (first) {
            g.setColor(new Color(40, 40, 40));
            g.drawString(fld.name.substring(0, 1), x + 4, y + h - 5);
        }
        // Red diagonal stripe on risky fields.
        if (risky.containsKey(owner)) {
            g.setColor(st.falseShareColor());
            g.fillRect(x, y, w - 2, 3);
        }
        // Cache line separator: thick gap before column 0 of each row.
        if (b % st.cacheLine() == 0 && b > 0) {
            g.setColor(st.cacheLineColor());
            g.drawLine(x - 1, y - 2, x - 1, y + h);
        }
    }

    /**
     * Stable color per field name. Mode 0 uses hue from the hash, mode 1
     * picks from a fixed palette.
     *
     * @param name field name
     * @param st   settings
     * @return fill color
     */
    public static Color colorOf(String name, HotPathSettings st) {
        if (st.paletteMode() == 1) {
            Color[] pal = {
                    new Color(0x5B9BD5), new Color(0x70AD47), new Color(0xFFC000),
                    new Color(0x9B59B6), new Color(0x1ABC9C), new Color(0xE67E22),
                    new Color(0x34495E), new Color(0xF1C40F)
            };
            int idx = Math.abs(name.hashCode()) % pal.length;
            return pal[idx];
        }
        int hash = name.hashCode() & 255;
        float hue = hash / 255f;
        return Color.getHSBColor(hue, 0.45f, 0.85f);
    }
}
