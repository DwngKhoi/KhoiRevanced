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
Copy-Item "$root\controller\inject.sh" "$root\dist\inject.sh" -Force
Copy-Item "$root\controller\profiles\*" "$root\dist\profiles" -Recurse -Force
Write-Host "Runtime bundle: $root\dist"
