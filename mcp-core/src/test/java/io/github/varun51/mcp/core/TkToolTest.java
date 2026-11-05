package io.github.varun51.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TkToolTest {

    private static final TkTool _TKECHO = new TkTool(
            "echo",
            "Echoes the text argument",
            TkSchema.mtkObject(Map.of("text", TkSchema.mtkField("string", "Text to echo")), "text"),
            tkArgs -> Map.of("echo", tkArgs.mtkString("text")));

    @Test
    void mtkReturnsStructuredAndTextContent() {
        CallToolResult tkResult = _TKECHO.mtkCall(Map.of("text", "hello"));
        assertFalse(tkResult.isError());
        assertEquals(Map.of("echo", "hello"), tkResult.structuredContent());
        assertEquals("{\"echo\":\"hello\"}", ((TextContent) tkResult.content().get(0)).text());
    }

    @Test
    void mtkBuildsResultsInInsertionOrder() {
        assertEquals(List.of("z", "a", "m"), List.copyOf(TkTool.mtkResult("z", 1, "a", 2, "m", 3).keySet()));
    }

    @Test
    void mtkMapsToolExceptionsToErrorResults() {
        CallToolResult tkResult = _TKECHO.mtkCall(Map.of());
        assertTrue(tkResult.isError());
        assertEquals("missing argument: text", ((TextContent) tkResult.content().get(0)).text());
    }

    @Test
    void mtkPropagatesUnexpectedFailures() {
        TkTool tkBroken = new TkTool("broken", "Fails", TkSchema.mtkObject(Map.of()), tkArgs -> {
            throw new IllegalStateException("bug");
        });
        assertThrows(IllegalStateException.class, () -> tkBroken.mtkCall(Map.of()));
    }

    @Test
    void mtkServesToolsOverStdioTransport() throws Exception {
        PipedOutputStream tkClientOut = new PipedOutputStream();
        PipedInputStream tkServerIn = new PipedInputStream(tkClientOut, 1 << 16);
        PipedOutputStream tkServerOut = new PipedOutputStream();
        BufferedReader tkClientIn = new BufferedReader(new InputStreamReader(new PipedInputStream(tkServerOut, 1 << 16), StandardCharsets.UTF_8));
        var tkServer = TkServerLauncher.mtkStartStdio("test", "1.0.0", List.of(_TKECHO), tkServerIn, tkServerOut);
        try {
            mtkSend(tkClientOut, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
            assertTrue(tkClientIn.readLine().contains("\"name\":\"test\""));
            mtkSend(tkClientOut, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            mtkSend(tkClientOut, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"ping\"}}}");
            String tkResponse = tkClientIn.readLine();
            assertTrue(tkResponse.contains("\"echo\":\"ping\""), tkResponse);
        } finally {
            tkServer.closeGracefully();
        }
    }

    private static void mtkSend(PipedOutputStream tkOut, String tkMessage) throws Exception {
        tkOut.write((tkMessage + "\n").getBytes(StandardCharsets.UTF_8));
        tkOut.flush();
    }
}
