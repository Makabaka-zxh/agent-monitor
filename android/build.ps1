#Requires -Version 5.1
# Windows build. Explicit paths override ANDROID_SDK_ROOT / ANDROID_HOME / JAVA_HOME.
# Release signing uses an existing local keystore; passwords are passed by environment name only.
param(
    [string]$AndroidSdk = '',
    [string]$JavaDirectory = '',
    [string]$KeyStorePath = '',
    [string]$KeyAlias = '',
    [switch]$Release,
    [AllowEmptyString()][string]$XiaomiAppId = '',
    [ValidateSet('Unconfigured', 'Test', 'Production')][string]$XiaomiCertificateMode = 'Unconfigured'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This build script requires Windows and PowerShell 5.1 or newer.'
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
}
if ([string]::IsNullOrWhiteSpace($JavaDirectory)) { $JavaDirectory = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { throw 'Set ANDROID_SDK_ROOT / ANDROID_HOME or pass -AndroidSdk.' }
if ([string]::IsNullOrWhiteSpace($JavaDirectory)) { throw 'Set JAVA_HOME to a JDK or pass -JavaDirectory.' }

function Assert-ReleaseManifest([string]$ManifestPath) {
    try {
        [xml]$releaseManifest = Get-Content -LiteralPath $ManifestPath -Raw
        $releaseApplication = $releaseManifest.SelectSingleNode('/manifest/application')
        if ($null -eq $releaseApplication -or
            $releaseApplication.GetAttribute('debuggable', 'http://schemas.android.com/apk/res/android') -cne 'false') {
            throw 'Invalid release manifest'
        }
    } catch {
        throw 'Release builds require an explicit android:debuggable="false" application manifest.'
    }
}
if ($Release) { Assert-ReleaseManifest (Join-Path $projectDirectory 'AndroidManifest.xml') }

# Missing release credentials must fail before generated files or development keys are created.
if ([string]::IsNullOrWhiteSpace($KeyStorePath)) { $KeyStorePath = $env:MONITOR_KEYSTORE }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { $KeyAlias = $env:MONITOR_KEY_ALIAS }
$externalSigning = -not [string]::IsNullOrWhiteSpace($KeyStorePath)
if ($Release -and -not $externalSigning) { throw 'Release signing requires -KeyStorePath or MONITOR_KEYSTORE; development signing is never a release fallback.' }
if ($externalSigning) {
    if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'External signing requires -KeyAlias or MONITOR_KEY_ALIAS.' }
    if ([string]::IsNullOrEmpty($env:MONITOR_KEYSTORE_PASSWORD) -or [string]::IsNullOrEmpty($env:MONITOR_KEY_PASSWORD)) {
        throw 'External signing requires MONITOR_KEYSTORE_PASSWORD and MONITOR_KEY_PASSWORD in the process environment.'
    }
    if (-not (Test-Path -LiteralPath $KeyStorePath -PathType Leaf)) { throw 'The supplied signing keystore must already exist.' }
    $signingFile = (Resolve-Path -LiteralPath $KeyStorePath).ProviderPath
    $signingArguments = @('--ks', $signingFile, '--ks-key-alias', $KeyAlias,
                         '--ks-pass', 'env:MONITOR_KEYSTORE_PASSWORD', '--key-pass', 'env:MONITOR_KEY_PASSWORD')
} else {
    if (-not [string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'A signing alias requires an existing -KeyStorePath or MONITOR_KEYSTORE.' }
    $signingFile = Join-Path $projectDirectory '.signing/debug.keystore'
    $signingArguments = @('--ks', $signingFile, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android')
}
. (Join-Path $projectDirectory 'xiaomi-build-config.ps1')
# Reject incomplete onboarding configuration before changing generated build outputs.
$null = Get-MonitorXiaomiConfiguration -AppId $XiaomiAppId -CertificateMode $XiaomiCertificateMode
$buildDirectory = Join-Path $projectDirectory 'build'
$toolsDirectory = Join-Path $AndroidSdk 'build-tools/36.1.0'
$platformJar = Join-Path $AndroidSdk 'platforms/android-36.1/android.jar'
$javaBin = Join-Path $JavaDirectory 'bin'
foreach ($javaTool in @('javac.exe', 'java.exe', 'jar.exe', 'keytool.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $javaBin $javaTool) -PathType Leaf)) { throw 'JavaDirectory / JAVA_HOME must point to a complete Windows JDK.' }
}
$qrLibrary = Join-Path $projectDirectory 'vendor/zxing-3.5.3/core-3.5.3.jar'
$qrLibraryHash = '8D8064C1636FDAEF7189DD9055C7D59950A8940A12F2293956446EC3C109FD82'
if (-not (Test-Path -LiteralPath $qrLibrary) -or (Get-FileHash -LiteralPath $qrLibrary -Algorithm SHA256).Hash -ne $qrLibraryHash) { throw 'ZXing 3.5.3 library is missing or its pinned SHA256 differs.' }
if (-not (Test-Path -LiteralPath $platformJar)) { throw 'Android 36.1 platform is required.' }
if (-not (Test-Path -LiteralPath (Join-Path $toolsDirectory 'aapt2.exe'))) { throw 'Android Build Tools 36.1.0 is required.' }
$env:JAVA_HOME = $JavaDirectory
function Run-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Executable (exit $LASTEXITCODE)" }
}
# Never package obsolete classes after a screen or anonymous class is removed.
# Clear only these generated directories; retain APK history, signing material and assets.
$generatedBuildRoot = [System.IO.Path]::GetFullPath($buildDirectory).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
if (Test-Path -LiteralPath $generatedBuildRoot) {
    $generatedRootItem = Get-Item -LiteralPath $generatedBuildRoot -Force
    if (($generatedRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Build directory must not be a reparse point.' }
    $resolvedBuildRoot = (Resolve-Path -LiteralPath $generatedBuildRoot).ProviderPath.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if (-not $resolvedBuildRoot.Equals($generatedBuildRoot, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected resolved build directory.' }
    foreach ($generatedName in @('classes', 'generated', 'dex')) {
        $generatedTarget = [System.IO.Path]::GetFullPath((Join-Path $generatedBuildRoot $generatedName))
        if (-not $generatedTarget.StartsWith($generatedBuildRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Generated cleanup target is outside the build directory.' }
        if (-not (Test-Path -LiteralPath $generatedTarget)) { continue }
        $generatedItem = Get-Item -LiteralPath $generatedTarget -Force
        if (-not $generatedItem.PSIsContainer -or ($generatedItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Generated cleanup target must be a normal directory.' }
        $resolvedGeneratedTarget = (Resolve-Path -LiteralPath $generatedTarget).ProviderPath
        if (-not $resolvedGeneratedTarget.Equals($generatedTarget, [System.StringComparison]::OrdinalIgnoreCase) -or -not $resolvedGeneratedTarget.StartsWith($resolvedBuildRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected resolved generated cleanup target.' }
        Remove-Item -LiteralPath $resolvedGeneratedTarget -Recurse -Force
    }
}
foreach ($relative in @('build', 'build/compiled', 'build/generated', 'build/classes', 'build/dex', 'build/policy-tests', 'build/notification-tests')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $projectDirectory $relative) | Out-Null
}
Run-Checked (Join-Path $toolsDirectory 'aapt2.exe') @('compile', '--dir', (Join-Path $projectDirectory 'res'), '-o', (Join-Path $buildDirectory 'resources.zip'))
$buildInputs = New-MonitorBuildInputs -SourceManifest (Join-Path $projectDirectory 'AndroidManifest.xml') -OutputDirectory (Join-Path $buildDirectory 'generated/xiaomi') -XiaomiAppId $XiaomiAppId -XiaomiCertificateMode $XiaomiCertificateMode
if ($Release) { Assert-ReleaseManifest $buildInputs.ManifestPath }
$linkArguments = @('link', '-o', (Join-Path $buildDirectory 'base.apk'), '-I', $platformJar, '--manifest', $buildInputs.ManifestPath, '--java', (Join-Path $buildDirectory 'generated'), '--min-sdk-version', '26', '--target-sdk-version', '36', (Join-Path $buildDirectory 'resources.zip'))
if ($buildInputs.XiaomiConfigured) {
    $xiaomiResources = Join-Path $buildDirectory 'xiaomi-resources.zip'
    Run-Checked (Join-Path $toolsDirectory 'aapt2.exe') @('compile', '--dir', $buildInputs.ResourceDirectory, '-o', $xiaomiResources)
    $linkArguments += $xiaomiResources
}
if (Test-Path -LiteralPath (Join-Path $projectDirectory 'assets')) { $linkArguments += @('-A', (Join-Path $projectDirectory 'assets')) }
Run-Checked (Join-Path $toolsDirectory 'aapt2.exe') $linkArguments
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'src'), (Join-Path $buildDirectory 'generated') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-classpath', "$platformJar;$qrLibrary", '-d', (Join-Path $buildDirectory 'classes')) + $sources)
Run-Checked (Join-Path $javaBin 'jar.exe') @('cf', (Join-Path $buildDirectory 'classes.jar'), '-C', (Join-Path $buildDirectory 'classes'), '.')
Run-Checked (Join-Path $toolsDirectory 'd8.bat') @('--lib', $platformJar, '--min-api', '26', '--output', (Join-Path $buildDirectory 'dex'), (Join-Path $buildDirectory 'classes.jar'), $qrLibrary)
Copy-Item -LiteralPath (Join-Path $buildDirectory 'base.apk') -Destination (Join-Path $buildDirectory 'unsigned.apk') -Force
Run-Checked (Join-Path $javaBin 'jar.exe') @('uf', (Join-Path $buildDirectory 'unsigned.apk'), '-C', (Join-Path $buildDirectory 'dex'), 'classes.dex')
# Windows aapt2 may write backslashes in asset ZIP entry names. Android AssetManager
# looks up slash-separated names, so normalize archive names before alignment/signing.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open((Join-Path $buildDirectory 'unsigned.apk'), [System.IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($entry in @($archive.Entries)) {
        if (-not $entry.FullName.Contains('\')) { continue }
        $canonicalName = $entry.FullName.Replace('\', '/')
        if ($null -ne $archive.GetEntry($canonicalName)) { throw "Duplicate archive entry after normalization: $canonicalName" }
        $replacement = $archive.CreateEntry($canonicalName, [System.IO.Compression.CompressionLevel]::Optimal)
        $inputStream = $entry.Open()
        $outputStream = $replacement.Open()
        try { $inputStream.CopyTo($outputStream) }
        finally { $inputStream.Dispose(); $outputStream.Dispose() }
        $entry.Delete()
    }
} finally { $archive.Dispose() }
Run-Checked (Join-Path $toolsDirectory 'zipalign.exe') @('-f', '4', (Join-Path $buildDirectory 'unsigned.apk'), (Join-Path $buildDirectory 'aligned.apk'))
if (-not $externalSigning -and -not (Test-Path -LiteralPath $signingFile)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $signingFile) | Out-Null
    Run-Checked (Join-Path $javaBin 'keytool.exe') @('-genkeypair', '-keystore', $signingFile, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=Monitor Local Test,O=Agent Monitor,C=CN')
}
$apk = Join-Path $buildDirectory 'monitor-live-test.apk'
Run-Checked (Join-Path $toolsDirectory 'apksigner.bat') (@('sign') + $signingArguments + @('--out', $apk, (Join-Path $buildDirectory 'aligned.apk')))
Run-Checked (Join-Path $toolsDirectory 'apksigner.bat') @('verify', '--verbose', $apk)
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $fontEntry = $archive.GetEntry('assets/fonts/MiSans-Regular.ttf')
    if ($null -eq $fontEntry) { throw 'APK is missing the exact AssetManager font path.' }
    if (@($archive.Entries | Where-Object { $_.FullName.Contains('\') }).Count -gt 0) { throw 'APK contains noncanonical asset entry names.' }
    $fontStream = $fontEntry.Open()
    $fontHasher = [System.Security.Cryptography.SHA256]::Create()
    try { $embeddedFontHash = [BitConverter]::ToString($fontHasher.ComputeHash($fontStream)).Replace('-', '') }
    finally { $fontStream.Dispose(); $fontHasher.Dispose() }
    $originalFontHash = (Get-FileHash -LiteralPath (Join-Path $projectDirectory 'assets/fonts/MiSans-Regular.ttf') -Algorithm SHA256).Hash
    if ($embeddedFontHash -ne $originalFontHash) { throw 'APK font bytes differ from the original font asset.' }
    Write-Output 'APK font: exact slash-separated entry and original bytes verified'
    $wheelFontEntry = $archive.GetEntry('res/font/misans_regular.ttf')
    if ($null -eq $wheelFontEntry) { throw 'APK is missing the native wheel font resource.' }
    $wheelFontStream = $wheelFontEntry.Open()
    $wheelFontHasher = [System.Security.Cryptography.SHA256]::Create()
    try { $wheelFontHash = [BitConverter]::ToString($wheelFontHasher.ComputeHash($wheelFontStream)).Replace('-', '') }
    finally { $wheelFontStream.Dispose(); $wheelFontHasher.Dispose() }
    if ($wheelFontHash -ne $originalFontHash) { throw 'Native wheel font resource differs from the original MiSans asset.' }
    Write-Output 'Native wheel MiSans: original font resource bytes verified'
} finally { $archive.Dispose() }
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/TrackingPolicy.java'), (Join-Path $projectDirectory 'tests/TrackingPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.TrackingPolicyTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/PairingGate.java'), (Join-Path $projectDirectory 'tests/PairingGateTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.PairingGateTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/DeviceBrand.java'), (Join-Path $projectDirectory 'tests/DeviceBrandTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.DeviceBrandTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-classpath', $platformJar, '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/XiaomiLiveCapabilities.java'), (Join-Path $projectDirectory 'tests/XiaomiLiveCapabilitiesTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', "$(Join-Path $buildDirectory 'policy-tests');$platformJar", 'com.agentmonitor.live.XiaomiLiveCapabilitiesTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativePairingStage.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/PairingGate.java'), (Join-Path $projectDirectory 'tests/NativeWebPolicyTest.java'), (Join-Path $projectDirectory 'tests/NativePairingStageTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.NativeWebPolicyTest')
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.NativePairingStageTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/WebLoadPolicy.java'), (Join-Path $projectDirectory 'tests/WebLoadPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.WebLoadPolicyTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeNavigationPolicy.java'), (Join-Path $projectDirectory 'tests/NativeNavigationPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.NativeNavigationPolicyTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NotificationRecoveryPolicy.java'), (Join-Path $projectDirectory 'tests/NotificationRecoveryPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.NotificationRecoveryPolicyTest')
# Compile the real notification/receiver implementation against offline recording stubs.
# These classes stay outside build/classes and therefore never enter the APK.
$notificationTestSources = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'tests/notification-stubs') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
foreach ($source in @('TrackingService', 'TrackingRequestDiagnostics', 'TrackingRemovalListener', 'NotificationRecoveryPolicy', 'StopReceiver', 'DismissReceiver', 'TrackingPolicy', 'NativeNavigationPolicy', 'NativeWebPolicy', 'LivePresentation', 'LivePresentationPreferences', 'UsageAlertNotification', 'DeviceBrand', 'XiaomiLiveCapabilities', 'XiaomiLiveNotification')) {
    $notificationTestSources += Join-Path $projectDirectory "src/com/agentmonitor/live/$source.java"
}
$notificationTestSources += Join-Path $projectDirectory 'tests/TrackingNotificationTest.java'
$notificationTestSources += Join-Path $projectDirectory 'tests/LivePresentationTest.java'
$notificationTestSources += Join-Path $projectDirectory 'tests/UsageAlertNotificationTest.java'
$notificationTestSources += Join-Path $projectDirectory 'tests/XiaomiLiveNotificationTest.java'
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-d', (Join-Path $buildDirectory 'notification-tests')) + $notificationTestSources)
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'notification-tests'), 'com.agentmonitor.live.TrackingNotificationTest')
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'notification-tests'), 'com.agentmonitor.live.LivePresentationTest')
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'notification-tests'), 'com.agentmonitor.live.UsageAlertNotificationTest')
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'notification-tests'), 'com.agentmonitor.live.XiaomiLiveNotificationTest')
# Private diagnostics use an independent platform double; never package these classes.
$diagnosticsTestsDirectory = Join-Path $buildDirectory 'diagnostics-tests'
New-Item -ItemType Directory -Force -Path $diagnosticsTestsDirectory | Out-Null
$diagnosticsTestSources = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'tests/diagnostics-stubs') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
foreach ($source in @('NotificationDiagnostics', 'XiaomiOnboarding')) {
    $diagnosticsTestSources += Join-Path $projectDirectory "src/com/agentmonitor/live/$source.java"
    $diagnosticsTestSources += Join-Path $projectDirectory "tests/$($source)Test.java"
}
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-d', $diagnosticsTestsDirectory) + $diagnosticsTestSources)
foreach ($test in @('NotificationDiagnosticsTest', 'XiaomiOnboardingTest')) {
    Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $diagnosticsTestsDirectory, "com.agentmonitor.live.$test")
}
foreach ($policy in @('NativeUsageFormat', 'UsageAlertPolicy', 'NativeUsagePresentation')) {
    Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-classpath', (Join-Path $buildDirectory 'policy-tests'), '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory "src/com/agentmonitor/live/$policy.java"), (Join-Path $projectDirectory "tests/$($policy)Test.java"))
    Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), "com.agentmonitor.live.$($policy)Test")
}
Write-Output "APK: $apk"
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-classpath', $qrLibrary, '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeQrDecoder.java'), (Join-Path $projectDirectory 'tests/NativeQrDecoderTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', "$(Join-Path $buildDirectory 'policy-tests');$qrLibrary", 'com.agentmonitor.live.NativeQrDecoderTest')
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Select-Object Hash



