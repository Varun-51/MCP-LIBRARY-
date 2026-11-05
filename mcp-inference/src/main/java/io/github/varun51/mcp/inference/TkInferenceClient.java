package io.github.varun51.mcp.inference;

import io.github.varun51.mcp.core.TkToolException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class TkInferenceClient {

    static final int _TKMAX_RETRIES = 3;
    static final int _TKMAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final Function<String, String> tkEnvironment;
    private final Duration tkBackoff;
    private final HttpClient tkHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public TkInferenceClient(Function<String, String> tkEnvironment, Duration tkBackoff) {
        this.tkEnvironment = tkEnvironment;
        this.tkBackoff = tkBackoff;
    }

    public Map<String, Object> mtkExchange(String tkPath, Object tkBody, int tkTimeoutSeconds) {
        HttpRequest tkRequest = mtkRequest(tkPath, tkBody);
        long tkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(tkTimeoutSeconds);
        for (int tkAttempt = 1; ; tkAttempt++) {
            HttpResponse<byte[]> tkResponse = mtkAwait(tkRequest, tkDeadline, tkTimeoutSeconds);
            int tkStatus = tkResponse.statusCode();
            if (tkStatus / 100 == 2) {
                return mtkParse(tkResponse.body());
            }
            boolean tkRetryable = tkStatus == 429 || tkStatus / 100 == 5;
            Duration tkDelay = tkBackoff.multipliedBy(1L << (tkAttempt - 1));
            TkToolException.mtkCheck(tkRetryable && tkAttempt <= _TKMAX_RETRIES && tkDelay.toNanos() < tkDeadline - System.nanoTime(),
                    "inference server returned HTTP " + tkStatus + (tkRetryable ? " (attempts: " + tkAttempt + ")" : ""));
            try {
                Thread.sleep(tkDelay);
            } catch (InterruptedException tkInterrupted) {
                Thread.currentThread().interrupt();
                throw new TkToolException("request interrupted", tkInterrupted);
            }
        }
    }

    private HttpRequest mtkRequest(String tkPath, Object tkBody) {
        String tkBase = tkEnvironment.apply("TK_INFERENCE_URL");
        TkToolException.mtkCheck(tkBase != null && !tkBase.isBlank(), "TK_INFERENCE_URL is not set");
        URI tkUri;
        try {
            tkUri = new URI(tkBase.strip().replaceAll("/+$", "") + tkPath);
        } catch (URISyntaxException tkError) {
            throw new TkToolException("TK_INFERENCE_URL must be an http or https URL", tkError);
        }
        TkToolException.mtkCheck(("http".equals(tkUri.getScheme()) || "https".equals(tkUri.getScheme())) && tkUri.getHost() != null,
                "TK_INFERENCE_URL must be an http or https URL");
        HttpRequest.Builder tkBuilder = HttpRequest.newBuilder(tkUri).header("Accept", "application/json");
        if (tkBody == null) {
            tkBuilder.GET();
        } else {
            tkBuilder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(mtkJson(tkBody)));
        }
        String tkKey = tkEnvironment.apply("TK_INFERENCE_API_KEY");
        if (tkKey != null && !tkKey.isBlank()) {
            TkToolException.mtkCheck(tkKey.strip().chars().allMatch(tkChar -> tkChar > 32 && tkChar < 127), "TK_INFERENCE_API_KEY contains invalid characters");
            tkBuilder.header("Authorization", "Bearer " + tkKey.strip());
        }
        return tkBuilder.build();
    }

    private HttpResponse<byte[]> mtkAwait(HttpRequest tkRequest, long tkDeadline, int tkTimeoutSeconds) {
        String tkCancelled = "request cancelled after " + tkTimeoutSeconds + " seconds";
        long tkRemaining = tkDeadline - System.nanoTime();
        TkToolException.mtkCheck(tkRemaining > 0, tkCancelled);
        CompletableFuture<HttpResponse<byte[]>> tkFuture = tkHttp.sendAsync(tkRequest, TkInferenceClient::mtkCapped);
        try {
            return tkFuture.get(tkRemaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException tkTimeout) {
            tkFuture.cancel(true);
            throw new TkToolException(tkCancelled, tkTimeout);
        } catch (InterruptedException tkInterrupted) {
            tkFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new TkToolException("request interrupted", tkInterrupted);
        } catch (ExecutionException tkFailure) {
            if (tkFailure.getCause() instanceof TkToolException tkToolError) {
                throw tkToolError;
            }
            throw new TkToolException("cannot reach the inference server: " + tkFailure.getCause().getClass().getSimpleName(), tkFailure.getCause());
        }
    }

    private static HttpResponse.BodySubscriber<byte[]> mtkCapped(HttpResponse.ResponseInfo tkInfo) {
        HttpResponse.BodySubscriber<byte[]> tkDelegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private long tkReceived;
            private Flow.Subscription tkSubscription;

            @Override
            public CompletionStage<byte[]> getBody() {
                return tkDelegate.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription tkUpstream) {
                tkSubscription = tkUpstream;
                tkDelegate.onSubscribe(tkUpstream);
            }

            @Override
            public void onNext(List<ByteBuffer> tkBuffers) {
                if (tkReceived > _TKMAX_RESPONSE_BYTES) {
                    return;
                }
                tkReceived += tkBuffers.stream().mapToLong(ByteBuffer::remaining).sum();
                if (tkReceived > _TKMAX_RESPONSE_BYTES) {
                    tkSubscription.cancel();
                    tkDelegate.onError(new TkToolException("inference response exceeds " + _TKMAX_RESPONSE_BYTES + " bytes"));
                } else {
                    tkDelegate.onNext(tkBuffers);
                }
            }

            @Override
            public void onError(Throwable tkError) {
                tkDelegate.onError(tkError);
            }

            @Override
            public void onComplete() {
                tkDelegate.onComplete();
            }
        };
    }

    private static Map<String, Object> mtkParse(byte[] tkBody) {
        try {
            Map<String, Object> tkParsed = McpJsonDefaults.getMapper().readValue(tkBody, new TypeRef<Map<String, Object>>() {
            });
            TkToolException.mtkCheck(tkParsed != null, "inference server returned invalid JSON");
            return tkParsed;
        } catch (IOException tkError) {
            throw new TkToolException("inference server returned invalid JSON", tkError);
        }
    }

    private static byte[] mtkJson(Object tkValue) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsBytes(tkValue);
        } catch (IOException tkError) {
            throw new UncheckedIOException(tkError);
        }
    }
}
