package io.github.varun51.mcp.fit;

import io.github.varun51.mcp.core.TkToolException;
import io.github.varun51.mcp.inspector.TkModelFiles;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class TkHubClient {

    static final int _TKMAX_FILES = 10_000;
    static final int _TKMAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final Pattern _TKREPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Pattern _TKREVISION = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final int _TKMAX_REDIRECTS = 5;

    private final URI tkEndpoint;
    private final String tkToken;
    private final HttpClient tkClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();

    public TkHubClient(URI tkEndpoint, String tkToken) {
        this.tkEndpoint = tkEndpoint;
        this.tkToken = tkToken;
    }

    public static TkHubClient mtkFromEnvironment() {
        String tkEndpoint = Objects.requireNonNullElse(System.getenv("TK_HF_ENDPOINT"), "https://huggingface.co");
        return new TkHubClient(URI.create(tkEndpoint), System.getenv("HF_TOKEN"));
    }

    public static void mtkValidate(String tkRepo, String tkRevision) {
        TkToolException.mtkCheck(_TKREPO.matcher(tkRepo).matches() && !tkRepo.contains(".."), "model must be a Hugging Face repo id such as Qwen/Qwen2.5-7B-Instruct");
        TkToolException.mtkCheck(_TKREVISION.matcher(tkRevision).matches() && !tkRevision.contains(".."), "invalid revision: " + tkRevision);
    }

    public List<TkHubFile> mtkFiles(String tkRepo, String tkRevision) {
        Object tkListing = mtkJson("/api/models/" + tkRepo + "/tree/" + tkRevision + "?recursive=true")
                .orElseThrow(() -> new TkToolException("model not found on the Hub: " + tkRepo + "@" + tkRevision));
        TkToolException.mtkCheck(tkListing instanceof List<?> tkList && tkList.size() <= _TKMAX_FILES, "unexpected file listing for " + tkRepo);
        return ((List<?>) tkListing).stream()
                .map(tkEntry -> (Map<?, ?>) tkEntry)
                .filter(tkEntry -> "file".equals(tkEntry.get("type")))
                .map(tkEntry -> {
                    Object tkLfs = tkEntry.get("lfs");
                    Object tkSize = tkLfs instanceof Map<?, ?> tkLfsMap && tkLfsMap.get("size") != null ? tkLfsMap.get("size") : tkEntry.get("size");
                    return new TkHubFile((String) tkEntry.get("path"), ((Number) tkSize).longValue());
                })
                .toList();
    }

    public Optional<Map<String, Object>> mtkConfig(String tkRepo, String tkRevision) {
        return mtkJson(mtkResolve(tkRepo, tkRevision, "config.json")).map(tkConfig -> {
            TkToolException.mtkCheck(tkConfig instanceof Map<?, ?>, "config.json is not an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> tkMap = (Map<String, Object>) tkConfig;
            return tkMap;
        });
    }

    public Map<String, Object> mtkHeader(String tkRepo, String tkRevision, TkHubFile tkFile) {
        try {
            HttpResponse<InputStream> tkResponse = mtkGet(mtkResolve(tkRepo, tkRevision, tkFile.tkPath()));
            TkToolException.mtkCheck(tkResponse.statusCode() == 200 || tkResponse.statusCode() == 206, "cannot read " + tkFile.tkPath() + ": HTTP " + tkResponse.statusCode());
            try (InputStream tkBody = tkResponse.body()) {
                return TkModelFiles.mtkInspect(tkBody, tkFile.tkSize());
            }
        } catch (IOException tkError) {
            throw new UncheckedIOException("cannot read " + tkFile.tkPath(), tkError);
        }
    }

    private Optional<Object> mtkJson(String tkPath) {
        try {
            HttpResponse<InputStream> tkResponse = mtkGet(tkPath);
            try (InputStream tkBody = tkResponse.body()) {
                if (tkResponse.statusCode() == 404) {
                    return Optional.empty();
                }
                TkToolException.mtkCheck(tkResponse.statusCode() == 200, "Hugging Face Hub returned HTTP " + tkResponse.statusCode() + " for " + tkPath.split("\\?")[0]
                        + (tkResponse.statusCode() == 401 || tkResponse.statusCode() == 403 ? ", the model may be gated: set HF_TOKEN" : ""));
                byte[] tkBytes = tkBody.readNBytes(_TKMAX_JSON_BYTES + 1);
                TkToolException.mtkCheck(tkBytes.length <= _TKMAX_JSON_BYTES, "response too large for " + tkPath);
                return Optional.of(McpJsonDefaults.getMapper().readValue(tkBytes, new TypeRef<Object>() {
                }));
            }
        } catch (IOException tkError) {
            throw new UncheckedIOException("cannot reach the Hugging Face Hub", tkError);
        }
    }

    private HttpResponse<InputStream> mtkGet(String tkPath) throws IOException {
        URI tkUri = tkEndpoint.resolve(tkPath);
        try {
            for (int tkHop = 0; tkHop <= _TKMAX_REDIRECTS; tkHop++) {
                HttpRequest.Builder tkRequest = HttpRequest.newBuilder(tkUri).timeout(Duration.ofSeconds(60)).header("User-Agent", "mcp-will-it-fit").GET();
                if (tkToken != null && Objects.equals(tkUri.getAuthority(), tkEndpoint.getAuthority())) {
                    tkRequest.header("Authorization", "Bearer " + tkToken);
                }
                HttpResponse<InputStream> tkResponse = tkClient.send(tkRequest.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (tkResponse.statusCode() / 100 != 3) {
                    return tkResponse;
                }
                tkResponse.body().close();
                String tkLocation = tkResponse.headers().firstValue("Location").orElseThrow(() -> new IOException("redirect without location"));
                tkUri = tkUri.resolve(tkLocation);
                TkToolException.mtkCheck("https".equals(tkUri.getScheme()) || Objects.equals(tkUri.getScheme(), tkEndpoint.getScheme()), "refusing redirect to " + tkUri.getScheme());
            }
        } catch (InterruptedException tkInterrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", tkInterrupted);
        }
        throw new TkToolException("too many redirects for " + tkPath);
    }

    private static String mtkResolve(String tkRepo, String tkRevision, String tkFile) {
        String tkEncoded = Arrays.stream(tkFile.split("/"))
                .map(tkSegment -> URLEncoder.encode(tkSegment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return "/" + tkRepo + "/resolve/" + tkRevision + "/" + tkEncoded;
    }

    public record TkHubFile(String tkPath, long tkSize) {
    }
}
