package optimization;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import core.ProducerConsumerAnalyzer;
import dev.hotpath.CppStructParser;
import dev.hotpath.HotRegionFinder;
import org.jetbrains.annotations.NotNull;
import ui.HFTSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Alt+Enter action: Reorganizes struct fields by grouping Producer and Consumer fields
 * into isolated 64-byte cache line blocks.
 */
public class ReorganizeProducerConsumerIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    @NotNull
    @Override
    public String getText() {
        return "Reorganize to separate producer/consumer (cache line isolation)";
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
            if (offset >= r.mStart() && offset <= r.bEnd()) {
                String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                return info != null && info.fields.size() >= 2;
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
            if (offset < r.mStart() || offset > r.bEnd()) {
                continue;
            }

            String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
            CppStructParser.StructInfo info = CppStructParser.parse(block);
            if (info == null || info.fields.size() < 2) return;

            ProducerConsumerAnalyzer.StructProfile profile = ProducerConsumerAnalyzer.analyze(info, block);

            List<CppStructParser.Field> producers = new ArrayList<>();
            List<CppStructParser.Field> consumers = new ArrayList<>();
            List<CppStructParser.Field> others = new ArrayList<>();

            for (CppStructParser.Field f : info.fields) {
                ProducerConsumerAnalyzer.FieldProfile fp = profile.getFieldProfile(f.name);
                if (fp != null && fp.isProducer()) {
                    producers.add(f);
                } else if (fp != null && fp.isConsumer()) {
                    consumers.add(f);
                } else {
                    others.add(f);
                }
            }

            int cacheLine = HFTSettings.getInstance().getState().cacheLineSize;
            StringBuilder sb = new StringBuilder();

            if (!producers.isEmpty()) {
                sb.append("\n    // --- Producer / Writer Cache Line ---\n");
                sb.append("    alignas(").append(cacheLine).append(") ");
                for (int i = 0; i < producers.size(); i++) {
                    if (i > 0) sb.append("    ");
                    sb.append(cleanDecl(producers.get(i).decl)).append(";\n");
                }
            }

            if (!consumers.isEmpty()) {
                sb.append("\n    // --- Consumer / Reader Cache Line ---\n");
                sb.append("    alignas(").append(cacheLine).append(") ");
                for (int i = 0; i < consumers.size(); i++) {
                    if (i > 0) sb.append("    ");
                    sb.append(cleanDecl(consumers.get(i).decl)).append(";\n");
                }
            }

            if (!others.isEmpty()) {
                sb.append("\n    // --- General Data ---\n");
                for (CppStructParser.Field other : others) {
                    sb.append("    ").append(cleanDecl(other.decl)).append(";\n");
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