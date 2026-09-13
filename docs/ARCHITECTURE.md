# Standalone runtime architecture

## Trust boundary

The manager has no privileged Android permission and does not request
`WRITE_EXTERNAL_STORAGE`, overlay access, or an LSPosed service binding. Its
only privileged boundary is a short-lived `su -mm -c` process started after
the user grants root in the root manager.

`RootShell` owns process creation and exit-code handling. `RuntimeAsset` owns
copying the immutable shell bundle from APK assets. Keeping these operations
outside `MainActivity` makes the UI replaceable without changing the runtime
contract.

## Launch sequence

1. Manager runs `id` through `su` and requires both exit code `0` and
   `uid=0`.
2. Manager extracts `KhoiRevanced.sh` into its private cache.
3. The shell controller force-stops the selected target and prepares a
   root-owned runtime under the target's `code_cache`.
4. The controller resolves the target launcher, starts it, and selects only
   the main package process.
5. The arm64 injector loads `libkhoirevanced_agent.so`.
6. The agent waits for `Application`, initializes the native hook backend,
   loads the read-only NexAlloy dexpack, and writes `agent-status.txt`.
7. Manager closes its task only after the canonical ready state is visible.

## Why the payload is separate

NexAlloy patch code is still being migrated from its historical compatibility
interfaces. It is therefore built as `:nexalloy-payload`, copied into the
runtime as data, and never treated as the product APK. This keeps the
standalone manager independent from LSPosed installation and lets the patch
layer be replaced without redesigning root process control.
