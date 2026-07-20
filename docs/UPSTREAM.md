# Keeping up with NexAlloy

`upstream` is the unmodified NexAlloy remote; `khoirevanced/main` is the fork branch.

```powershell
.\tools\sync-upstream.ps1
.\tools\sync-upstream.ps1 -Merge -UpdateSubmodules
.\gradlew.bat :runtime-api:testDebugUnitTest :runtime-agent:assembleDebug
```

Keep custom work in `runtime-*`, `native/`, `controller/`, `tools/`, and `docs/`. A required NexAlloy modification must be a narrow `port:` commit, separate from upstream merges.

NexAlloy is GPL-3.0. Keep its notices and release corresponding source for every distributed KhoiRevanced bundle.
