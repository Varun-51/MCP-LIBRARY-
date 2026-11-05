package io.github.varun51.mcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Function;

public record TkTool(String tkName, String tkDescription, Map<String, Object> tkInputSchema, Function<TkArgs, Object> tkHandler) {

    public CallToolResult mtkCall(Map<String, Object> tkArguments) {
        try {
            Object tkResult = tkHandler.apply(new TkArgs(tkArguments));
            return CallToolResult.builder().structuredContent(tkResult).addTextContent(mtkJson(tkResult)).isError(false).build();
        } catch (TkToolException tkError) {
            return CallToolResult.builder().addTextContent(tkError.getMessage()).isError(true).build();
        }
    }

    public SyncToolSpecification mtkSpecification() {
        Tool tkTool = Tool.builder().name(tkName).description(tkDescription).inputSchema(tkInputSchema).build();
        return SyncToolSpecification.builder().tool(tkTool).callHandler((tkExchange, tkRequest) -> mtkCall(tkRequest.arguments())).build();
    }

    private static String mtkJson(Object tkValue) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(tkValue);
        } catch (IOException tkError) {
            throw new UncheckedIOException(tkError);
        }
    }
}
