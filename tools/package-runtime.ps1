[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug',
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndk = Join-Path $sdk 'ndk\25.2.9519653'
if (-not (Test-Path $ndk)) { throw "NDK not found: $ndk" }
Set-Location $root
$flavor = $Configuration.ToLower()
$localProperties = Join-Path $root 'local.properties'
# Forward slashes are valid in local.properties on Windows and avoid AGP's
# fragile interpretation of escaped backslashes.
# Only sdk.dir is written. ndk.dir is deprecated: AGP resolves the NDK from
# each module's `android.ndkVersion`, and an ndk.dir that disagrees with it
# produces a [CXX1104] warning. The NDK path below is still used directly for
# the standalone CMake invocation.
$escapedSdk = $sdk -replace '\\', '/'
if (-not (Test-Path $localProperties)) {
    "sdk.dir=$escapedSdk" | Set-Content -NoNewline $localProperties
} elseif (-not (Select-String -Quiet -Path $localProperties -Pattern '^sdk\.dir=')) {
    Set-Content -Path $localProperties -Value "sdk.dir=$escapedSdk"
}
# Drop a stale ndk.dir left over from earlier runs; it now only causes warnings.
$staleNdkDir = Select-String -Quiet -Path $localProperties -Pattern '^ndk\.dir='
if ($staleNdkDir) {
    Write-Host 'Removing deprecated ndk.dir from local.properties (android.ndkVersion is authoritative).'
    (Get-Content $localProperties) |
        Where-Object { $_ -notmatch '^ndk\.dir=' } |
        Set-Content $localProperties
}
$existingAgentJar = Get-ChildItem "$root\runtime-agent\build\intermediates" -Filter classes.jar -Recurse -ErrorAction SilentlyContinue |
    Where-Object FullName -Match "aar_main_jar\\$flavor" | Select-Object -First 1
if ($Rebuild -or -not $existingAgentJar) {
    # Building only the agent still builds runtime-api's compile jar through
    # the project dependency. This avoids an AGP 9.2 CXX/NDK resolver bug
    # triggered by requesting both AAR assemble tasks in one invocation.
    & .\gradlew.bat ":runtime-agent:assemble$Configuration" --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle runtime build failed.' }
}
$moduleApk = Join-Path $root "app\build\outputs\apk\$flavor\nexalloy-payload-$flavor.apk"
if ($Rebuild -or -not (Test-Path $moduleApk)) {
    & .\gradlew.bat ":nexalloy-payload:assemble$Configuration" --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle NexAlloy payload build failed.' }
}
if (-not (Test-Path $moduleApk)) {
    # AGP 9 names the artifact after the Gradle project. Resolve the first
    # APK as a fallback so packaging remains compatible with older AGP output
    # names as well.
    $moduleApk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\$flavor") `
        -Filter '*.apk' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $moduleApk -or -not (Test-Path $moduleApk)) {
    throw "NexAlloy payload APK is missing under app/build/outputs/apk/$flavor."
}
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$d8 = Join-Path $buildTools.FullName 'd8.bat'
$agentJar = Get-ChildItem "$root\runtime-agent\build\intermediates" -Filter classes.jar -Recurse |
    Where-Object FullName -Match "aar_main_jar\\$flavor" | Select-Object -First 1
$apiJar = Get-ChildItem "$root\runtime-api\build\intermediates" -Filter classes.jar -Recurse |
    Where-Object FullName -Match "compile_library_classes_jar\\$flavor" | Select-Object -First 1
if (-not $agentJar -or -not $apiJar) { throw 'Could not locate runtime classes.jar files.' }
$kotlinStdlib = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib" `
    -Filter 'kotlin-stdlib-*.jar' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $kotlinStdlib) { throw 'Could not locate kotlin-stdlib in the Gradle cache.' }
