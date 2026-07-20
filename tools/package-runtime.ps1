[CmdletBinding()]
param([ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndk = Join-Path $sdk 'ndk\25.2.9519653'
if (-not (Test-Path $ndk)) { throw "NDK not found: $ndk" }
Set-Location $root
& .\gradlew.bat ":runtime-agent:assemble$Configuration"
if ($LASTEXITCODE -ne 0) { throw 'Gradle runtime build failed.' }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$d8 = Join-Path $buildTools.FullName 'd8.bat'
$flavor = $Configuration.ToLower()
$agentJar = Get-ChildItem "$root\runtime-agent\build\intermediates\aar_main_jar\$flavor" -Filter classes.jar -Recurse | Select-Object -First 1
$apiJar = Get-ChildItem "$root\runtime-api\build\intermediates\aar_main_jar\$flavor" -Filter classes.jar -Recurse | Select-Object -First 1
if (-not $agentJar -or -not $apiJar) { throw 'Could not locate runtime classes.jar files.' }
$out = Join-Path $root 'dist\payload\arm64-v8a'
New-Item -ItemType Directory -Force $out | Out-Null
& $d8 '--min-api' '27' '--output' $out $agentJar.FullName $apiJar.FullName
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
Copy-Item "$root\controller\inject.sh" "$root\dist\inject.sh" -Force
Copy-Item "$root\controller\profiles" "$root\dist\profiles" -Recurse -Force
Write-Host "Runtime bundle: $root\dist"
