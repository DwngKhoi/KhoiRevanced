# Pine runtime artifacts

These binaries are exported from [`canyie/pine`](https://github.com/canyie/pine)
revision `216d910f18b18430a5d21c510affb221a9833a55`:

- `pine-core.jar` — ART hook API
- `pine-xposed.jar` — `de.robv.android.xposed` compatibility API
- `libpine.so` — arm64 ART hook engine

`tools/package-runtime.ps1` puts these artifacts into the root-only standalone
runtime bundle.  They are deliberately separate from the injected agent so the
Pine revision can be upgraded without changing injector code.

Pine is licensed under the Anti-996 License 1.0.  See its upstream repository
for the complete license text and source.