# Native screens and request gates use production code; no test stubs enter the APK.
$apiTestsDirectory = Join-Path $buildDirectory 'native-api-tests'
$connectionTestsDirectory = Join-Path $buildDirectory 'connection-tests'
New-Item -ItemType Directory -Force -Path $apiTestsDirectory, $connectionTestsDirectory | Out-Null
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-classpath', $platformJar, '-d', $apiTestsDirectory, (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeApi.java'), (Join-Path $projectDirectory 'tests/NativeApiRequestTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', "$apiTestsDirectory;$platformJar", 'com.agentmonitor.live.NativeApiRequestTest')
# Exercise late result delivery against the production refresh state machine.
$resultTestsDirectory = Join-Path $buildDirectory 'result-tests'
New-Item -ItemType Directory -Force -Path $resultTestsDirectory | Out-Null
$resultTestClasspath = "$(Join-Path $buildDirectory 'classes');$platformJar"
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-classpath', $resultTestClasspath, '-d', $resultTestsDirectory, (Join-Path $projectDirectory 'tests/NativeTaskResultsRefreshTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', "$resultTestsDirectory;$resultTestClasspath", 'com.agentmonitor.live.NativeTaskResultsRefreshTest')
# Isolated recording stubs exercise the production JSON transport without any network.
$transportTestsDirectory = Join-Path $buildDirectory 'transport-tests'
New-Item -ItemType Directory -Force -Path $transportTestsDirectory | Out-Null
$transportTestSources = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'tests/transport-stubs') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
foreach ($source in @('NativeWebPolicy', 'NativeApi')) { $transportTestSources += Join-Path $projectDirectory "src/com/agentmonitor/live/$source.java" }
$transportTestSources += Join-Path $projectDirectory 'tests/NativeApiTransportTest.java'
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-d', $transportTestsDirectory) + $transportTestSources)
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $transportTestsDirectory, 'com.agentmonitor.live.NativeApiTransportTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/TrackingRequestDiagnostics.java'), (Join-Path $projectDirectory 'tests/TrackingRequestDiagnosticsTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.TrackingRequestDiagnosticsTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeScreenPolicy.java'), (Join-Path $projectDirectory 'tests/NativeScreenPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.NativeScreenPolicyTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', (Join-Path $buildDirectory 'policy-tests'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeScreenPolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWorkbenchPolicy.java'), (Join-Path $projectDirectory 'tests/NativeWorkbenchPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', (Join-Path $buildDirectory 'policy-tests'), 'com.agentmonitor.live.NativeWorkbenchPolicyTest')
$connectionTestSources = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'tests/connection-stubs') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
foreach ($source in @('PairingGate', 'NativeConnection')) { $connectionTestSources += Join-Path $projectDirectory "src/com/agentmonitor/live/$source.java" }
$connectionTestSources += Join-Path $projectDirectory 'tests/NativeConnectionTest.java'
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-d', $connectionTestsDirectory) + $connectionTestSources)
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $connectionTestsDirectory, 'com.agentmonitor.live.NativeConnectionTest')

