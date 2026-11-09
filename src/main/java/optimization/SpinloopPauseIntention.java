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
 * Alt+Enter action: Inserts _mm_pause() instruction into spinloops.
 * Preserves all existing code inside the loop body to prevent code corruption.
 */
public class SpinloopPauseIntention extends PsiElementBaseIntentionAction implements DumbAware, IntentionAction {

    private int bodyStart = -1;
    private int bodyEnd = -1;

    @NotNull
    @Override
    public String getText() {
        return "Insert _mm_pause() into spinloop";
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
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();

        int lineNum = document.getLineNumber(offset);
        int startLine = Math.max(0, lineNum - 4);
        int endLine = Math.min(document.getLineCount() - 1, lineNum + 4);
        int searchStart = document.getLineStartOffset(startLine);
        int searchEnd = document.getLineEndOffset(endLine);
        String snippet = text.substring(searchStart, searchEnd);

        Pattern p = Pattern.compile("\\bwhile\\s*\\(");
        Matcher m = p.matcher(snippet);
        while (m.find()) {
            int whileIdxInSnippet = m.start();
            int openParen = m.end() - 1;
            int closeParen = findMatchingParen(snippet, openParen);
            if (closeParen == -1) continue;

            int bodyIdx = closeParen + 1;
            while (bodyIdx < snippet.length() && Character.isWhitespace(snippet.charAt(bodyIdx))) {
                bodyIdx++;
            }
            if (bodyIdx >= snippet.length()) continue;

            int bStart = searchStart + bodyIdx;
            int bEnd = -1;

            if (snippet.charAt(bodyIdx) == ';') {
                bEnd = bStart + 1;
            } else if (snippet.charAt(bodyIdx) == '{') {
                int closeBrace = findMatchingBrace(snippet, bodyIdx);
                if (closeBrace != -1) {
                    bEnd = searchStart + closeBrace + 1;
                }
            }

            if (bEnd != -1) {
                int loopStart = searchStart + whileIdxInSnippet;
                if (offset >= loopStart && offset <= bEnd + 2) {
                    String body = text.substring(bStart, bEnd);
                    if (body.contains("_mm_pause") || body.contains("pause")) {
                        return false;
                    }
                    this.bodyStart = bStart;
                    this.bodyEnd = bEnd;
                    return true;
                }
            }
        }
        return false;
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        if (editor == null || bodyStart == -1) return;

        Document document = editor.getDocument();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            String existingBody = document.getText().substring(bodyStart, bodyEnd);

            if (existingBody.equals(";")) {
                document.replaceString(bodyStart, bodyEnd, " { _mm_pause(); }");
            } else if (existingBody.trim().equals("{}")) {
                document.replaceString(bodyStart, bodyEnd, " { _mm_pause(); }");
            } else {
                int openBraceIdx = existingBody.indexOf('{');
                if (openBraceIdx != -1) {
                    int insertPos = bodyStart + openBraceIdx + 1;
                    int lineNum = document.getLineNumber(insertPos);
                    int lineStart = document.getLineStartOffset(lineNum);
                    String lineText = document.getText().substring(lineStart, insertPos);
                    int ws = 0;
                    while (ws < lineText.length() && Character.isWhitespace(lineText.charAt(ws))) ws++;
                    String indent = lineText.substring(0, ws);
                    String pauseInsert = "\n" + indent + "    _mm_pause();";
                    document.insertString(insertPos, pauseInsert);
                }
            }

            String currentText = document.getText();
            if (!currentText.contains("<immintrin.h>")) {
                int firstInclude = currentText.indexOf("#include");
                if (firstInclude != -1) {
                    document.insertString(firstInclude, "#include <immintrin.h>\n");
                } else {
                    document.insertString(0, "#include <immintrin.h>\n");
                }
            }
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}