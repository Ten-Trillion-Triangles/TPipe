# Native ABI Baselines

This directory holds the expected outputs of the native C compliance
tests, captured at known-good points in the development history. They
are the regression floors that subsequent ABI work must not break.

## `tpipe_abi_compliance-baseline.txt`

Captured at the end of Phase 2 (after the buffer-bounds hardening).
The compliance test calls 100 of the 163 declared TPipe_* functions
with safe dummy arguments and asserts they all return without
segfaulting. The baseline is "100/100 functions OK".

The `exit=99` at the bottom of the file is **not** a test failure.
It is the SubstrateVM safepoint abort that happens during teardown
of the graal_isolate — a known artifact of the current SubstrateVM
configuration, called out in the test source at
`test/native/c/tpipe_abi_compliance.c`:

> Teardown (best-effort — may abort with SubstrateVM safepoint error
> on some configurations, but the compliance result is already printed).

The compliance result printed before the teardown is the source of
truth. Re-running the test and asserting "100/100" in stdout (not
the exit code) is the right gate.

## Re-capturing the baseline

```bash
./test/native/c/tpipe_abi_compliance build/native/nativeCompile/TPipe.so \
    > test/native/baselines/tpipe_abi_compliance-baseline.txt 2>&1
```
