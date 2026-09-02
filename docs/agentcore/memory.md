# Memory

`AgentCoreMemoryBackend` implements Core's provider-neutral
`ContextPersistenceBackend`. It stores exact serialized `ContextWindow` and
`TodoList` values as opaque AgentCore Memory records:

1. Serialize with TPipe's existing serializer.
2. Gzip and base64 the serialized value.
3. Split the encoded payload into bounded chunks.
4. Write a manifest containing key, revision, chunk count, checksum, and codec.
5. Verify chunk count and SHA-256 checksum before decoding.

Writes and deletes use batches of at most 100 records. Reads and index listing
follow service pagination. The adapter enforces a 12,000-chunk maximum and a
12,000-character payload chunk size so record metadata stays below the service
record limit. It does not use semantic retrieval for exact persistence.

Use `AgentCoreSemanticMemory` for `createEvent` and
`retrieveMemoryRecords`. Keeping these APIs separate prevents an embedding or
summary result from silently replacing exact TPipe state.

AgentCore Memory is not treated as a distributed lock service. Core's
`ContextLockBackend` is optional; only a backend that explicitly implements
that capability participates in lock operations.
