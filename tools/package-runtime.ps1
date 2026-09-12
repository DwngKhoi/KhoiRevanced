[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug',
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndk = Join-Path $sdk 'ndk\25.2.9519653'
if (-not (Test-Path $ndk)) { throw "NDK not found: $ndk" }
Set-Location $root
$flavor = $Configuration.ToLower()
$localProperties = Join-Path $root 'local.properties'
# Forward slashes are valid in local.properties on Windows and avoid AGP's
# fragile interpretation of escaped backslashes in ndk.dir.
$escapedSdk = $sdk -replace '\\', '/'
$escapedNdk = $ndk -replace '\\', '/'
if (-not (Test-Path $localProperties)) {
    "sdk.dir=$escapedSdk`nndk.dir=$escapedNdk" | Set-Content -NoNewline $localProperties
} elseif (-not (Select-String -Quiet -Path $localProperties -Pattern '^ndk\.dir=')) {
    Add-Content -Path $localProperties -Value "`nndk.dir=$escapedNdk"
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
$moduleApk = Join-Path $root "app\build\outputs\apk\$flavor\app-$flavor.apk"
if ($Rebuild -or -not (Test-Path $moduleApk)) {
    & .\gradlew.bat ":app:assemble$Configuration" --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle NexAlloy compatibility build failed.' }
}
if (-not (Test-Path $moduleApk)) { throw "NexAlloy compatibility APK is missing: $moduleApk" }
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
$pineRoot = Join-Path $root 'third_party\pine-libs'
$pineCoreJar = Join-Path $pineRoot 'pine-core.jar'
$pineXposedJar = Join-Path $pineRoot 'pine-xposed.jar'
if (-not (Test-Path $pineCoreJar) -or -not (Test-Path $pineXposedJar)) {
    throw 'Pine compatibility jars are missing from third_party/pine-libs.'
}
$out = Join-Path $root 'dist\payload\arm64-v8a'
New-Item -ItemType Directory -Force $out | Out-Null
& $d8 '--min-api' '27' '--output' $out $agentJar.FullName $apiJar.FullName $kotlinStdlib.FullName $pineCoreJar $pineXposedJar
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
Copy-Item (Join-Path $pineRoot 'libpine.so') (Join-Path $out 'libpine.so') -Force
# Preserve the complete upstream module as a read-only dexpack.  It is loaded
# through Pine in-process and is never installed as an APK.
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
Copy-Item "$root\controller\profiles\*" "$root\dist\profiles" -Recurse -Force

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
