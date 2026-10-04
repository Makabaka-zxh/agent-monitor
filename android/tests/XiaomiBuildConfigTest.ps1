# Standalone synthetic configuration tests. Does not compile, sign or install an APK.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
. (Join-Path $projectDirectory 'xiaomi-build-config.ps1')
$testDirectory = Join-Path $projectDirectory ('build/config-tests/' + [guid]::NewGuid().ToString('N'))
$sourceDirectory = Join-Path $testDirectory 'source'
New-Item -ItemType Directory -Force -Path $sourceDirectory | Out-Null
$source = Join-Path $sourceDirectory 'AndroidManifest.xml'
[IO.File]::WriteAllText($source, '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="synthetic.test"><uses-permission android:name="synthetic.permission"/><application android:debuggable="false"><meta-data android:name="unrelated" android:value="kept"/></application></manifest>')
$initialHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$checks = 0
function Check([bool]$Condition, [string]$Label) {
    $script:checks++
    if (-not $Condition) { throw $Label }
}
function Reject([scriptblock]$Action, [string]$Label) {
    $rejected = $false
    try { $null = & $Action } catch { $rejected = $true }
    Check $rejected $Label
}
function Read-Generated([string]$AppId, [string]$Mode, [string]$Name) {
    $result = New-MonitorBuildInputs -SourceManifest $source -OutputDirectory (Join-Path $testDirectory $Name) -XiaomiAppId $AppId -XiaomiCertificateMode $Mode
    [xml]$manifest = Get-Content -LiteralPath $result.ManifestPath -Raw
    [xml]$resources = Get-Content -LiteralPath (Join-Path $result.ResourceDirectory 'values/xiaomi-build.xml') -Raw
    return [pscustomobject]@{ Result = $result; Manifest = $manifest; Resources = $resources }
}
$ns = 'http://schemas.android.com/apk/res/android'
$plainDirectory = Join-Path $testDirectory 'not-configured'
$plain = New-MonitorBuildInputs -SourceManifest $source -OutputDirectory $plainDirectory
Check (-not $plain.XiaomiConfigured -and $plain.ManifestPath -eq $source -and $null -eq $plain.ResourceDirectory) 'Unconfigured input must use the original manifest and no OEM resources.'
Check (-not (Test-Path -LiteralPath $plainDirectory)) 'Unconfigured generation must not write files.'
Reject { Get-MonitorXiaomiConfiguration -AppId 'synthetic-app-id' } 'App ID requires an explicit certificate registration mode.'
Reject { Get-MonitorXiaomiConfiguration -CertificateMode Test } 'A certificate mode cannot invent an App ID.'
Reject { Get-MonitorXiaomiConfiguration -AppId 'synthetic-app-id' -CertificateMode Incorrect } 'Unknown certificate modes must be rejected.'
foreach ($invalid in @(' leading', 'trailing ', ' ', ("a`nb"), ('a' + [char]0 + 'b'), ([string][char]0xd800))) {
    Reject { Get-MonitorXiaomiConfiguration -AppId $invalid -CertificateMode Test } 'Invalid copy/paste or XML characters must be rejected.'
}
$numeric = Read-Generated '00012345678901234567890' Test 'numeric'
$metadata = @($numeric.Manifest.manifest.application.'meta-data')
$idMetadata = $metadata | Where-Object { $_.GetAttribute('name', $ns) -eq 'com.xiaomi.xms.APP_ID' }
$debugMetadata = $metadata | Where-Object { $_.GetAttribute('name', $ns) -eq 'com.xiaomi.xms.BUILD_TYPE_DEBUG' }
Check ($idMetadata.GetAttribute('value', $ns) -eq '@string/monitor_xiaomi_app_id' -and -not $idMetadata.HasAttribute('resource', $ns)) 'App ID must resolve a typed string, not an integer resource ID.'
Check ($numeric.Resources.resources.string.InnerText -ceq '"00012345678901234567890"') 'A numeric identifier preserves leading zeroes and its string type.'
Check ($debugMetadata.GetAttribute('value', $ns) -ceq 'true') 'Test certificate mode produces boolean true.'
Check ($numeric.Manifest.manifest.application.GetAttribute('debuggable', $ns) -ceq 'false') 'Certificate mode must not change or infer android:debuggable.'
Check ($metadata.Count -eq 3 -and $numeric.Manifest.manifest.'uses-permission'.GetAttribute('name', $ns) -eq 'synthetic.permission') 'Existing metadata and permissions remain intact.'
Check ($numeric.Resources.resources.string.GetAttribute('formatted') -eq 'false' -and $numeric.Resources.resources.string.GetAttribute('translatable') -eq 'false') 'Identifier must not become a format string or a translation.'
$production = Read-Generated 'synthetic-platform-id' Production 'production'
$productionFlag = @($production.Manifest.manifest.application.'meta-data') | Where-Object { $_.GetAttribute('name', $ns) -eq 'com.xiaomi.xms.BUILD_TYPE_DEBUG' }
Check ($productionFlag.GetAttribute('value', $ns) -ceq 'false') 'Production certificate mode produces boolean false.'
$cases = @(
    @('a&<b>', '"a&<b>"'),
    @('a"b', '"a\"b"'),
    @("a'b", '"a\''b"'),
    @('a\nb', '"a\\nb"'),
    @('@string/other', '"@string/other"'),
    @('?attr/other', '"?attr/other"'),
    @('opaque  id%s', '"opaque  id%s"')
)
$index = 0
foreach ($case in $cases) {
    $generated = Read-Generated $case[0] Test ('syntax-' + $index++)
    Check ($generated.Resources.resources.string.InnerText -ceq $case[1]) 'XML and Android string escaping must preserve the opaque identifier.'
}
$disabledAgain = New-MonitorBuildInputs -SourceManifest $source -OutputDirectory (Join-Path $testDirectory 'numeric')
Check (-not $disabledAgain.XiaomiConfigured -and $null -eq $disabledAgain.ResourceDirectory -and $disabledAgain.ManifestPath -eq $source) 'Disabling configuration must never consume stale generated OEM metadata.'
Reject { New-MonitorBuildInputs -SourceManifest $source -OutputDirectory $sourceDirectory -XiaomiAppId 'synthetic-platform-id' -XiaomiCertificateMode Test } 'Generated output must not overwrite the source manifest.'
$duplicateSource = Join-Path $sourceDirectory 'with-static-id.xml'
[IO.File]::WriteAllText($duplicateSource, '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><meta-data android:name="com.xiaomi.xms.APP_ID" android:value="synthetic"/></application></manifest>')
Reject { New-MonitorBuildInputs -SourceManifest $duplicateSource -OutputDirectory (Join-Path $testDirectory 'duplicate') } 'Source-embedded onboarding values must not bypass optional configuration.'
$dtdSource = Join-Path $sourceDirectory 'with-dtd.xml'
[IO.File]::WriteAllText($dtdSource, '<!DOCTYPE manifest [<!ENTITY value "synthetic">]><manifest><application/></manifest>')
Reject { New-MonitorBuildInputs -SourceManifest $dtdSource -OutputDirectory (Join-Path $testDirectory 'dtd') } 'Manifest parsing must not permit DTD or external entity resolution.'
Check ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -eq $initialHash) 'Source manifest bytes must remain unchanged.'
$parseErrors = $null; $tokens = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $projectDirectory 'build.ps1'), [ref]$tokens, [ref]$parseErrors)
Check ($parseErrors.Count -eq 0) 'Modified build entry must parse without executing the APK build.'
Write-Output "XiaomiBuildConfigTest: $checks checks passed; no APK built."
