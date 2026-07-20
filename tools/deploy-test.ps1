[CmdletBinding()]
param(
    [ValidateSet('youtube', 'youtube-music')]
    [string]$Profile = 'youtube',
    [switch]$Watch
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
if (-not (Test-Path "$dist\inject.sh")) {
    throw 'Missing dist bundle. Run .\tools\package-runtime.ps1 first.'
}

$device = (adb devices | Select-String "`tdevice$" | Select-Object -First 1)
if (-not $device) { throw 'No authorized Android device is connected through adb.' }

$target = '/data/local/tmp/khoirevanced-test'
adb shell "rm -rf $target"
adb push "$dist\." $target
if ($LASTEXITCODE -ne 0) { throw 'adb push failed.' }
adb shell "su -c 'chmod 0700 $target/inject.sh; $target/inject.sh $(if ($Watch) { 'watch' } else { 'launch' }) $Profile'"
adb shell "su -c '$target/inject.sh status $Profile'"
adb logcat -d -s KhoiRevanced:I '*:S'
