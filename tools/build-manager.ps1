[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # Generate the single self-extracting script first. The manager Gradle task
    # then embeds this exact file as an APK asset.
    & "$PSScriptRoot\package-runtime.ps1" -Configuration $Configuration -Rebuild
    & "$root\gradlew.bat" ":manager:assemble$Configuration" --no-daemon --console=plain
} finally {
    Pop-Location
}