# Content preview uses only synthetic data. Isolated doubles must never enter the APK.
$previewTestsDirectory = Join-Path $buildDirectory 'live-preview-tests'
New-Item -ItemType Directory -Force -Path $previewTestsDirectory | Out-Null
$previewTestSources = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'tests/live-preview-stubs') -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
foreach ($jsonSource in @('JSONObject', 'JSONArray')) { $previewTestSources += Join-Path $projectDirectory "tests/notification-stubs/org/json/$jsonSource.java" }
foreach ($previewSource in @('LivePresentation', 'LivePresentationPreferences', 'LivePreviewModel')) { $previewTestSources += Join-Path $projectDirectory "src/com/agentmonitor/live/$previewSource.java" }
$previewTestSources += Join-Path $projectDirectory 'tests/LivePreviewModelTest.java'
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-d', $previewTestsDirectory) + $previewTestSources)
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $previewTestsDirectory, 'com.agentmonitor.live.LivePreviewModelTest')

# Update URL/manifest policy is pure Java; these test classes never enter the APK.
$updateTestsDirectory = Join-Path $buildDirectory 'update-policy-tests'
New-Item -ItemType Directory -Force -Path $updateTestsDirectory | Out-Null
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', $updateTestsDirectory, (Join-Path $projectDirectory 'src/com/agentmonitor/live/UpdatePolicy.java'), (Join-Path $projectDirectory 'tests/UpdatePolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $updateTestsDirectory, 'com.agentmonitor.live.UpdatePolicyTest')
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', $updateTestsDirectory, (Join-Path $projectDirectory 'src/com/agentmonitor/live/UpdatePolicy.java'), (Join-Path $projectDirectory 'src/com/agentmonitor/live/UpdateRecoveryPolicy.java'), (Join-Path $projectDirectory 'tests/UpdateRecoveryPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $updateTestsDirectory, 'com.agentmonitor.live.UpdateRecoveryPolicyTest', $updateTestsDirectory)

