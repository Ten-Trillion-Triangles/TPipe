# Install

## Kotlin PCP scripting backend

No PCP request or application API migration is required. The Kotlin scripting
backend moved from JSR-223 to TPipe's internal K2 host while preserving the
existing executor, request, context, binding, result, and serialization APIs.
The `pcp-kotlin-v1` dialect is one-shot and host-classpath-only. Compiler
diagnostic wording may be normalized rather than byte-identical. Applications
using supported named registered bindings continue to use the same API; report
any historical script that differs under the versioned dialect.

TPipe artifacts are published to AWS CodeArtifact. The README's
`## Installation` section on `main` covers the source-build path
(clone, build locally, depend on `com.TTT:TPipe:1.0.0` modules).

For the prebuilt binary install path, see the
[tentrilliontriangles.com pricing page](https://tentrilliontriangles.com/pricing/),
which has per-edition install commands with the CodeArtifact URLs
and `aws codeartifact get-authorization-token` instructions.

## License

This repo's `LICENSE` file is AGPL-3.0 for the Community edition
(the source-build target on `main`). The Startup edition, built
from the `startup-license` branch, ships the TPipe Startup License 1.0
text in its `-license.jar` artifact.

See https://tentrilliontriangles.com/licenses/ for the canonical
license texts and full terms.
