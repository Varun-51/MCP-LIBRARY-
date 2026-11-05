# Contributing

Thanks for helping improve MCP Library. This guide covers how to build the project, the engineering rules every change follows, and how to get a pull request merged.

## Development setup

Requirements: Java 21+ and Maven 3.9+.

```bash
mvn -q dependency:go-offline
mvn -q package -DskipTests
java -jar mcp-tokenizer/target/mcp-tokenizer-*-all.jar download
mvn verify
```

The download step fetches the tokenizer vocabularies and verifies their SHA-256 checksums. Without it, the tiktoken conformance tests are skipped locally. CI always runs them.

## Project layout

| Path | Purpose |
|---|---|
| `mcp-core` | Shared `TkTool`, `TkArgs`, `TkSchema` and `TkServerLauncher`. Every server builds on these. |
| `mcp-<name>` | One MCP server per module, packaged as a runnable jar. |
| `.github/workflows/ci.yml` | Build, test on Java 21 and 25, and stdio smoke test of every server jar. |

## Engineering rules

These are enforced in review, and where possible by tests.

1. **No comments anywhere.** No inline comments, block comments, Javadoc, TODOs or commented-out code in Java, POMs or workflows. Code explains itself through naming and structure. `TkNoCommentsTest` fails the build otherwise.
2. **Naming conventions.**

   | Element | Convention | Example |
   |---|---|---|
   | Classes | `TkPascalCase` | `TkVocabStore` |
   | Methods | `mtk` prefix | `mtkEncode()` |
   | Variables and parameters | `tk` prefix | `tkTokens` |
   | Constants | `_TKUPPER_SNAKE` | `_TKMAX_TEXT_CHARS` |

   MCP tool and argument names stay `snake_case`. They are protocol strings, not Java identifiers.
3. **Smallest complete change.** Reuse `mcp-core` before adding helpers. No speculative abstractions, no wrappers that add nothing.
4. **Dependencies.** Prefer the JDK. A new dependency needs a justification in the pull request.
5. **Errors.** Expected failures throw `TkToolException` with a clear message, which becomes an MCP tool error. Never swallow exceptions.

## Tests

Every change ships with tests:

- golden path
- edge cases
- negative paths (invalid arguments, malformed input)
- security cases (size caps, path traversal, integrity checks, secrets never echoed)
- regression tests for bug fixes

Tests must be deterministic and must not depend on network services. Mock HTTP endpoints with the JDK `HttpServer`.

## Adding a server

1. Create `mcp-<name>` with a `Tk<Name>Server` main class that calls `TkServerLauncher.mtkStartStdio`.
2. Define tools as `TkTool` instances with a `TkSchema` input schema and capped inputs.
3. Set `exec.mainClass` and declare `maven-shade-plugin` in the module POM.
4. Register the module in the root POM and add it to the README.
5. Cover the tools through `TkTool.mtkCall` in tests.

## Security checklist for new tools

- [ ] Input sizes and counts are capped.
- [ ] File access is confined to configured roots, with symlink and `..` escapes rejected.
- [ ] Network access validates destinations, and downloads verify integrity.
- [ ] Credentials come only from environment variables and are never logged or returned.
- [ ] Connectors are read-only unless writes are explicitly enabled.

## Pull requests

- Keep each PR focused on one change.
- Use clear commit messages that describe what changed.
- Fill in the pull request template.
- CI must be green before review.
