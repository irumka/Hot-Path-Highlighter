package optimization;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alt+Enter action: Replaces slow integer modulo '%' with bitwise '& (N - 1)'.
 * Validates power-of-two constraints to prevent silent code corruption.
 */
public class BitwiseMaskIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private static final Pattern MODULO_PATTERN = Pattern.compile(
            "(\\([a-zA-Z0-9_\\s+\\-*/%]+\\)|[a-zA-Z0-9_]+)\\s*%\\s*([a-zA-Z0-9_]+)"
    );

    private String targetDivisor = null;
    private int matchStart = -1;
    private int matchEnd = -1;
    private String leftExpr = null;

    private static boolean isPowerOfTwo(long n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    @NotNull
    @Override
    public String getText() {
        if (targetDivisor != null) {
            return "Replace '%' with bitwise '& (" + targetDivisor + " - 1)' (Fast power-of-two indexing)";
        }
        return "Replace '%' with bitwise '& (N - 1)'";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "HFT Optimizations";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null) return false;

        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int lineNum = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(lineNum);
        int lineEnd = document.getLineEndOffset(lineNum);
        String line = document.getText().substring(lineStart, lineEnd);

        if (!line.contains("%")) {
            return false;
        }

        Matcher m = MODULO_PATTERN.matcher(line);
        while (m.find()) {
            int absStart = lineStart + m.start();
            int absEnd = lineStart + m.end();
            if (offset >= absStart && offset <= absEnd + 5) {
                String candidateDivisor = m.group(2).trim();

                // Защита от порчи семантики: проверяем целочисленные литералы
                try {
                    long val = Long.decode(candidateDivisor);
                    if (!isPowerOfTwo(val)) {
                        return false; // Запрещаем для 100, 10, 60 и любых чисел != 2^N
                    }
                } catch (NumberFormatException ignored) {
                    // Символическая константа (например BUFFER_SIZE) допустима
                }

                this.leftExpr = m.group(1).trim();
                this.targetDivisor = candidateDivisor;
                this.matchStart = absStart;
                this.matchEnd = absEnd;
                return true;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || targetDivisor == null || matchStart == -1) return;

        Document document = editor.getDocument();
        StringBuilder replacement = new StringBuilder();

        if (leftExpr.startsWith("(") && leftExpr.endsWith(")")) {
            replacement.append(leftExpr);
        } else if (leftExpr.contains("+") || leftExpr.contains("-") || leftExpr.contains("*") || leftExpr.contains("/")) {
            replacement.append("(").append(leftExpr).append(")");
        } else {
            replacement.append(leftExpr);
        }

        replacement.append(" & (").append(targetDivisor).append(" - 1)");

        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.replaceString(matchStart, matchEnd, replacement.toString());
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}