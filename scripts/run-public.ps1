#Requires -Version 5.1
<##
Run the existing personal hub in the foreground, with local collection enabled.
No dependency installation, account setup, proxy changes, or task registration.
##>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$PublicUrl,

    [string]$PythonPath = '',
    [string]$StateDir = '',

    [ValidateRange(1, 65535)]
    [int]$Port = 8766
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runExitCode = 1
$stateLock = $null
$locationPushed = $false

function Resolve-ProjectPath {
    param([string]$Candidate, [string]$ProjectRoot)
    if (-not [System.IO.Path]::IsPathRooted($Candidate)) {
        $Candidate = Join-Path -Path $ProjectRoot -ChildPath $Candidate
    }
    return (Resolve-Path -LiteralPath $Candidate -ErrorAction Stop).ProviderPath
}

try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'This launcher is for Windows. Use python -m agent_monitor on other systems.'
    }
    $currentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        if ($currentIdentity.User.Value -in @('S-1-5-18', 'S-1-5-19', 'S-1-5-20')) {
            throw 'Run as the Windows user who owns the Codex / Claude sessions, not a built-in service account.'
        }
    } finally {
        $currentIdentity.Dispose()
    }

    $publicUri = $null
    if ($PublicUrl -match '[\s\x00-\x1f\x7f]' -or
        -not [Uri]::TryCreate($PublicUrl, [UriKind]::Absolute, [ref]$publicUri)) {
        throw 'PublicUrl must be an absolute HTTPS origin.'
    }
    if ($publicUri.Scheme -ne 'https' -or
        [string]::IsNullOrEmpty($publicUri.Host) -or
        -not [string]::IsNullOrEmpty($publicUri.UserInfo) -or
        $publicUri.AbsolutePath -ne '/' -or
        -not [string]::IsNullOrEmpty($publicUri.Query) -or
        -not [string]::IsNullOrEmpty($publicUri.Fragment) -or
        $publicUri.Port -lt 1 -or $publicUri.Port -gt 65535) {
        throw 'PublicUrl must use HTTPS without credentials, a path, a query, or a fragment.'
    }
    # Canonicalize the host and omit the default :443, matching browser Origin.
    $publicOrigin = $publicUri.GetLeftPart([UriPartial]::Authority)
    $projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'agent_monitor\__main__.py') -PathType Leaf)) {
        throw 'The launcher must remain in the scripts folder of the Agent Monitor project.'
    }

    if ([string]::IsNullOrWhiteSpace($PythonPath)) {
        $PythonPath = Join-Path $projectRoot '.venv\Scripts\python.exe'
    }
    if ([string]::IsNullOrWhiteSpace($StateDir)) {
        $StateDir = Join-Path $projectRoot '.state\hub'
    }
    try {
        $resolvedPython = Resolve-ProjectPath $PythonPath $projectRoot
    } catch {
        throw 'PythonPath does not exist. Point it to the existing Python environment; no dependencies are installed by this script.'
    }
    if (-not (Test-Path -LiteralPath $resolvedPython -PathType Leaf) -or
        [System.IO.Path]::GetExtension($resolvedPython) -ne '.exe') {
        throw 'PythonPath must point to an existing python.exe.'
    }
    try {
        $resolvedState = Resolve-ProjectPath $StateDir $projectRoot
    } catch {
        throw 'StateDir does not exist. Use the original hub state directory to preserve the existing account and devices.'
    }
    if (-not (Test-Path -LiteralPath $resolvedState -PathType Container) -or
        -not (Test-Path -LiteralPath (Join-Path $resolvedState 'monitor.sqlite3') -PathType Leaf)) {
        throw 'StateDir must contain the existing monitor.sqlite3. For a new deployment, initialize the account locally first.'
    }

    # Hold an exclusive lock across launcher instances sharing this state folder.
    # This is an empty coordination file; account data and passwords are not read.
    try {
        $stateLock = [System.IO.File]::Open(
            (Join-Path $resolvedState '.public-run.lock'),
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    } catch {
        throw 'This state directory is already used by another public launcher, or is not writable. Do not start a second hub.'
    }
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if (@($listeners | Where-Object { $_.Port -eq $Port }).Count -gt 0) {
        throw "Port $Port is already listening. Stop the existing hub intentionally before retrying; this launcher will not stop it or start a second server."
    }

    & $resolvedPython -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)'
    if ($LASTEXITCODE -ne 0) {
        throw 'Python 3.12 or newer is required.'
    }
    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true
    $pythonArguments = @(
        '-m', 'agent_monitor',
        '--host', '127.0.0.1',
        '--port', [string]$Port,
        '--state-dir', $resolvedState,
        '--public-url', $publicOrigin
    )
    Write-Host "HTTPS address: $publicOrigin"
    Write-Host "Backend: http://127.0.0.1:$Port (foreground; local collection enabled)"
    & $resolvedPython @pythonArguments
    $runExitCode = $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine("Agent Monitor could not start: {0}", $_.Exception.Message)
} finally {
    if ($locationPushed) { Pop-Location }
    if ($null -ne $stateLock) { $stateLock.Dispose() }
}
exit $runExitCode