$xposedApiRoot = Join-Path $root 'third_party\pine-libs'
$xposedCoreJar = Join-Path $xposedApiRoot 'pine-core.jar'
$xposedApiJar = Join-Path $xposedApiRoot 'pine-xposed.jar'
if (-not (Test-Path $xposedCoreJar) -or -not (Test-Path $xposedApiJar)) {
    throw 'Legacy Xposed API jars are missing from third_party/pine-libs.'
}

# `io.github.nexalloy.MainHook` extends `io.github.libxposed.api.XposedModule`.
# That API is only compileOnly for :nexalloy-payload, so it is not in the
# dexpack, and the standalone runtime has no LSPosed framework to provide it.
# Without these classes the agent's parent class loader cannot resolve MainHook
# and every launch dies with NoClassDefFoundError before a hook is installed.
#
# Resolve the AAR from the Gradle module cache (it is always present after the
# payload build above) and extract its classes.jar for d8.
$libxposedAar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\io.github.libxposed\api" `
    -Filter 'api-*.aar' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $libxposedAar) {
    throw 'Could not locate io.github.libxposed:api in the Gradle cache. Run the payload build first.'
}
$libxposedClasses = Join-Path $root '.out\libxposed-api-classes.jar'
Remove-Item -LiteralPath $libxposedClasses -Force -ErrorAction SilentlyContinue
$aarZip = [System.IO.Compression.ZipFile]::OpenRead($libxposedAar.FullName)
try {
    $entry = $aarZip.GetEntry('classes.jar')
    if ($null -eq $entry) { throw "classes.jar is missing from $($libxposedAar.Name)" }
    $entryStream = $entry.Open()
    try {
        $fileStream = [System.IO.File]::Create($libxposedClasses)
        try { $entryStream.CopyTo($fileStream) } finally { $fileStream.Dispose() }
    } finally { $entryStream.Dispose() }
} finally { $aarZip.Dispose() }
Write-Host "libxposed API classes: $($libxposedAar.Name) -> .out\libxposed-api-classes.jar"

