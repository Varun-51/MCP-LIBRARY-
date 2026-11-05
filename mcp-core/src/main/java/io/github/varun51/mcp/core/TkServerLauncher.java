package io.github.varun51.mcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public final class TkServerLauncher {

    private TkServerLauncher() {
    }

    public static McpSyncServer mtkStartStdio(String tkName, String tkVersion, List<TkTool> tkTools) {
        return mtkStartStdio(tkName, tkVersion, tkTools, System.in, System.out);
    }

    public static McpSyncServer mtkStartStdio(String tkName, String tkVersion, List<TkTool> tkTools, InputStream tkIn, OutputStream tkOut) {
        return McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper(), tkIn, tkOut))
                .serverInfo(tkName, tkVersion)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tkTools.stream().map(TkTool::mtkSpecification).toList())
                .build();
    }
}
