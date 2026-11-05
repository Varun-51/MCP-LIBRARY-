# MCP Library

Java 21 [Model Context Protocol](https://modelcontextprotocol.io) servers and connectors for AI/ML engineering, built on the official MCP Java SDK.

## Requirements

- Java 21
- Maven 3.9+

## Commands

| Purpose | Command |
|---|---|
| setup | `mvn -q dependency:go-offline` |
| test | `mvn test` |
| check | `mvn verify` |
| run a server | `mvn -q -pl <module> -am exec:java` |

## Modules

| Module | Purpose |
|---|---|
| `mcp-core` | Shared tool definition, typed argument access, error mapping and stdio server bootstrap |

## Using a server with an MCP client

Each server speaks MCP over stdio. Example client configuration:

```json
{
  "mcpServers": {
    "<module>": {
      "command": "mvn",
      "args": ["-q", "-f", "/path/to/MCP-LIBRARY-/pom.xml", "-pl", "<module>", "-am", "exec:java"]
    }
  }
}
```

## License

Apache 2.0