$out = Join-Path $root 'dist\payload\arm64-v8a'
if (Test-Path $out) {
    Remove-Item -LiteralPath $out -Recurse -Force
}
New-Item -ItemType Directory -Force $out | Out-Null
& $d8 '--min-api' '27' '--output' $out $agentJar.FullName $apiJar.FullName $kotlinStdlib.FullName `
    $xposedCoreJar $xposedApiJar $libxposedClasses
if ($LASTEXITCODE -ne 0) { throw 'D8 payload conversion failed.' }
$toolchain = Join-Path $ndk 'build\cmake\android.toolchain.cmake'
$nativeBuild = Join-Path $root '.out\injector-arm64'
$cmakeDir = Get-ChildItem (Join-Path $sdk 'cmake') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$cmake = Join-Path $cmakeDir.FullName 'bin\cmake.exe'
$ninja = Join-Path $cmakeDir.FullName 'bin\ninja.exe'
& $cmake -S "$root\native\injector" -B $nativeBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-27'
& $cmake --build $nativeBuild --parallel
Copy-Item (Join-Path $nativeBuild 'khoirevanced-injector') $out -Force
$agent = Get-ChildItem "$root\runtime-agent\build\intermediates\cxx" -Filter libkhoirevanced_agent.so -Recurse | Where-Object FullName -Match 'arm64-v8a' | Select-Object -First 1
if (-not $agent) { throw 'Could not locate libkhoirevanced_agent.so.' }
Copy-Item $agent.FullName (Join-Path $out 'libkhoirevanced_agent.so') -Force
Copy-Item (Join-Path $root 'third_party\lsplant-libs\arm64-v8a\liblsplant.so') (Join-Path $out 'liblsplant.so') -Force
# Preserve the complete upstream module as a read-only dexpack.  It is loaded
# through the standalone LSPlant runtime in-process and is never installed as an APK.
Copy-Item $moduleApk (Join-Path $out 'nexalloy.dexpack') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkZip = [System.IO.Compression.ZipFile]::OpenRead($moduleApk)
try {
    $dexkitEntry = $apkZip.GetEntry('lib/arm64-v8a/libdexkit.so')
    if ($null -eq $dexkitEntry) { throw 'NexAlloy dexpack does not contain arm64 DexKit.' }
    $dexkitOutput = Join-Path $out 'libdexkit.so'
    $entryStream = $dexkitEntry.Open()
    try {
        $fileStream = [System.IO.File]::Create($dexkitOutput)
        try { $entryStream.CopyTo($fileStream) } finally { $fileStream.Dispose() }
    } finally { $entryStream.Dispose() }
} finally { $apkZip.Dispose() }

# The bundle must contain every file inject.sh validates in prepare_payload.
# Verifying here turns a device-side "missing ... payload" abort into a build
# failure on the host. This has to run after every copy above, not right after
# d8, because the injector, agent, LSPlant, DexKit and dexpack land later.
$requiredPayload = @(
    'classes.dex',
    'khoirevanced-injector',
    'libdexkit.so',
    'libkhoirevanced_agent.so',
    'liblsplant.so',
    'nexalloy.dexpack'
)
$missingPayload = @($requiredPayload | Where-Object { -not (Test-Path (Join-Path $out $_)) })
if ($missingPayload.Count -gt 0) {
    throw "Runtime payload is incomplete, missing: $($missingPayload -join ', ')"
}

# The payload dexpack is `compileOnly` against the Xposed APIs, so none of them
# ship inside it. The agent's classes.dex has to supply every type the payload
# resolves through the DexClassLoader parent chain. A missing type here is a
# NoClassDefFoundError on device, long after the build has already gone green,
# so assert the types that gate payload loading.
# d8 writes a raw dex here, not a zip, so read the file directly.
$agentDex = Join-Path $out 'classes.dex'
if (-not (Test-Path $agentDex)) { throw "Agent dex is missing: $agentDex" }
$dexBytes = [System.IO.File]::ReadAllBytes($agentDex)
$dexText = [System.Text.Encoding]::ASCII.GetString($dexBytes)
$requiredTypes = @(
    'Lde/robv/android/xposed/XposedBridge;',
    'Lde/robv/android/xposed/XC_MethodHook;',
    'Lio/github/libxposed/api/XposedModule;'
)
$absentTypes = @($requiredTypes | Where-Object { $dexText.IndexOf($_, [StringComparison]::Ordinal) -lt 0 })
if ($absentTypes.Count -gt 0) {
    throw "Agent classes.dex is missing required types: $($absentTypes -join ', ')"
}
Write-Host "Agent DEX verified: $($requiredTypes -join ', ')"

Copy-Item "$root\controller\inject.sh" "$root\dist\inject.sh" -Force
# Git may check the controller shell script out with CRLF on Windows. Android's
# /system/bin/sh treats the resulting `set -eu\r` as an invalid option, so make
# the packaged (not source) runtime POSIX/LF before it enters the archive.
$packagedInject = Join-Path $root 'dist\inject.sh'
$injectText = [System.IO.File]::ReadAllText($packagedInject)
[System.IO.File]::WriteAllText(
    $packagedInject,
    ($injectText -replace "`r`n", "`n" -replace "`r", "`n"),
    [System.Text.UTF8Encoding]::new($false)
)
Copy-Item "$root\controller\inject.sh" "$root\dist\inject.sh" -Force
# Git may check the controller shell script out with CRLF on Windows. Android's
# /system/bin/sh treats the resulting `set -eu\r` as an invalid option, so make
# the packaged (not source) runtime POSIX/LF before it enters the archive.
$packagedInject = Join-Path $root 'dist\inject.sh'
$injectText = [System.IO.File]::ReadAllText($packagedInject)
[System.IO.File]::WriteAllText(
    $packagedInject,
    ($injectText -replace "`r`n", "`n" -replace "`r", "`n"),
    [System.Text.UTF8Encoding]::new($false)
)

