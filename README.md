# MCP Library

[![CI](https://github.com/Varun-51/MCP-LIBRARY-/actions/workflows/ci.yml/badge.svg)](https://github.com/Varun-51/MCP-LIBRARY-/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)

Java [Model Context Protocol](https://modelcontextprotocol.io) servers and connectors for AI/ML engineering, built on the official MCP Java SDK. Each server is a small, self-contained jar that any MCP client (Claude Desktop, Claude Code, IDE agents) can launch over stdio.

## Flagship: will it fit?

Ask your assistant whether any Hugging Face model fits your GPU. `mcp-will-it-fit` reads the real safetensors and GGUF headers over HTTP, streams a few kilobytes instead of downloading the weights, and computes weights + KV cache for every precision and quantization in the repo.

> **You:** Can I run Qwen/Qwen2.5-7B-Instruct on my RTX 4090 at 32k context?
>
> **`will_it_fit`:** Best fit: safetensors BF16 uses 16.94 of 24.0 GiB at 32768 tokens (14.19 GiB weights + 1.75 GiB KV cache + 1 GiB overhead).

> **You:** And the GGUF builds on an 8 GB card at 16k context?
>
> **`will_it_fit`:** Best fit: Q6_K uses 7.7 of 8.0 GiB. Q4_K_M fits with up to 49,405 tokens of context; FP16 and Q8_0 do not fit.

These are real outputs from the live Hugging Face Hub. The run streamed only the tensor headers, not gigabytes of weights.

- Exact parameter counts and dtypes from tensor headers, not guesses from the model name
- GQA-aware KV cache from `config.json`, or from GGUF metadata when there is no config
- Every GGUF quantization in a repo compared side by side, including split files
- GPU memory auto-detected through `nvidia-smi` or Apple unified memory
- `HF_TOKEN` supported for gated models and never forwarded to CDN redirects

```json
{
  "mcpServers": {
    "will-it-fit": {
      "command": "java",
      "args": ["-jar", "/path/to/MCP-LIBRARY-/mcp-will-it-fit/target/mcp-will-it-fit-0.1.0-SNAPSHOT-all.jar"]
    }
  }
}
```

## Servers

| Module | Tools | Notes |
|---|---|---|
| `mcp-tokenizer` | `count_tokens`, `encode`, `decode`, `truncate_to_budget` | Byte-level BPE matching tiktoken `cl100k_base` and `o200k_base` exactly. Vocabularies are SHA-256 verified. |
| `mcp-eval` | `score`, `pass_at_k`, `bootstrap_ci` | Exact match, SQuAD token F1, ROUGE-L, WER, CER, unbiased pass@k, seeded bootstrap intervals. Verified against SQuAD v2, rouge-score, jiwer and the Codex estimator. |
| `mcp-vector-search` | `create_collection`, `upsert`, `search`, `delete`, `drop_collection`, `list_collections`, `save_collection`, `load_collection` | Exact top-k over cosine, dot or L2 with metadata filters and JSON snapshots confined to `TK_VECTOR_DIR`. |
| `mcp-guardrails` | `detect_pii`, `detect_secrets`, `redact`, `check_injection`, `validate_output` | Checksum-verified PII (Luhn, IBAN mod-97), secret detection with masked output, prompt-injection heuristics, JSON Schema validation of model output. |
| `mcp-model-inspector` | `inspect_model_file`, `estimate_inference_memory`, `estimate_training_memory` | Reads safetensors and GGUF headers without loading weights; KV-cache aware inference sizing and ZeRO-stage training sizing. File access confined to `TK_MODEL_ROOTS`. |
| `mcp-will-it-fit` | `will_it_fit`, `detect_gpu` | Fits any Hugging Face model to your GPU from remote tensor headers: per-variant weights, KV cache, max context. |
| `mcp-inference` | `complete`, `list_models` | OpenAI-compatible chat gateway (vLLM, Ollama, llama.cpp, OpenAI) with pre-flight token budget checks, retries and cancellation on timeout. |
| `mcp-quantization` | `analyze_quantization` | FP16, BF16, FP8 E4M3/E5M2 and INT8 round-trip error analysis with per-tensor or per-block scaling, verified against ml_dtypes. |

Shared infrastructure lives in `mcp-core`.

## Quick start

Requirements: Java 21+ and Maven 3.9+.

```bash
mvn -q package -DskipTests
java -jar mcp-tokenizer/target/mcp-tokenizer-*-all.jar download
```

`download` stores the vocabularies in `~/.mcp-library/tokenizers`. Set `TK_TOKENIZER_DIR` to use a different directory.

## Connecting to an MCP client

```json
{
  "mcpServers": {
    "tokenizer": {
      "command": "java",
      "args": ["-jar", "/path/to/MCP-LIBRARY-/mcp-tokenizer/target/mcp-tokenizer-0.1.0-SNAPSHOT-all.jar"]
    }
  }
}
```

## Commands

| Purpose | Command |
|---|---|
| setup | `mvn -q dependency:go-offline` |
| build | `mvn -q package -DskipTests` |
| download | `java -jar mcp-tokenizer/target/mcp-tokenizer-*-all.jar download` |
| test | `mvn test` |
| check | `mvn verify` |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE)