# Origin configuration uses the same pure policy as native navigation.
$originTestsDirectory = Join-Path $buildDirectory 'server-origin-tests'
New-Item -ItemType Directory -Force -Path $originTestsDirectory | Out-Null
Run-Checked (Join-Path $javaBin 'javac.exe') @('-encoding', 'UTF-8', '--release', '8', '-d', $originTestsDirectory, (Join-Path $projectDirectory 'src/com/agentmonitor/live/NativeWebPolicy.java'), (Join-Path $projectDirectory 'tests/ServerOriginTest.java'))
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', $originTestsDirectory, 'com.agentmonitor.live.ServerOriginTest')

# Production update I/O runs against an isolated cache Context and socket-free HTTPS fixtures.
# Only this test directory precedes android.jar; its platform double never enters the APK.
$updateClientTestsDirectory = Join-Path $buildDirectory 'update-client-tests'
New-Item -ItemType Directory -Force -Path $updateClientTestsDirectory | Out-Null
$updateClientTestSources = @(
    (Join-Path $projectDirectory 'tests/update-stubs/android/content/Context.java'),
    (Join-Path $projectDirectory 'src/com/agentmonitor/live/UpdatePolicy.java'),
    (Join-Path $projectDirectory 'src/com/agentmonitor/live/UpdateClient.java'),
    (Join-Path $projectDirectory 'tests/UpdateClientTest.java')
)
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-classpath', $platformJar, '-d', $updateClientTestsDirectory) + $updateClientTestSources)
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', "$updateClientTestsDirectory;$platformJar", 'com.agentmonitor.live.UpdateClientTest', $updateClientTestsDirectory)

# Startup origin migration cannot reuse unbound credentials on a new server.
$settingsTestsDirectory = Join-Path $buildDirectory 'server-settings-tests'
New-Item -ItemType Directory -Force -Path $settingsTestsDirectory | Out-Null
$settingsSources = @((Join-Path $projectDirectory 'tests/notification-stubs/org/json/JSONObject.java'), (Join-Path $projectDirectory 'tests/notification-stubs/org/json/JSONArray.java'), (Join-Path $projectDirectory 'tests/ServerSettingsPolicyTest.java'))
Run-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-classpath', "$(Join-Path $buildDirectory 'classes');$platformJar", '-d', $settingsTestsDirectory) + $settingsSources)
Run-Checked (Join-Path $javaBin 'java.exe') @('-cp', "$settingsTestsDirectory;$(Join-Path $buildDirectory 'classes');$platformJar", 'com.agentmonitor.live.ServerSettingsPolicyTest')
