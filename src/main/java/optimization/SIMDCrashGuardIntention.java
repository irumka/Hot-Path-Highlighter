package optimization;

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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alt+Enter action: Fixes SIMD unaligned memory hazard by inserting
 * alignas(16/32/64) strictly AFTER the 'struct' or 'class' keyword:
 * 'struct alignas(32) Packet' (standard C++11 syntax).
 */
public class SIMDCrashGuardIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private int requiredAlignment = 0;
    private String vectorTypeFound = null;

    @NotNull
    @Override
    public String getText() {
        return "Fix SIMD SIGSEGV hazard: add alignas(" + requiredAlignment + ") for " + vectorTypeFound;
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
                int openBrace = text.indexOf('{', r.bStart());
                if (openBrace == -1) openBrace = Math.min(r.bStart() + 120, text.length());

                String header = text.substring(r.mStart(), openBrace);
                String block = text.substring(r.bStart(), Math.min(r.bEnd(), text.length()));
                CppStructParser.StructInfo info = CppStructParser.parse(block);
                if (info == null || info.fields.isEmpty()) return false;

                int maxSimdAlign = 0;
                String matchedType = null;

                for (CppStructParser.Field f : info.fields) {
                    String t = f.type.toLowerCase();
                    if (t.contains("__m512")) {
                        if (maxSimdAlign < 64) {
                            maxSimdAlign = 64;
                            matchedType = "__m512 (AVX-512)";
                        }
                    } else if (t.contains("__m256")) {
                        if (maxSimdAlign < 32) {
                            maxSimdAlign = 32;
                            matchedType = "__m256 (AVX2)";
                        }
                    } else if (t.contains("__m128")) {
                        if (maxSimdAlign < 16) {
                            maxSimdAlign = 16;
                            matchedType = "__m128 (SSE)";
                        }
                    }
                }

                if (maxSimdAlign > 0) {
                    boolean alreadyAligned = header.contains("alignas(" + maxSimdAlign + ")") ||
                            (maxSimdAlign <= 32 && header.contains("alignas(64)"));
                    if (!alreadyAligned) {
                        this.requiredAlignment = maxSimdAlign;
                        this.vectorTypeFound = matchedType;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || requiredAlignment == 0) return;

        Document document = editor.getDocument();
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();

        ArrayList<HotRegionFinder.Region> regions = HotRegionFinder.findAllStructs(text);
        for (HotRegionFinder.Region r : regions) {
            if (offset < r.mStart() || offset > r.bEnd()) continue;

            int openBrace = text.indexOf('{', r.bStart());
            if (openBrace == -1) openBrace = Math.min(r.bStart() + 120, text.length());

            String headerFull = text.substring(r.mStart(), openBrace);

            // 1. Если был меньший alignas — заменяем его точно на месте
            Pattern existingAlign = Pattern.compile("alignas\\s*\\([^)]*\\)");
            Matcher alignMatcher = existingAlign.matcher(headerFull);
            if (alignMatcher.find()) {
                int replaceFrom = r.mStart() + alignMatcher.start();
                int replaceTo = r.mStart() + alignMatcher.end();
                String newAlign = "alignas(" + requiredAlignment + ")";

                WriteCommandAction.runWriteCommandAction(project, () -> {
                    document.replaceString(replaceFrom, replaceTo, newAlign);
                });
                return;
            }

            // 2. Иначе вставляем строго ПОСЛЕ ключевого слова 'struct' или 'class'
            String headerFromStruct = text.substring(r.bStart(), openBrace);
            Pattern p = Pattern.compile("\\b(struct|class)\\b");
            Matcher m = p.matcher(headerFromStruct);
            if (m.find()) {
                int insertPos = r.bStart() + m.end();
                String alignDirective = " alignas(" + requiredAlignment + ")";

                WriteCommandAction.runWriteCommandAction(project, () -> {
                    document.insertString(insertPos, alignDirective);
                });
                return;
            }
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}