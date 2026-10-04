# Optional Xiaomi onboarding metadata, supplied at build time rather than kept in source.
# Xiaomi: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132 (section 3.3).
# CertificateMode describes the certificate registered with Xiaomi, not android:debuggable.

function Get-MonitorXiaomiConfiguration {
    param(
        [AllowEmptyString()][string]$AppId = '',
        [ValidateSet('Unconfigured', 'Test', 'Production')][string]$CertificateMode = 'Unconfigured'
    )
    if ([string]::IsNullOrEmpty($AppId)) {
        if ($CertificateMode -ne 'Unconfigured') { throw 'A Xiaomi certificate mode requires a platform-issued App ID.' }
        return [pscustomobject]@{ Enabled = $false; AppId = ''; DebugValue = $null }
    }
    if ($AppId -cne $AppId.Trim()) { throw 'Xiaomi App ID must not contain leading or trailing whitespace.' }
    foreach ($character in $AppId.ToCharArray()) {
        if ([char]::IsControl($character)) { throw 'Xiaomi App ID must not contain control characters.' }
    }
    try { $null = [System.Xml.XmlConvert]::VerifyXmlChars($AppId) }
    catch { throw 'Xiaomi App ID must be valid XML text.' }
    if ($CertificateMode -eq 'Unconfigured') {
        throw 'Select XiaomiCertificateMode Test or Production to match the signing certificate registered with Xiaomi.'
    }
    # Xiaomi publishes no App ID alphabet or numeric-length contract. Treat it as opaque text.
    return [pscustomobject]@{ Enabled = $true; AppId = $AppId; DebugValue = $(if ($CertificateMode -eq 'Test') { 'true' } else { 'false' }) }
}

function ConvertTo-MonitorAndroidString {
    param([Parameter(Mandatory = $true)][string]$Value)
    # Android string parsing follows XML parsing. Quote the entire value to preserve spaces
    # and leading @/?, and escape Android's own backslash/quote/apostrophe syntax separately.
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"').Replace("'", "\'") + '"'
}

function New-MonitorBuildInputs {
    param(
        [Parameter(Mandatory = $true)][string]$SourceManifest,
        [Parameter(Mandatory = $true)][string]$OutputDirectory,
        [AllowEmptyString()][string]$XiaomiAppId = '',
        [ValidateSet('Unconfigured', 'Test', 'Production')][string]$XiaomiCertificateMode = 'Unconfigured'
    )
    $configuration = Get-MonitorXiaomiConfiguration -AppId $XiaomiAppId -CertificateMode $XiaomiCertificateMode
    $sourcePath = (Resolve-Path -LiteralPath $SourceManifest -ErrorAction Stop).ProviderPath
    $readerSettings = New-Object System.Xml.XmlReaderSettings
    $readerSettings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $readerSettings.XmlResolver = $null
    $document = New-Object System.Xml.XmlDocument
    $document.PreserveWhitespace = $true
    $document.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create($sourcePath, $readerSettings)
    try { $document.Load($reader) } finally { $reader.Dispose() }
    $applications = $document.SelectNodes('/manifest/application')
    if ($applications.Count -ne 1) { throw 'The source manifest must have exactly one application element.' }
    $application = $applications[0]
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    foreach ($metadata in $application.SelectNodes('meta-data')) {
        if ($metadata.GetAttribute('name', $androidNamespace) -in @('com.xiaomi.xms.APP_ID', 'com.xiaomi.xms.BUILD_TYPE_DEBUG')) {
            throw 'Keep Xiaomi onboarding metadata out of the source manifest; supply it through build parameters.'
        }
    }
    if (-not $configuration.Enabled) {
        # Never consume stale generated files from an earlier, configured build.
        return [pscustomobject]@{ ManifestPath = $sourcePath; ResourceDirectory = $null; XiaomiConfigured = $false }
    }
    $outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
    $manifestPath = Join-Path $outputPath 'AndroidManifest.xml'
    if ([string]::Equals($sourcePath, $manifestPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Generated manifest must not overwrite the source manifest.'
    }
    $resourceDirectory = Join-Path $outputPath 'res'
    $valuesDirectory = Join-Path $resourceDirectory 'values'
    New-Item -ItemType Directory -Force -Path $valuesDirectory | Out-Null
    foreach ($entry in @(
        @('com.xiaomi.xms.APP_ID', '@string/monitor_xiaomi_app_id'),
        @('com.xiaomi.xms.BUILD_TYPE_DEBUG', $configuration.DebugValue)
    )) {
        $metadata = $document.CreateElement('meta-data')
        $null = $metadata.SetAttribute('name', $androidNamespace, $entry[0])
        # android:value resolves a string resource; android:resource would expose its numeric ID.
        $null = $metadata.SetAttribute('value', $androidNamespace, $entry[1])
        $null = $application.AppendChild($metadata)
    }
    $resources = New-Object System.Xml.XmlDocument
    $root = $resources.CreateElement('resources')
    $null = $resources.AppendChild($root)
    $string = $resources.CreateElement('string')
    $null = $string.SetAttribute('name', 'monitor_xiaomi_app_id')
    $null = $string.SetAttribute('translatable', 'false')
    $null = $string.SetAttribute('formatted', 'false')
    $string.InnerText = ConvertTo-MonitorAndroidString $configuration.AppId
    $null = $root.AppendChild($string)
    $writerSettings = New-Object System.Xml.XmlWriterSettings
    $writerSettings.Encoding = New-Object System.Text.UTF8Encoding($false)
    $writerSettings.Indent = $true
    foreach ($item in @(@($document, $manifestPath), @($resources, (Join-Path $valuesDirectory 'xiaomi-build.xml')))) {
        $writer = [System.Xml.XmlWriter]::Create($item[1], $writerSettings)
        try { $item[0].Save($writer) } finally { $writer.Dispose() }
    }
    return [pscustomobject]@{ ManifestPath = $manifestPath; ResourceDirectory = $resourceDirectory; XiaomiConfigured = $true }
}
