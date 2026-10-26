package dev.hotpath;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.*;
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
import java.util.List;

/**
 * Основной класс плагина. Следит за открытием редакторов и изменениями
 * текста, разбирает помеченные C++ структуры и показывает в редакторе,
 * как лежат их поля в памяти.
 */
public class HotPathHighlighter implements EditorFactoryListener {

    /**
     * Создаёт обработчик. IDE вызывает его сама.
     */
    public HotPathHighlighter() {
    }

    /**
     * Ищет регионы для обоих маркеров, {@code @padding} и {@code @p}.
     *
     * @param text текст файла
     * @return все найденные регионы
     */
    static ArrayList<HotRegionFinder.Region> findAll(String text) {
        ArrayList<HotRegionFinder.Region> all = HotRegionFinder.find(text, "@padding");
        all.addAll(HotRegionFinder.find(text, "@p"));
        return all;
    }

    /**
     * Одно поле структуры.
     */
    static class Field {
        /** Имя поля. */
        String name;
        /** Объявление поля без точки с запятой. */
        String decl;
        /** Выравнивание в байтах, 0 если тип неизвестен. */
        int al;
        /** Размер в байтах, для массива уже умножен на число элементов. */
        int size;
        /** Начало объявления, от начала региона. */
        int start;
        /** Позиция точки с запятой, от начала региона. */
        int end;

        /**
         * Создаёт описание поля.
         *
         * @param name  имя поля
         * @param decl  объявление поля
         * @param al    выравнивание в байтах
         * @param size  размер в байтах
         * @param start начало объявления
         * @param end   позиция точки с запятой
         */
        Field(String name, String decl, int al, int size, int start, int end) {
            this.name = name;
            this.decl = decl;
            this.al = al;
            this.size = size;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Возвращает размер встроенного типа. Считается под 64-битную
     * платформу, поэтому {@code long} это 8 байт.
     *
     * @param t имя типа, например {@code int}
     * @return размер в байтах или 0, если тип неизвестен
     */
    static int sizeOf(String t) {
        if (t.equals("char") || t.equals("bool") || t.equals("int8_t") || t.equals("uint8_t")) return 1;
        if (t.equals("short") || t.equals("int16_t") || t.equals("uint16_t")) return 2;
        if (t.equals("int") || t.equals("float") || t.equals("int32_t") || t.equals("uint32_t")) return 4;
        if (t.equals("long") || t.equals("double") || t.equals("int64_t") || t.equals("uint64_t") || t.equals("size_t")) return 8;
        return 0;
    }

    /**
     * Разбирает регион на поля. Однострочные комментарии забиваются
     * пробелами, чтобы позиции остальных символов не сдвинулись.
     * <p>
     * Каждое поле заканчивается точкой с запятой. Модификаторы
     * {@code unsigned}, {@code signed}, {@code const} и {@code volatile}
     * отбрасываются, на размер они не влияют. Всё после {@code =}
     * (значение по умолчанию) тоже отбрасывается, в {@code decl} оно
     * при этом остаётся. Если размер массива не
     * число, такое поле считать не умеем и выравнивание у него 0.
     * Указатель всегда 8 байт, а {@code std::atomic<T>} занимает
     * столько же, сколько {@code T}.
     *
     * @param reg текст региона от строки с маркером до закрывающей скобки
     * @return список полей или {@code null}, если в заголовке нет struct и class
     */
    static ArrayList<Field> parse(String reg) {
        char[] chars = reg.toCharArray();
        boolean inComment = false;
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\n') {
                inComment = false;
            } else if (inComment || (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '/')) {
                inComment = true;
                chars[i] = ' ';
            }
        }
        reg = new String(chars);

        int open = reg.indexOf('{');
        if (open < 0) {
            return null;
        }
        String header = reg.substring(0, open);
        if (!header.contains("struct") && !header.contains("class")) {
            return null;
        }

