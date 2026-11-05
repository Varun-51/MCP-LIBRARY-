package io.github.varun51.mcp.tokenizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.varun51.mcp.core.TkToolException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TkVocabStoreTest {

    @TempDir
    Path tkDirectory;

    private final TkEncoding tkCl100k = TkEncoding.mtkNamed("cl100k_base");

    @Test
    void mtkRejectsUnknownEncodingsIncludingPathTraversal() {
        TkVocabStore tkStore = new TkVocabStore(tkDirectory);
        assertThrows(TkToolException.class, () -> tkStore.mtkTokenizer("gpt2"));
        assertThrows(TkToolException.class, () -> tkStore.mtkTokenizer("../../etc/passwd"));
    }

    @Test
    void mtkReportsMissingVocabulary() {
        TkToolException tkError = assertThrows(TkToolException.class, () -> new TkVocabStore(tkDirectory).mtkTokenizer("cl100k_base"));
        assertTrue(tkError.getMessage().contains("run the download command first"));
    }

    @Test
    void mtkRejectsTamperedVocabularyOnLoad() throws Exception {
        Files.writeString(tkDirectory.resolve(tkCl100k.mtkFileName()), "YQ== 0\n");
        TkToolException tkError = assertThrows(TkToolException.class, () -> new TkVocabStore(tkDirectory).mtkTokenizer("cl100k_base"));
        assertTrue(tkError.getMessage().startsWith("checksum mismatch for cl100k_base"));
    }

    @Test
    void mtkRejectsTamperedDownloadWithoutWritingFiles() throws Exception {
        try (TkServedFile tkServed = new TkServedFile(200, "YQ== 0\n".getBytes())) {
            assertThrows(TkToolException.class, () -> new TkVocabStore(tkDirectory).mtkDownload(tkCl100k, tkServed.mtkUri()));
        }
        try (var tkFiles = Files.list(tkDirectory)) {
            assertEquals(List.of(), tkFiles.toList());
        }
    }

    @Test
    void mtkRejectsFailedDownloads() throws Exception {
        try (TkServedFile tkServed = new TkServedFile(404, new byte[0])) {
            TkToolException tkError = assertThrows(TkToolException.class, () -> new TkVocabStore(tkDirectory).mtkDownload(tkCl100k, tkServed.mtkUri()));
            assertEquals("download of cl100k_base failed with HTTP 404", tkError.getMessage());
        }
    }

    @Test
    void mtkDownloadsVerifiedVocabulary() throws Exception {
        Path tkReal = TkVocabStore.mtkDefaultDirectory().resolve(tkCl100k.mtkFileName());
        assumeTrue(Files.isRegularFile(tkReal), "vocabulary not downloaded");
        try (TkServedFile tkServed = new TkServedFile(200, Files.readAllBytes(tkReal))) {
            Path tkWritten = new TkVocabStore(tkDirectory).mtkDownload(tkCl100k, tkServed.mtkUri());
            assertEquals(tkDirectory.resolve(tkCl100k.mtkFileName()), tkWritten);
        }
        assertEquals(List.of(15339, 1917), new TkVocabStore(tkDirectory).mtkTokenizer("cl100k_base").mtkEncode("hello world"));
        try (var tkFiles = Files.list(tkDirectory)) {
            assertFalse(tkFiles.anyMatch(tkPath -> tkPath.toString().endsWith(".part")));
        }
    }

    private static final class TkServedFile implements AutoCloseable {

        private final HttpServer tkServer;

        TkServedFile(int tkStatus, byte[] tkBody) throws Exception {
            tkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            tkServer.createContext("/", tkExchange -> {
                tkExchange.sendResponseHeaders(tkStatus, tkBody.length == 0 ? -1 : tkBody.length);
                tkExchange.getResponseBody().write(tkBody);
                tkExchange.close();
            });
            tkServer.start();
        }

        URI mtkUri() {
            return URI.create("http://127.0.0.1:" + tkServer.getAddress().getPort() + "/vocab.tiktoken");
        }

        @Override
        public void close() {
            tkServer.stop(0);
        }
    }
}
