package analysis;

import dev.hotpath.CppStructParser;
import dev.hotpath.LayoutCalculator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects Store-to-Load Forwarding (STLF) stalls and 4K Aliasing penalties in CPU Store Buffers:
 * 1. Store and subsequent Load overlap with mismatched sizes (~15-20 cycles penalty)
 * 2. Virtual addresses alias at 4096-byte boundaries (addr1 % 4096 == addr2 % 4096)
 */
public class StoreForwardingDetector {

    public enum StlfHazardType {
        SIZE_MISMATCH,
        UNALIGNED_OVERLAP,
        FOUR_K_ALIASING
    }

    public static class StlfHazard {
        public final CppStructParser.Field storeField;
        public final CppStructParser.Field loadField;
        public final StlfHazardType type;
        public final String description;

        public StlfHazard(CppStructParser.Field storeField, CppStructParser.Field loadField,
                          StlfHazardType type, String description) {
            this.storeField = storeField;
            this.loadField = loadField;
            this.type = type;
            this.description = description;
        }
    }

    @NotNull
    public static List<StlfHazard> detect(@NotNull LayoutCalculator.Layout layout) {
        List<StlfHazard> hazards = new ArrayList<>();
        if (layout.fields == null || layout.fields.size() < 2) return hazards;

        for (int i = 0; i < layout.fields.size(); i++) {
            CppStructParser.Field f1 = layout.fields.get(i);
            if (!f1.known() || f1.isBitfield) continue;

            for (int j = i + 1; j < layout.fields.size(); j++) {
                CppStructParser.Field f2 = layout.fields.get(j);
                if (!f2.known() || f2.isBitfield) continue;

                // 1. Проверка на 4K Aliasing внутри крупных структур / очередей
                if (Math.abs(f1.offset - f2.offset) >= 4096 && (Math.abs(f1.offset - f2.offset) % 4096 == 0)) {
                    hazards.add(new StlfHazard(
                            f1, f2, StlfHazardType.FOUR_K_ALIASING,
                            "4K Aliasing Hazard: '" + f1.name + "' and '" + f2.name +
                                    "' alias at 4KB offset (" + Math.abs(f1.offset - f2.offset) + "B). Triggers false Store Buffer stall"
                    ));
                }

                // 2. Проверка на пересечение смещений с несовпадающим размером (Store-to-Load Forwarding Fail)
                boolean overlaps = (f1.offset < f2.offset + f2.size) && (f2.offset < f1.offset + f1.size);
                if (overlaps && f1.size != f2.size) {
                    hazards.add(new StlfHazard(
                            f1, f2, StlfHazardType.SIZE_MISMATCH,
                            "Store-Forwarding Stall: '" + f1.name + "' (" + f1.size + "B) and '" + f2.name + "' (" + f2.size +
                                    "B) overlap. CPU cannot forward store buffer directly to load"
                    ));
                }
            }
        }
        return hazards;
    }
}