        ArrayList<Field> fields = new ArrayList<>();
        int s = open + 1;
        for (int i = s; i < reg.length() - 1; i++) {
            if (reg.charAt(i) != ';') {
                continue;
            }
            String decl = reg.substring(s, i).trim();
            int start = s;
            s = i + 1;
            if (decl.isEmpty()) {
                continue;
            }

            String clean = decl.replace("unsigned ", "").replace("signed ", "").replace("const ", "").replace("volatile ", "").replace("long long", "long");
            int eq = clean.indexOf('=');
            if (eq >= 0) {
                clean = clean.substring(0, eq).trim();
            }
            int count = 1;
            int bracket = clean.indexOf('[');
            if (bracket >= 0) {
                try {
                    count = Integer.parseInt(clean.substring(bracket + 1, clean.indexOf(']')).trim());
                } catch (Exception e) {
                    count = 0;
                }
                clean = clean.substring(0, bracket).trim();
            }

            String name = "?";
            int al = 0;
            int star = clean.lastIndexOf('*');
            if (star >= 0) {
                al = 8;
                name = clean.substring(star + 1).trim();
            } else {
                int space = clean.lastIndexOf(' ');
                if (space >= 0) {
                    name = clean.substring(space + 1).trim();
                    String type = clean.substring(0, space).trim();
                    int close = type.lastIndexOf('>');
                    if (type.startsWith("std::atomic<") && close > 12) {
                        type = type.substring(12, close).trim();
                    }
                    al = sizeOf(type);
                }
            }
            if (count == 0) {
                al = 0;
            }
            fields.add(new Field(name, decl, al, al * count, start, i));
        }
        return fields;
    }

