[CmdletBinding()]
param(
    [switch]$Merge,
    [switch]$UpdateSubmodules
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (git diff --quiet) -or -not (git diff --cached --quiet)) {
    throw 'Working tree has changes. Commit or stash them before syncing upstream.'
}
git fetch upstream --tags
git log --oneline --decorate 'HEAD..upstream/main'
if ($Merge) {
    git merge --no-ff upstream/main -m 'merge: sync NexAlloy upstream'
    if ($LASTEXITCODE -ne 0) { throw 'Upstream merge has conflicts.' }
}
if ($UpdateSubmodules) {
    git submodule sync --recursive
    git submodule update --init --recursive
}
Write-Host 'Upstream status:'
git log -1 --oneline upstream/main
Write-Host 'Run .\gradlew.bat :runtime-api:testDebugUnitTest :runtime-agent:assembleDebug after resolving changes.'
