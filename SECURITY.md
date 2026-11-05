# Security Policy

## Supported versions

Security fixes are applied to the latest release on `main`.

## Reporting a vulnerability

Do not open a public issue. Report vulnerabilities privately through [GitHub Security Advisories](https://github.com/Varun-51/MCP-LIBRARY-/security/advisories/new).

Please include:

- affected module and version or commit
- a description of the issue and its impact
- reproduction steps or a proof of concept

You can expect an acknowledgement within 5 business days. Fixes are disclosed through a security advisory once a patched release is available.

## Scope

In scope:

- MCP servers and connectors in this repository
- argument handling, file and network access, credential handling
- integrity checks on downloaded resources

Out of scope:

- vulnerabilities in the MCP client or model provider you connect these servers to
- issues that need an already compromised host
