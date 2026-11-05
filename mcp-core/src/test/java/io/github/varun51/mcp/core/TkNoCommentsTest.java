package io.github.varun51.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TkNoCommentsTest {

    @Test
    void mtkRepositoryContainsNoComments() throws IOException {
        Path tkRoot = Path.of("").toAbsolutePath().getParent();
        try (Stream<Path> tkFiles = Files.walk(tkRoot)) {
            List<String> tkOffenders = tkFiles
                    .filter(tkPath -> !tkPath.toString().contains("/target/") && !tkPath.toString().contains("/.git/"))
                    .filter(tkPath -> tkPath.toString().matches(".*\\.(java|ya?ml)$") || tkPath.getFileName().toString().equals("pom.xml"))
                    .filter(TkNoCommentsTest::mtkHasComment)
                    .map(tkRoot::relativize)
                    .map(Path::toString)
                    .toList();
            assertEquals(List.of(), tkOffenders);
        }
    }

    @Test
    void mtkDetectsCommentsOutsideLiterals() {
        assertTrue(mtkHasJavaComment("int a; " + "/" + "/ note"));
        assertTrue(mtkHasJavaComment("int a; " + "/" + "* note *" + "/"));
        assertFalse(mtkHasJavaComment("String u = \"https:" + "/" + "/x\";"));
        assertFalse(mtkHasJavaComment("char c = '/'; String s = \"\\\"" + "/" + "/\";"));
        assertFalse(mtkHasJavaComment("String t = \"\"\"\n" + "/" + "/ inside\n\"\"\";"));
    }

    private static boolean mtkHasComment(Path tkPath) {
        try {
            String tkSource = Files.readString(tkPath);
            String tkFile = tkPath.toString();
            if (tkFile.endsWith(".java")) {
                return mtkHasJavaComment(tkSource);
            }
            return tkFile.endsWith(".xml") ? tkSource.contains("<" + "!--") : tkSource.lines().anyMatch(tkLine -> tkLine.strip().startsWith("#"));
        } catch (IOException tkError) {
            throw new IllegalStateException(tkPath.toString(), tkError);
        }
    }

    static boolean mtkHasJavaComment(String tkSource) {
        int tkIndex = 0;
        while (tkIndex < tkSource.length()) {
            char tkChar = tkSource.charAt(tkIndex);
            if (tkSource.startsWith("\"\"\"", tkIndex)) {
                tkIndex = tkSource.indexOf("\"\"\"", tkIndex + 3) + 3;
            } else if (tkChar == '"' || tkChar == '\'') {
                tkIndex = mtkSkipLiteral(tkSource, tkIndex, tkChar);
            } else if (tkChar == '/' && tkIndex + 1 < tkSource.length() && "/*".indexOf(tkSource.charAt(tkIndex + 1)) >= 0) {
                return true;
            } else {
                tkIndex++;
            }
        }
        return false;
    }

    private static int mtkSkipLiteral(String tkSource, int tkStart, char tkQuote) {
        int tkIndex = tkStart + 1;
        while (tkSource.charAt(tkIndex) != tkQuote) {
            tkIndex += tkSource.charAt(tkIndex) == '\\' ? 2 : 1;
        }
        return tkIndex + 1;
    }
}
