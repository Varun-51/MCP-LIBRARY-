package io.github.varun51.mcp.tokenizer;

import io.github.varun51.mcp.core.TkToolException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TkVocabStore {

    private final Path tkDirectory;
    private final Map<String, TkBpeTokenizer> tkLoaded = new ConcurrentHashMap<>();

    public TkVocabStore(Path tkDirectory) {
        this.tkDirectory = tkDirectory;
    }

    public static Path mtkDefaultDirectory() {
        String tkConfigured = System.getenv("TK_TOKENIZER_DIR");
        return tkConfigured != null ? Path.of(tkConfigured) : Path.of(System.getProperty("user.home"), ".mcp-library", "tokenizers");
    }

    public TkBpeTokenizer mtkTokenizer(String tkName) {
        TkEncoding tkEncoding = TkEncoding.mtkNamed(tkName);
        return tkLoaded.computeIfAbsent(tkEncoding.tkName(), tkKey -> mtkLoad(tkEncoding));
    }

    public Path mtkDownload(TkEncoding tkEncoding, URI tkSource) throws IOException, InterruptedException {
        HttpResponse<byte[]> tkResponse;
        try (HttpClient tkClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            tkResponse = tkClient.send(HttpRequest.newBuilder(tkSource).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        TkToolException.mtkCheck(tkResponse.statusCode() == 200, "download of " + tkEncoding.tkName() + " failed with HTTP " + tkResponse.statusCode());
        mtkVerify(tkEncoding, tkResponse.body());
        Files.createDirectories(tkDirectory);
        Path tkTemp = Files.createTempFile(tkDirectory, tkEncoding.tkName(), ".part");
        Files.write(tkTemp, tkResponse.body());
        return Files.move(tkTemp, tkDirectory.resolve(tkEncoding.mtkFileName()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private TkBpeTokenizer mtkLoad(TkEncoding tkEncoding) {
        Path tkFile = tkDirectory.resolve(tkEncoding.mtkFileName());
        TkToolException.mtkCheck(Files.isRegularFile(tkFile), "vocabulary " + tkEncoding.tkName() + " not found in " + tkDirectory + ", run the download command first");
        try {
            byte[] tkBytes = Files.readAllBytes(tkFile);
            mtkVerify(tkEncoding, tkBytes);
            return TkBpeTokenizer.mtkParse(tkBytes, tkEncoding.tkPattern());
        } catch (IOException tkError) {
            throw new UncheckedIOException("cannot read " + tkFile, tkError);
        }
    }

    private static void mtkVerify(TkEncoding tkEncoding, byte[] tkBytes) {
        try {
            String tkActual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tkBytes));
            TkToolException.mtkCheck(tkActual.equals(tkEncoding.tkSha256()), "checksum mismatch for " + tkEncoding.tkName() + ": expected " + tkEncoding.tkSha256() + " but was " + tkActual);
        } catch (NoSuchAlgorithmException tkError) {
            throw new IllegalStateException(tkError);
        }
    }
}
