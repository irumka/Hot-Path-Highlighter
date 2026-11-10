package optimization;

import com.intellij.ide.BrowserUtil;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Generates an instant Compiler Explorer (Godbolt) URL with side-by-side assembly view:
 * Compiles with '-O3 -std=c++20 -march=native' so developers can verify assembly differences.
 */
public class GodboltExporter {

    public static void openInGodbolt(@NotNull String cppCode, @NotNull String title) {
        try {
            String preamble = "// --- HFT Memory Layout ASM Inspection: " + title + " ---\n"
                    + "#include <cstdint>\n"
                    + "#include <atomic>\n"
                    + "#include <immintrin.h>\n\n";

            String fullCode = preamble + cppCode;
            String encodedCode = URLEncoder.encode(fullCode, StandardCharsets.UTF_8);

            String godboltUrl = "https://godbolt.org/?#g:!((g:!((h:compiler_1,type:editor,content:'"
                    + encodedCode
                    + "'),(h:compiler_1,type:compiler,compiler:g142,options:'-O3%20-std=c%2B%2B20%20-march=native')))";

            BrowserUtil.browse(godboltUrl);
        } catch (Exception ignored) {
            BrowserUtil.browse("https://godbolt.org/");
        }
    }
}