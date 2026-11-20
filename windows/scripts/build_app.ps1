param(
    [string]$Configuration = "Release",
    [string]$OutputDir = "$PSScriptRoot\..\dist",
    [switch]$SelfContained
)

$ErrorActionPreference = "Stop"

$dotnet = $null
$globalDotnet = Get-Command "dotnet" -ErrorAction SilentlyContinue
if ($globalDotnet) {
    $sdks = & $globalDotnet.Source --list-sdks 2>$null
    if ($sdks) {
        $dotnet = $globalDotnet.Source
    }
}
if (-not $dotnet) {
    $localDotnet = Join-Path $env:LOCALAPPDATA "Microsoft\dotnet\dotnet.exe"
    if (Test-Path $localDotnet) {
        $dotnet = $localDotnet
    } else {
        throw ".NET 8 SDK not found. Install .NET 8 SDK first."
    }
}

$project = Join-Path $PSScriptRoot "..\MonitorApp\MonitorApp.csproj"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$scValue = if ($SelfContained) { "true" } else { "false" }

& $dotnet publish $project `
    -c $Configuration `
    -r win-x64 `
    --self-contained $scValue `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:DebugType=none `
    -o $OutputDir

if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed with exit code $LASTEXITCODE"
}

Write-Host "Built Windows Monitor desktop app at: $(Join-Path $OutputDir 'Monitor.exe')"
