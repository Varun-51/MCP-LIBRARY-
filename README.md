# MCP Library

[![CI](https://github.com/Varun-51/MCP-LIBRARY-/actions/workflows/ci.yml/badge.svg)](https://github.com/Varun-51/MCP-LIBRARY-/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)

Java [Model Context Protocol](https://modelcontextprotocol.io) servers and connectors for AI/ML engineering, built on the official MCP Java SDK. Each server is a small, self-contained jar that any MCP client (Claude Desktop, Claude Code, IDE agents) can launch over stdio.

## Servers

| Module | Tools | Notes |
|---|---|---|
| `mcp-tokenizer` | `count_tokens`, `encode`, `decode`, `truncate_to_budget` | Byte-level BPE matching tiktoken `cl100k_base` and `o200k_base` exactly. Vocabularies are SHA-256 verified. |
| `mcp-eval` | `score`, `pass_at_k`, `bootstrap_ci` | Exact match, SQuAD token F1, ROUGE-L, WER, CER, unbiased pass@k, seeded bootstrap intervals. Verified against SQuAD v2, rouge-score, jiwer and the Codex estimator. |

Shared infrastructure lives in `mcp-core`.

## Quick start

Requirements: Java 21+ and Maven 3.9+.

```bash
mvn -q package -DskipTests
java -jar mcp-tokenizer/target/mcp-tokenizer-*.jar download
```

`download` stores the vocabularies in `~/.mcp-library/tokenizers`. Set `TK_TOKENIZER_DIR` to use a different directory.

## Connecting to an MCP client

```json
{
  "mcpServers": {
    "tokenizer": {
      "command": "java",
      "args": ["-jar", "/path/to/MCP-LIBRARY-/mcp-tokenizer/target/mcp-tokenizer-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

## Commands

| Purpose | Command |
|---|---|
| setup | `mvn -q dependency:go-offline` |
| build | `mvn -q package -DskipTests` |
| download | `java -jar mcp-tokenizer/target/mcp-tokenizer-*.jar download` |
| test | `mvn test` |
| check | `mvn verify` |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE)
