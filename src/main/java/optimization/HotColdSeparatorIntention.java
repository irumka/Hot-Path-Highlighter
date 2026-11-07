package optimization;

import analysis.AccessPatternAnalyzer;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import dev.hotpath.CppStructParser;
import dev.hotpath.HotRegionFinder;
import dev.hotpath.LayoutCalculator;
import org.jetbrains.annotations.NotNull;
import ui.HFTSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alt+Enter action: Packs all HOT fields into the primary 64-byte L1 cache line,
 * pushing COLD (diagnostic/error) fields to the end with cache line alignment.
 */
public class HotColdSeparatorIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    @NotNull
    @Override
    public String getText() {
        return "Separate hot and cold data (pack hot path into first cache line)";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "HFT Optimizations";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return false;

        String text = editor.getDocument().getText();
        int offset = editor.getCaretModel().getOffset();
        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);

        for (HotRegionFinder.Region r : regions) {
            if (offset >= r.bStart() && offset <= r.bEnd()) {
                String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                if (info == null || info.fields.size() < 2) return false;

                LayoutCalculator.Layout layout = LayoutCalculator.compute(info, new HashMap<>());
                if (!layout.complete) return false;

                Map<String, AccessPatternAnalyzer.FieldAnalysis> analysis = AccessPatternAnalyzer.analyze(info, block);

                boolean hasColdInFirstCacheLine = false;
                boolean hasHot = false;
                int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;

                for (CppStructParser.Field f : layout.fields) {
                    AccessPatternAnalyzer.FieldAnalysis fa = analysis.get(f.name);
                    if (fa != null && fa.temperature == AccessPatternAnalyzer.Temperature.HOT) {
                        hasHot = true;
                    }
                    if (fa != null && fa.temperature == AccessPatternAnalyzer.Temperature.COLD && f.offset < cacheLineSize) {
                        hasColdInFirstCacheLine = true;
                    }
                }

                return hasHot && hasColdInFirstCacheLine;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return;
        Document document = editor.getDocument();
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();

        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);
        for (HotRegionFinder.Region r : regions) {
            if (offset < r.bStart() || offset > r.bEnd()) continue;

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null || info.fields.size() < 2) return;

            Map<String, AccessPatternAnalyzer.FieldAnalysis> analysis = AccessPatternAnalyzer.analyze(info, block);

            List<CppStructParser.Field> hotFields = new ArrayList<>();
            List<CppStructParser.Field> coldFields = new ArrayList<>();

            for (CppStructParser.Field f : info.fields) {
                AccessPatternAnalyzer.FieldAnalysis fa = analysis.get(f.name);
                if (fa != null && fa.temperature == AccessPatternAnalyzer.Temperature.COLD) {
                    coldFields.add(f);
                } else {
                    hotFields.add(f);
                }
            }

            int cacheLineSize = HFTSettings.getInstance().getState().cacheLineSize;
            StringBuilder sb = new StringBuilder();

            // 1. Секция горячих данных (помещается в первый кэшлайн)
            sb.append("\n    // --- Hot Path Data (L1 Cache) ---\n");
            for (CppStructParser.Field hf : hotFields) {
                sb.append("    ").append(cleanDecl(hf.decl)).append(";\n");
            }

            // 2. Секция холодных данных (выравнивается на отдельную линию кэша)
            if (!coldFields.isEmpty()) {
                sb.append("\n    // --- Cold / Diagnostic Data (Separate Cache Line) ---\n");
                sb.append("    alignas(").append(cacheLineSize).append(") ");
                for (int i = 0; i < coldFields.size(); i++) {
                    if (i > 0) sb.append("    ");
                    sb.append(cleanDecl(coldFields.get(i).decl)).append(";\n");
                }
            }

            CppStructParser.Field first = info.fields.get(0);
            CppStructParser.Field last = info.fields.get(info.fields.size() - 1);

            int firstStartInDoc = r.bStart() + first.start;
            int lineStart = text.lastIndexOf('\n', firstStartInDoc);
            int replaceFrom = (lineStart != -1) ? lineStart + 1 : firstStartInDoc;

            int lastEndInDoc = r.bStart() + last.end;
            int lineEnd = text.indexOf('\n', lastEndInDoc);
            int replaceTo = (lineEnd != -1) ? lineEnd : Math.min(lastEndInDoc + 1, text.length());

            WriteCommandAction.runWriteCommandAction(project, () -> {
                document.replaceString(replaceFrom, replaceTo, sb.toString().trim());
            });
            return;
        }
    }

    private String cleanDecl(String decl) {
        if (decl == null) return "";
        return decl.replaceFirst("^alignas\\s*\\([^)]*\\)\\s*", "").trim();
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}