    /**
     * Считает смещения полей с учётом выравнивания. Размер структуры
     * в конце округляется до самого большого выравнивания среди полей.
     *
     * @param fields поля структуры в порядке объявления
     * @return массив, где элемент {@code i} это смещение поля {@code i},
     *         а последний элемент это размер всей структуры
     */
    static int[] offsets(ArrayList<Field> fields) {
        int[] o = new int[fields.size() + 1];
        int end = 0;
        int maxAl = 1;
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            int p = end;
            if (p % f.al != 0) {
                p = p + f.al - p % f.al;
            }
            o[i] = p;
            end = o[i] + f.size;
            if (f.al > maxAl) {
                maxAl = f.al;
            }
        }
        int p = end;
        if (p % maxAl != 0) {
            p = p + maxAl - p % maxAl;
        }
        o[fields.size()] = p;
        return o;
    }

    /**
     * Проверяет, что тип каждого поля известен.
     *
     * @param fields поля структуры
     * @return {@code true}, если у всех полей выравнивание не нулевое
     */
    static boolean allKnown(ArrayList<Field> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).al == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Вызывается при открытии редактора. Подписывается на изменения
     * документа и сразу строит разметку.
     *
     * @param e событие создания редактора
     */
    @Override
    public void editorCreated(EditorFactoryEvent e) {
        Editor ed = e.getEditor();
        MyListener listener = new MyListener(ed);
        ed.getDocument().addDocumentListener(listener);
        update(ed);
    }

    /**
     * Слушатель изменений документа. Хранит редактор и при каждой правке
     * текста просит обновить разметку.
     */
    static class MyListener implements DocumentListener {
        /** Редактор, за документом которого мы следим. */
        Editor ed;

        /**
         * Создаёт слушатель.
         *
         * @param ed редактор
         */
        MyListener(Editor ed) {
            this.ed = ed;
        }

        /**
         * Вызывается после каждой правки текста.
         *
         * @param ev событие изменения документа
         */
        @Override
        public void documentChanged(DocumentEvent ev) {
            update(ed);
        }
    }

    /**
     * Откладывает обновление в поток UI: менять inlay прямо внутри
     * события документа нельзя.
     *
     * @param ed редактор
     */
    static void update(Editor ed) {
        RefreshTask task = new RefreshTask(ed);
        ApplicationManager.getApplication().invokeLater(task);
    }

    /**
     * Задача для очереди UI. Когда до неё дойдёт очередь, IDE вызовет
     * {@code run}, а там мы обновим разметку.
     */
    static class RefreshTask implements Runnable {
        /** Редактор, в котором надо обновить разметку. */
        Editor ed;

        /**
         * Создаёт задачу.
         *
         * @param ed редактор
         */
        RefreshTask(Editor ed) {
            this.ed = ed;
        }

        /**
         * Выполняется в потоке UI, когда подойдёт очередь.
         */
        @Override
        public void run() {
            refresh(ed);
        }
    }

    /**
     * Добавляет подпись в конец строки.
     *
     * @param ed     редактор
     * @param offset позиция в тексте, на чьей строке появится подпись
     * @param text   текст подписи
     */
    static void addHint(Editor ed, int offset, String text) {
        InlayModel model = ed.getInlayModel();
        Hint hint = new Hint(text);
        model.addAfterLineEndElement(offset, true, hint);
    }

    /**
     * Убирает старую разметку и строит новую для всех помеченных
     * структур в документе. Удаляются только свои {@code Hint} и
     * {@code Bar}, чужие inlay остаются.
     * <p>
     * Для карты памяти метод собирает массив, где у каждого байта
     * записано имя поля, а у паддинга стоит {@code null}. Структуры
     * без полей или с неизвестными типами получают подпись
     * {@code layout unknown}.
     *
     * @param ed редактор
     */
    static void refresh(Editor ed) {
        String text = ed.getDocument().getText();

        removeOldHints(ed, text.length());
        removeOldBars(ed, text.length());

        ArrayList<HotRegionFinder.Region> regions = findAll(text);
        for (int k = 0; k < regions.size(); k++) {
            HotRegionFinder.Region r = regions.get(k);
            drawRegion(ed, text, r);
        }
    }

    /**
     * Удаляет все свои подписи {@code Hint} в конце строк.
     *
     * @param ed     редактор
     * @param length длина текста документа
     */
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

    /**
     * Удаляет все свои карты памяти {@code Bar} над структурами.
     *
     * @param ed     редактор
     * @param length длина текста документа
     */
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
     * Рисует разметку для одной структуры: подписи у полей, итог
     * и карту памяти над ней.
     *
     * @param ed   редактор
     * @param text весь текст файла
     * @param r    регион одной структуры
     */
    static void drawRegion(Editor ed, String text, HotRegionFinder.Region r) {
        Document doc = ed.getDocument();
        InlayModel model = ed.getInlayModel();

        String regionText = text.substring(r.bStart(), r.bEnd());
        ArrayList<Field> fields = parse(regionText);
        if (fields == null) {
            return;
        }

        int n = fields.size();
        int line = doc.getLineNumber(r.bStart());

        if (n == 0 || !allKnown(fields)) {
            addHint(ed, doc.getLineEndOffset(line), "layout unknown");
            return;
        }

        int[] o = offsets(fields);
        String[] cells = makeCells(fields, o);

        for (int i = 0; i < n; i++) {
            Field f = fields.get(i);
            int position = r.bStart() + f.end + 1;
            addHint(ed, position, "offset " + o[i] + ", size " + f.size);
        }

        int total = o[n];
        int wasted = total - sumSizes(fields);
        addHint(ed, doc.getLineEndOffset(line), "size " + total + ", wasted " + wasted + " B");

        InlayProperties props = new InlayProperties();
        props.showAbove(true);
        props.relatesToPrecedingText(false);
        Bar bar = new Bar(cells);
        model.addBlockElement(doc.getLineStartOffset(line), props, bar);
    }

    /**
     * Строит массив по байтам: в каждой ячейке имя поля, которому
     * принадлежит байт, а у паддинга остаётся {@code null}.
     *
     * @param fields поля структуры
     * @param o      смещения из {@code offsets}, последний элемент это размер
     * @return массив длиной в размер структуры
     */
    static String[] makeCells(ArrayList<Field> fields, int[] o) {
        String[] cells = new String[o[fields.size()]];
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            for (int b = o[i]; b < o[i] + f.size; b++) {
                cells[b] = f.name;
            }
        }
        return cells;
    }

    /**
     * Складывает размеры всех полей, то есть считает полезные байты.
     *
     * @param fields поля структуры
     * @return сумма размеров без паддинга
     */
    static int sumSizes(ArrayList<Field> fields) {
        int sum = 0;
        for (int i = 0; i < fields.size(); i++) {
            sum = sum + fields.get(i).size;
        }
        return sum;
    }

    /**
     * Серая подпись в конце строки: смещение и размер поля
     * или итог по всей структуре.
     */
    static class Hint implements EditorCustomElementRenderer {
        /** Текст подписи. */
        String txt;

        /**
         * Создаёт подпись.
         *
         * @param txt текст подписи
         */
        Hint(String txt) {
            this.txt = txt;
        }

        /**
         * Считает ширину подписи в пикселях.
         *
         * @param inlay элемент редактора
         * @return ширина текста плюс отступ
         */
        @Override
        public int calcWidthInPixels(Inlay inlay) {
            Editor e = inlay.getEditor();
            Font font = e.getColorsScheme().getFont(EditorFontType.PLAIN);
            FontMetrics metrics = e.getContentComponent().getFontMetrics(font);
            int textWidth = metrics.stringWidth(txt);
            return textWidth + 12;
        }

        /**
         * Рисует подпись серым цветом.
         *
         * @param inlay элемент редактора
         * @param g     графика
         * @param r     область под подпись
         * @param a     атрибуты текста, не используются
         */
        @Override
        public void paint(Inlay inlay, Graphics2D g, Rectangle2D r, TextAttributes a) {
            Editor e = inlay.getEditor();
            g.setColor(Color.GRAY);
            g.setFont(e.getColorsScheme().getFont(EditorFontType.PLAIN));
            float x = (float) r.getX() + 8;
            float y = (float) r.getY() + e.getAscent();
            g.drawString(txt, x, y);
        }
    }

    /**
     * Карта памяти над структурой: квадрат на байт, 64 в ряд
     * (одна кеш-линия). У каждого поля свой цвет, паддинг красный.
     */
    static class Bar implements EditorCustomElementRenderer {
        /** Имя поля для каждого байта, null у паддинга. */
        String[] cells;
        /** Ширина квадрата в пикселях. */
        int w = 14;
        /** Высота квадрата в пикселях. */
        int h = 16;
        /** Высота строки с подписью над картой. */
        int labelH = 20;

        /**
         * Создаёт карту памяти.
         *
         * @param cells имя поля для каждого байта структуры
         */
        Bar(String[] cells) {
            this.cells = cells;
        }

        /**
         * Считает число рядов, то есть число кеш-линий.
         *
         * @return число рядов по 64 байта
         */
        int rows() {
            return (cells.length + 63) / 64;
        }

        /**
         * Считает ширину карты в пикселях. Меньше 220 не бывает,
         * иначе обрежется подпись сверху.
         *
         * @param inlay элемент редактора
         * @return ширина карты с отступом
         */
        @Override
        public int calcWidthInPixels(Inlay inlay) {
            int width = Math.min(cells.length, 64) * w;
            if (width < 220) {
                width = 220;
            }
            return width + 8;
        }

        /**
         * Считает высоту карты в пикселях.
         *
         * @param inlay элемент редактора
         * @return высота подписи плюс все ряды
         */
        @Override
        public int calcHeightInPixels(Inlay inlay) {
            return labelH + rows() * h + 6;
        }

        /**
         * Рисует подпись и квадраты байтов. Цвет поля берётся из хеша
         * его имени, поэтому у одного поля он всегда одинаковый. На первом
         * байте поля стоит первая буква имени.
         *
         * @param inlay элемент редактора
         * @param g     графика
         * @param r     область под карту
         * @param a     атрибуты текста, не используются
         */
        @Override
        public void paint(Inlay inlay, Graphics2D g, Rectangle2D r, TextAttributes a) {
            Font f = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
            float smallSize = f.getSize2D() * 0.8f;
            g.setFont(f.deriveFont(smallSize));

            int x0 = (int) r.getX() + 4;
            int y0 = (int) r.getY();

            drawLabel(g, x0, y0);

            for (int b = 0; b < cells.length; b++) {
                int column = b % 64;
                int row = b / 64;
                int x = x0 + column * w;
                int y = y0 + labelH + row * h;
                drawCell(g, b, x, y);
            }
        }

        /**
         * Рисует серую подпись над картой: размер и число кеш-линий.
         *
         * @param g  графика
         * @param x0 левый край карты
         * @param y0 верхний край карты
         */
        void drawLabel(Graphics2D g, int x0, int y0) {
            String s = "";
            if (rows() > 1) {
                s = "s";
            }
            String label = "memory map " + cells.length + " B, " + rows() + " cache line" + s;
            g.setColor(Color.GRAY);
            g.drawString(label, x0, y0 + g.getFontMetrics().getAscent() + 2);
        }

        /**
         * Рисует квадрат одного байта и, если это первый байт поля,
         * первую букву его имени.
         *
         * @param g графика
         * @param b номер байта
         * @param x левый край квадрата
         * @param y верхний край квадрата
         */
        void drawCell(Graphics2D g, int b, int x, int y) {
            if (cells[b] == null) {
                g.setColor(new Color(230, 70, 70));
            } else {
                int hash = cells[b].hashCode() & 255;
                float hue = hash / 255f;
                g.setColor(Color.getHSBColor(hue, 0.45f, 0.85f));
            }
            g.fillRoundRect(x, y, w - 2, h - 2, 4, 4);

            if (cells[b] == null) {
                return;
            }
            boolean isFirstByteOfField = b == 0 || !cells[b].equals(cells[b - 1]);
            if (isFirstByteOfField) {
                g.setColor(new Color(40, 40, 40));
                g.drawString(cells[b].substring(0, 1), x + 4, y + h - 5);
            }
        }
    }

    /**
     * Пункт меню Alt+Enter. Сортирует поля по убыванию выравнивания,
     * если от этого структура становится меньше.
     */
    public static class Optimize implements IntentionAction {
        /**
         * Создаёт действие. IDE вызывает его сама.
         */
        public Optimize() {
        }

        /** Текст пункта меню. В prepare к нему дописываются размеры до и после. */
        String text = "Optimize struct layout";
        /** Новый текст объявлений полей. */
        String repl;
        /** Начало заменяемого куска в документе. */
        int from;
        /** Конец заменяемого куска в документе. */
        int to;

        /**
         * Проверяет структуру под курсором и, если сортировка уменьшит
         * размер, готовит новый текст.
         * <p>
         * Поля сортируются пузырьком по убыванию выравнивания, порядок
         * равных полей при этом не меняется. Отступ для новых строк берётся
         * у первого поля. Комментарии внутри структуры при замене
         * пропадают, потому что поля пишутся заново.
         *
         * @param ed редактор
         * @return {@code true}, если размер уменьшится
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

                ArrayList<Field> fields = parse(doc.substring(r.bStart(), r.bEnd()));
                if (fields == null || fields.size() < 2 || !allKnown(fields)) {
                    return false;
                }

                ArrayList<Field> sorted = sortByAlignment(fields);
                int before = offsets(fields)[fields.size()];
                int after = offsets(sorted)[sorted.size()];
                if (after >= before) {
                    return false;
                }

                Field first = fields.get(0);
                Field last = fields.get(fields.size() - 1);
                String indent = findIndent(doc, r.bStart(), first);

                repl = buildText(sorted, indent);
                from = r.bStart() + first.start;
                to = r.bStart() + last.end + 1;
                text = "Optimize struct layout (" + before + " -> " + after + " B)";
                return true;
            }
            return false;
        }

        /**
         * Сортирует копию списка пузырьком по убыванию выравнивания.
         * Поля с одинаковым выравниванием остаются в прежнем порядке.
         *
         * @param fields поля в исходном порядке
         * @return новый отсортированный список
         */
        ArrayList<Field> sortByAlignment(ArrayList<Field> fields) {
            ArrayList<Field> sorted = new ArrayList<>(fields);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = 0; j < sorted.size() - 1 - i; j++) {
                    Field left = sorted.get(j);
                    Field right = sorted.get(j + 1);
                    if (left.al < right.al) {
                        sorted.set(j, right);
                        sorted.set(j + 1, left);
                    }
                }
            }
            return sorted;
        }

        /**
         * Находит отступ первого поля. Если не получилось,
         * возвращает четыре пробела.
         *
         * @param doc         текст документа
         * @param regionStart начало региона в документе
         * @param first       первое поле структуры
         * @return строка из пробелов или табов
         */
        String findIndent(String doc, int regionStart, Field first) {
            String raw = doc.substring(regionStart + first.start, regionStart + first.end);
            String lead = raw.substring(0, raw.length() - raw.stripLeading().length());
            if (lead.contains("\n")) {
                return lead.substring(lead.lastIndexOf('\n') + 1);
            }
            return "    ";
        }

        /**
         * Собирает новый текст объявлений: каждое поле на своей строке
         * с отступом и точкой с запятой.
         *
         * @param sorted поля в новом порядке
         * @param indent отступ для каждой строки
         * @return текст для вставки в документ
         */
        String buildText(ArrayList<Field> sorted, String indent) {
            String result = "";
            for (int i = 0; i < sorted.size(); i++) {
                result = result + "\n" + indent + sorted.get(i).decl + ";";
            }
            return result;
        }

        /**
         * Возвращает текст пункта меню.
         *
         * @return название действия
         */
        @Override
        public String getText() {
            return text;
        }

        /**
         * Возвращает название группы действий.
         *
         * @return название группы
         */
        @Override
        public String getFamilyName() {
            return "Hot path";
        }

        /**
         * Решает, показывать ли пункт: курсор должен стоять в помеченной
         * структуре, которую можно уменьшить.
         *
         * @param p  проект
         * @param ed редактор
         * @param f  файл
         * @return {@code true}, если структуру можно оптимизировать
         */
        @Override
        public boolean isAvailable(Project p, Editor ed, PsiFile f) {
            return ed != null && prepare(ed);
        }

        /**
         * Заменяет объявления полей в документе на переставленные.
         *
         * @param p  проект
         * @param ed редактор
         * @param f  файл
         */
        @Override
        public void invoke(Project p, Editor ed, PsiFile f) {
            if (prepare(ed)) {
                ed.getDocument().replaceString(from, to, repl);
            }
        }

        /**
         * Действие меняет документ, поэтому выполняется внутри write action.
         *
         * @return {@code true}
         */
        @Override
        public boolean startInWriteAction() {
            return true;
        }
    }
}