# Copy the per-app profiles into the bundle.
#
# `Copy-Item 'dir\*' 'newDir' -Recurse` does NOT reliably create `newDir` as a
# directory: on Windows PowerShell it can create a plain *file* named `newDir`
# instead, silently dropping every profile but one. inject.sh then aborts on
# device with "unknown profile: youtube". Create the directory explicitly,
# copy the children, and fail loudly if the count does not match.
$profilesOut = Join-Path $root 'dist\profiles'
if (Test-Path $profilesOut) { Remove-Item -LiteralPath $profilesOut -Recurse -Force }
New-Item -ItemType Directory -Force $profilesOut | Out-Null
$profilesIn = Join-Path $root 'controller\profiles'
$expectedProfiles = @(Get-ChildItem -LiteralPath $profilesIn -File -Filter '*.conf')
if ($expectedProfiles.Count -eq 0) { throw "No profile definitions found in $profilesIn" }
foreach ($profile in $expectedProfiles) {
    Copy-Item -LiteralPath $profile.FullName -Destination (Join-Path $profilesOut $profile.Name) -Force
}
$packagedProfiles = @(Get-ChildItem -LiteralPath $profilesOut -File -Filter '*.conf')
if ($packagedProfiles.Count -ne $expectedProfiles.Count) {
    throw "Profile packaging incomplete: expected $($expectedProfiles.Count), packaged $($packagedProfiles.Count)."
}
Write-Host "Packaged profiles: $($packagedProfiles.Name -join ', ')"

# Emit one Android-POSIX shell file for releases. It contains a gzipped copy of
# the normal bundle and expands it only on the target device; source builds and
# debugging can still use dist\inject.sh directly.
$singleFile = Join-Path $root 'dist\KhoiRevanced.sh'
$archive = Join-Path $root '.out\khoirevanced-runtime.tar.gz'
Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
& tar.exe -C (Join-Path $root 'dist') -czf $archive inject.sh payload profiles
if ($LASTEXITCODE -ne 0) { throw 'Could not create single-file runtime archive.' }
$shellHeader = @'
#!/system/bin/sh
# Self-extracting KhoiRevanced runtime bundle. Run through su:
#   su -c 'sh /data/local/tmp/KhoiRevanced.sh launch youtube'
set -eu

TAG="KhoiRevanced"
ROOT="${KHOIREVANCED_HOME:-/data/local/tmp/khoirevanced-runtime}"
ARCHIVE="$ROOT/.payload.tar.gz"
MARKER="__KHOIREVANCED_ARCHIVE_BELOW__"

[ "$(id -u)" = 0 ] || { echo "$TAG: run through su -c" >&2; exit 1; }
mkdir -p "$ROOT"
line=$(awk -v marker="$MARKER" '$0 == marker { print NR + 1; exit }' "$0")
[ -n "$line" ] || { echo "$TAG: corrupted self-extracting bundle" >&2; exit 1; }
tail -n "+$line" "$0" | base64 -d > "$ARCHIVE"
tar -xzf "$ARCHIVE" -C "$ROOT"
chmod 0700 "$ROOT/inject.sh"
exec "$ROOT/inject.sh" "$@"
__KHOIREVANCED_ARCHIVE_BELOW__
'@
$shellHeader = ($shellHeader -replace "`r`n", "`n" -replace "`r", "`n").TrimEnd("`n") + "`n"
[System.IO.File]::WriteAllText($singleFile, $shellHeader, [System.Text.UTF8Encoding]::new($false))
$encodedArchive = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($archive))
[System.IO.File]::AppendAllText($singleFile, "$encodedArchive`n", [System.Text.UTF8Encoding]::new($false))
Write-Host "Runtime bundle: $root\dist"
Write-Host "Single-file runtime: $singleFile"
