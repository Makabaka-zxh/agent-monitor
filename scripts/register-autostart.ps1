#Requires -Version 5.1
<#
Register or remove this project's per-user, at-logon task.
Registration does not start the task or change a running service.
#>
[CmdletBinding(DefaultParameterSetName = 'Register')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Register')]
    [ValidateNotNullOrEmpty()]
    [string]$PublicUrl,

    [Parameter(ParameterSetName = 'Register')]
    [string]$PythonPath = '',

    [Parameter(ParameterSetName = 'Register')]
    [string]$StateDir = '',

    [Parameter(ParameterSetName = 'Register')]
    [ValidateRange(1, 65535)]
    [int]$Port = 8766,

    [Parameter(Mandatory = $true, ParameterSetName = 'Uninstall')]
    [switch]$Uninstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskName = 'AgentMonitor-PublicHub'
$taskPath = '\'

function Quote-NativeArgument {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value -match '[\x00-\x1f\x7f]') {
        throw 'Task arguments must not contain control characters.'
    }
    # Windows command-line quoting, including a directory's trailing backslash.
    $quoted = [System.Text.StringBuilder]::new()
    [void]$quoted.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $slashes += 1
            continue
        }
        if ($character -eq '"') {
            [void]$quoted.Append(('\' * ($slashes * 2 + 1)))
        } else {
            [void]$quoted.Append(('\' * $slashes))
        }
        [void]$quoted.Append($character)
        $slashes = 0
    }
    [void]$quoted.Append(('\' * ($slashes * 2)))
    [void]$quoted.Append('"')
    return $quoted.ToString()
}

function Resolve-ProjectItem {
    param([string]$Candidate, [string]$ProjectRoot)
    if (-not [System.IO.Path]::IsPathRooted($Candidate)) {
        $Candidate = Join-Path $ProjectRoot $Candidate
    }
    return (Resolve-Path -LiteralPath $Candidate -ErrorAction Stop).ProviderPath
}

try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'This script requires Windows Task Scheduler.'
    }
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $currentSid = $identity.User.Value
        $currentAccount = $identity.Name
    } finally {
        $identity.Dispose()
    }
    if ($currentSid -in @('S-1-5-18', 'S-1-5-19', 'S-1-5-20')) {
        throw 'Run as the Windows user who owns the local sessions, not a built-in service account.'
    }
    $projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
    $launcher = Join-Path $projectRoot 'scripts\run-public.ps1'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw 'Keep this script in the scripts directory beside run-public.ps1.'
    }
    $powershellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $powershellExe -PathType Leaf)) {
        throw 'Windows PowerShell could not be found.'
    }

    $pathHasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $canonicalPath = $projectRoot.Replace('/', '\').TrimEnd('\').ToLowerInvariant()
        $pathDigest = $pathHasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonicalPath))
        $projectHash = [BitConverter]::ToString($pathDigest).Replace('-', '').ToLowerInvariant()
    } finally {
        $pathHasher.Dispose()
    }
    $ownershipMarker = "AgentMonitor.PublicHub; schema=1; project=$projectHash"
    $argumentPrefix = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File ' + (Quote-NativeArgument $launcher) + ' '

    if (-not $Uninstall) {
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
        $publicOrigin = $publicUri.GetLeftPart([UriPartial]::Authority)
        if ([string]::IsNullOrWhiteSpace($PythonPath)) {
            $PythonPath = Join-Path $projectRoot '.venv\Scripts\python.exe'
        }
        if ([string]::IsNullOrWhiteSpace($StateDir)) {
            $StateDir = Join-Path $projectRoot '.state\hub'
        }
        try {
            $resolvedPython = Resolve-ProjectItem $PythonPath $projectRoot
            $resolvedState = Resolve-ProjectItem $StateDir $projectRoot
        } catch {
            throw 'PythonPath and StateDir must point to existing locations. No dependencies or databases are created.'
        }
        if (-not (Test-Path -LiteralPath $resolvedPython -PathType Leaf) -or
            [System.IO.Path]::GetExtension($resolvedPython) -ne '.exe') {
            throw 'PythonPath must point to an existing python.exe.'
        }
        if (-not (Test-Path -LiteralPath $resolvedState -PathType Container) -or
            -not (Test-Path -LiteralPath (Join-Path $resolvedState 'monitor.sqlite3') -PathType Leaf)) {
            throw 'StateDir must contain the original monitor.sqlite3.'
        }
        $taskArguments = $argumentPrefix +
            '-PublicUrl ' + (Quote-NativeArgument $publicOrigin) +
            ' -PythonPath ' + (Quote-NativeArgument $resolvedPython) +
            ' -StateDir ' + (Quote-NativeArgument $resolvedState) +
            ' -Port ' + [string]$Port
    }

    Import-Module ScheduledTasks -ErrorAction Stop
    # Enumerating the known root folder distinguishes missing tasks from access errors.
    $existingTasks = @(Get-ScheduledTask -TaskPath $taskPath -ErrorAction Stop | Where-Object { $_.TaskName -eq $taskName })
    if ($existingTasks.Count -gt 1) {
        throw 'The fixed task name is ambiguous. No task was changed.'
    }
    $existing = if ($existingTasks.Count -eq 1) { $existingTasks[0] } else { $null }
    if ($null -ne $existing) {
        $actions = @($existing.Actions)
        # Task Scheduler may store a local account as just its short name even
        # when registration supplied the SID. Compare resolved identities.
        $existingSid = $null
        try {
            $principalId = [string]$existing.Principal.UserId
            if ($principalId.StartsWith('S-1-', [StringComparison]::OrdinalIgnoreCase)) {
                $existingSid = ([System.Security.Principal.SecurityIdentifier]::new($principalId)).Value
            } else {
                $existingSid = ([System.Security.Principal.NTAccount]::new($principalId)).Translate([System.Security.Principal.SecurityIdentifier]).Value
            }
        } catch {
            # An unresolved account cannot establish ownership.
        }
        $owned = $existing.Description -ceq $ownershipMarker -and
            $existingSid -eq $currentSid -and
            $actions.Count -eq 1
        if ($owned) {
            $owned = [string]::Equals($actions[0].Execute, $powershellExe, [StringComparison]::OrdinalIgnoreCase) -and
                [string]::Equals($actions[0].WorkingDirectory, $projectRoot, [StringComparison]::OrdinalIgnoreCase) -and
                ([string]$actions[0].Arguments).StartsWith($argumentPrefix, [StringComparison]::OrdinalIgnoreCase)
        }
        if (-not $owned) {
            throw 'AgentMonitor-PublicHub already exists but is not owned by this project and current user. Refusing to replace or remove it.'
        }
    }

    if ($Uninstall) {
        if ($null -eq $existing) {
            Write-Host 'AgentMonitor-PublicHub is not registered. Nothing changed.'
        } else {
            Unregister-ScheduledTask -InputObject $existing -Confirm:$false -ErrorAction Stop
            Write-Host 'Removed this project''s logon task. No running process, data, or HTTPS mapping was changed.'
        }
        exit 0
    }

    $action = New-ScheduledTaskAction -Execute $powershellExe -Argument $taskArguments -WorkingDirectory $projectRoot
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentSid
    $principal = New-ScheduledTaskPrincipal -UserId $currentSid -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $definition = New-ScheduledTask -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description $ownershipMarker
    $registration = @{ TaskName = $taskName; TaskPath = $taskPath; InputObject = $definition; ErrorAction = 'Stop' }
    # Never use Force when a same-name task was not inspected and proven ours.
    if ($null -ne $existing) { $registration.Force = $true }
    Register-ScheduledTask @registration | Out-Null
    Write-Host 'Registered AgentMonitor-PublicHub for this user''s next Windows logon. The task was not started.'
} catch {
    [Console]::Error.WriteLine("Autostart setup failed: {0}", $_.Exception.Message)
    exit 1
}
