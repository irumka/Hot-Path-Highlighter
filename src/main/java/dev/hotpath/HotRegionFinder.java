package dev.hotpath;

import java.util.ArrayList;

/**
 * Ищет в тексте файла структуры, помеченные маркером в комментарии.
 */
public class HotRegionFinder {

    /**
     * Закрыт, потому что все методы класса статические.
     */
    private HotRegionFinder() {
    }

    /**
     * Границы найденной структуры в тексте файла.
     */
    public static class Region {
        /** Начало строки с маркером. */
        int mStart;
        /** Конец строки с маркером. */
        int mEnd;
        /** Начало блока структуры, то есть строка после маркера. */
        int bStart;
        /** Позиция сразу после закрывающей фигурной скобки. */
        int bEnd;

        /**
         * Создаёт регион по заданным границам.
         *
         * @param mStart начало строки с маркером
         * @param mEnd   конец строки с маркером
         * @param bStart начало блока структуры
         * @param bEnd   позиция после закрывающей скобки
         */
        Region(int mStart, int mEnd, int bStart, int bEnd) {
            this.mStart = mStart;
            this.mEnd = mEnd;
            this.bStart = bStart;
            this.bEnd = bEnd;
        }

        /**
         * Возвращает начало строки с маркером.
         *
         * @return позиция в тексте файла
         */
        public int mStart() {
            return mStart;
        }

        /**
         * Возвращает конец строки с маркером.
         *
         * @return позиция в тексте файла
         */
        public int mEnd() {
            return mEnd;
        }

        /**
         * Возвращает начало блока структуры.
         *
         * @return позиция в тексте файла
         */
        public int bStart() {
            return bStart;
        }

        /**
         * Возвращает конец блока структуры.
         *
         * @return позиция в тексте файла
         */
        public int bEnd() {
            return bEnd;
        }
    }

    /**
     * Ищет все структуры, перед которыми стоит заданный маркер.
     * Маркер должен быть на отдельной строке: {@code // @p},
     * {@code //@p} или то же самое в блочном комментарии. Вместо
     * {@code @p} подходит и {@code @padding}.
     * <p>
     * После маркера метод идёт по строкам и считает фигурные скобки до
     * закрывающей. Если до первой открывающей скобки встретилась точка
     * с запятой, после маркера идёт не структура, и регион не создаётся.
     *
     * @param text   весь текст файла
     * @param marker {@code @p} или {@code @padding}, без символов комментария
     * @return найденные регионы
     */
    public static ArrayList<Region> find(String text, String marker) {
        ArrayList<Region> result = new ArrayList<>();
        String[] lines = text.split("\n");
        int pos = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            boolean isMarker = trimmed.equals("// " + marker)
                    || trimmed.equals("//" + marker)
                    || trimmed.equals("/* " + marker + " */");

            if (isMarker) {
                int mStart = pos;
                int mEnd = pos + lines[i].length();
                int lineStart = mEnd + 1;
                int bracket = 0;
                boolean done = false;

                for (int j = i + 1; j < lines.length && !done; j++) {
                    String cur = lines[j];
                    for (int k = 0; k < cur.length(); k++) {
                        char c = cur.charAt(k);
                        if (c == '{') {
                            bracket++;
                        } else if (c == '}') {
                            bracket--;
                            if (bracket == 0) {
                                result.add(new Region(mStart, mEnd, mEnd + 1, lineStart + k + 1));
                                done = true;
                                break;
                            }
                        } else if (c == ';' && bracket == 0) {
                            done = true;
                            break;
                        }
                    }
                    lineStart = lineStart + cur.length() + 1;
                }
            }

            pos = pos + lines[i].length() + 1;
        }
        return result;
    }
}