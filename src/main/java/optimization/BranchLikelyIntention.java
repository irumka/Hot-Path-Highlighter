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
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alt+Enter action: Inserts C++20 [[unlikely]] branch predictor attribute into cold/error checks
 * on hot paths to prevent CPU branch target buffer (BTB) pipeline stalls.
 */
public class BranchLikelyIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private int insertOffset = -1;
    private String conditionSnippet = null;

    private static final Pattern IF_PATTERN = Pattern.compile(
            "\\bif\\s*\\(([^)]*(?:err|error|fail|invalid|overflow|unlikely|timeout)[^)]*)\\)\\s*\\{"
    );

    @NotNull
    @Override
    public String getText() {
        return "Add [[unlikely]] branch prediction hint to error check";
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

        int startLine = Math.max(0, lineNum - 2);
        int endLine = Math.min(document.getLineCount() - 1, lineNum + 2);
        int searchStart = document.getLineStartOffset(startLine);
        int searchEnd = document.getLineEndOffset(endLine);
        String snippet = document.getText().substring(searchStart, searchEnd);
        String clean = CppStructParser.clearCommentsAndStrings(snippet);

        Matcher m = IF_PATTERN.matcher(clean);
        while (m.find()) {
            int openBracePos = m.end() - 1;
            int absOpenBrace = searchStart + openBracePos;

            String cond = m.group(1);
            if (cond.contains("unlikely") || cond.contains("[[")) continue;

            if (offset >= searchStart + m.start() && offset <= absOpenBrace + 5) {
                this.insertOffset = absOpenBrace;
                this.conditionSnippet = cond.trim();
                return true;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || insertOffset == -1) return;

        Document document = editor.getDocument();
        // В C++20: if (cond) [[unlikely]] { ... }
        String attribute = "[[unlikely]] ";

        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.insertString(insertOffset, attribute);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}