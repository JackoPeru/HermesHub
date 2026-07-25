param(
    [string]$Version = "",
    [string]$OutputDirectory = "artifacts",
    [switch]$CiValidation
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root "src\NemoclawChat.Android"
$appDir = Join-Path $androidDir "app"
$gradleBuildPath = Join-Path $appDir "build.gradle.kts"
$localPropertiesPath = Join-Path $androidDir "local.properties"
$apkPath = Join-Path $appDir "build\outputs\apk\release\app-release.apk"
$mappingPath = Join-Path $appDir "build\outputs\mapping\release\mapping.txt"
$buildConfigPath = Join-Path $appDir "build\generated\source\buildConfig\release\com\nemoclaw\chat\BuildConfig.java"
$expectedCertificateSha256 = "7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f"

function Get-LocalPropertyValue([string]$Path, [string]$Key) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ""
    }
    $value = ""
    $pattern = "^\s*" + [regex]::Escape($Key) + "\s*=\s*(.*)$"
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match $pattern) {
            $value = $Matches[1].Trim()
        }
    }
    return $value
}

function Resolve-AndroidSdkRoot {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    if ($env:OS -eq "Windows_NT" -and -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    throw "Android SDK non trovato. Configura ANDROID_HOME o ANDROID_SDK_ROOT."
}

function Resolve-AndroidTool(
    [string[]]$CommandNames,
    [string[]]$CandidatePaths,
    [string]$Label
) {
    foreach ($name in $CommandNames) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    foreach ($path in $CandidatePaths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            return $path
        }
    }
    throw "$Label non trovato nell'Android SDK."
}

function Assert-MetaManifestValue(
    [string]$ManifestText,
    [string]$MetadataName,
    [string]$ExpectedValue
) {
    $position = $ManifestText.IndexOf($MetadataName, [System.StringComparison]::Ordinal)
    if ($position -lt 0) {
        throw "Metadata DAT assente dal manifest compilato: $MetadataName."
    }
    $nextMetadata = $ManifestText.IndexOf("E: meta-data", $position + $MetadataName.Length, [System.StringComparison]::Ordinal)
    $length = if ($nextMetadata -gt $position) {
        $nextMetadata - $position
    }
    else {
        [Math]::Min(1600, $ManifestText.Length - $position)
    }
    $segment = $ManifestText.Substring($position, $length)
    if ($segment.IndexOf("android:value", [System.StringComparison]::Ordinal) -lt 0 -or
        $segment.IndexOf($ExpectedValue, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Valore DAT inatteso nel manifest compilato: $MetadataName."
    }
}

$gradleBuild = Get-Content -LiteralPath $gradleBuildPath -Raw
$versionNameMatch = [regex]::Match($gradleBuild, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
$versionCodeMatch = [regex]::Match($gradleBuild, 'versionCode\s*=\s*([0-9]+)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    throw "versionName/versionCode non trovati in $gradleBuildPath."
}
$projectVersion = $versionNameMatch.Groups[1].Value
$projectVersionCode = $versionCodeMatch.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = $projectVersion
}
$Version = $Version.Trim().TrimStart("v", "V")
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "Versione non valida: $Version. Usa X.Y.Z."
}
if ($Version -ne $projectVersion) {
    throw "Versione richiesta $Version diversa dal progetto $projectVersion."
}

$githubToken = $env:GITHUB_TOKEN
if ([string]::IsNullOrWhiteSpace($githubToken)) {
    $githubToken = Get-LocalPropertyValue -Path $localPropertiesPath -Key "githubPackagesToken"
}
if ([string]::IsNullOrWhiteSpace($githubToken)) {
    throw "Release Android bloccata: configura GITHUB_TOKEN o githubPackagesToken in local.properties con scope read:packages. Non verra prodotto un APK senza DAT."
}

if ($CiValidation) {
    $metaApplicationId = "123456"
    $metaClientToken = "ci-validation-token-not-for-release"
}
else {
    $metaApplicationId = $env:META_DAT_APPLICATION_ID
    if ([string]::IsNullOrWhiteSpace($metaApplicationId)) {
        $metaApplicationId = Get-LocalPropertyValue -Path $localPropertiesPath -Key "mwdatApplicationId"
    }
    $metaClientToken = $env:META_DAT_CLIENT_TOKEN
    if ([string]::IsNullOrWhiteSpace($metaClientToken)) {
        $metaClientToken = Get-LocalPropertyValue -Path $localPropertiesPath -Key "mwdatClientToken"
    }
    if ([string]::IsNullOrWhiteSpace($metaApplicationId) -or $metaApplicationId -eq "0" -or $metaApplicationId -notmatch '^[0-9]{5,}$') {
        throw "Release Android bloccata: META_DAT_APPLICATION_ID/mwdatApplicationId assente o placeholder."
    }
    if ([string]::IsNullOrWhiteSpace($metaClientToken) -or $metaClientToken -eq "0" -or $metaClientToken.Length -lt 8) {
        throw "Release Android bloccata: META_DAT_CLIENT_TOKEN/mwdatClientToken assente o placeholder."
    }
}

$sdkRoot = Resolve-AndroidSdkRoot
$buildToolsRoot = Join-Path $sdkRoot "build-tools"
$latestBuildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $latestBuildTools) {
    throw "Android build-tools non trovati in $buildToolsRoot."
}
$runningOnWindows = $env:OS -eq "Windows_NT"
$aapt2 = Resolve-AndroidTool `
    -CommandNames @($(if ($runningOnWindows) { "aapt2.exe" } else { "aapt2" })) `
    -CandidatePaths @((Join-Path $latestBuildTools.FullName $(if ($runningOnWindows) { "aapt2.exe" } else { "aapt2" }))) `
    -Label "aapt2"
$apksigner = Resolve-AndroidTool `
    -CommandNames @($(if ($runningOnWindows) { "apksigner.bat" } else { "apksigner" })) `
    -CandidatePaths @((Join-Path $latestBuildTools.FullName $(if ($runningOnWindows) { "apksigner.bat" } else { "apksigner" }))) `
    -Label "apksigner"
$apkAnalyzerCandidates = @(
    (Join-Path $sdkRoot $(if ($runningOnWindows) { "cmdline-tools\latest\bin\apkanalyzer.bat" } else { "cmdline-tools/latest/bin/apkanalyzer" }))
)
$apkAnalyzer = Resolve-AndroidTool `
    -CommandNames @($(if ($runningOnWindows) { "apkanalyzer.bat" } else { "apkanalyzer" })) `
    -CandidatePaths $apkAnalyzerCandidates `
    -Label "apkanalyzer"

$gradleCommand = Join-Path $androidDir $(if ($runningOnWindows) { "gradlew.bat" } else { "gradlew" })
if (-not (Test-Path -LiteralPath $gradleCommand -PathType Leaf)) {
    throw "Gradle wrapper non trovato: $gradleCommand."
}

$oldApplicationId = [Environment]::GetEnvironmentVariable("ORG_GRADLE_PROJECT_mwdatApplicationId", "Process")
$oldClientToken = [Environment]::GetEnvironmentVariable("ORG_GRADLE_PROJECT_mwdatClientToken", "Process")
try {
    [Environment]::SetEnvironmentVariable("ORG_GRADLE_PROJECT_mwdatApplicationId", $metaApplicationId, "Process")
    [Environment]::SetEnvironmentVariable("ORG_GRADLE_PROJECT_mwdatClientToken", $metaClientToken, "Process")
    Push-Location $androidDir
    try {
        & $gradleCommand -PenableMetaDat=true --no-daemon --console=plain lintRelease testDebugUnitTest assembleRelease
        if ($LASTEXITCODE -ne 0) {
            throw "Build Android DAT fallita con exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    [Environment]::SetEnvironmentVariable("ORG_GRADLE_PROJECT_mwdatApplicationId", $oldApplicationId, "Process")
    [Environment]::SetEnvironmentVariable("ORG_GRADLE_PROJECT_mwdatClientToken", $oldClientToken, "Process")
}

foreach ($requiredFile in @($apkPath, $mappingPath, $buildConfigPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf) -or (Get-Item -LiteralPath $requiredFile).Length -le 0) {
        throw "Output DAT obbligatorio assente o vuoto: $requiredFile."
    }
}

$buildConfig = Get-Content -LiteralPath $buildConfigPath -Raw
if ($buildConfig -notmatch 'META_DAT_ENABLED\s*=\s*true;') {
    throw "BuildConfig non conferma META_DAT_ENABLED=true."
}
$mapping = Get-Content -LiteralPath $mappingPath -Raw
foreach ($className in @(
    "com.nemoclaw.chat.jarvis.meta.MetaWearablesFrameSource",
    "com.nemoclaw.chat.jarvis.meta.MetaWearablesSetupBridgeImpl"
)) {
    if ($mapping.IndexOf($className + " ->", [System.StringComparison]::Ordinal) -lt 0) {
        throw "R8 ha rimosso l'entrypoint DAT: $className."
    }
}

$badgingOutput = (& $aapt2 dump badging $apkPath 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 non riesce a leggere l'APK DAT."
}
if ($badgingOutput -notmatch "package: name='com\.nemoclaw\.chat'") {
    throw "Application ID APK inatteso."
}
if ($badgingOutput -notmatch ("versionCode='" + [regex]::Escape($projectVersionCode) + "'")) {
    throw "versionCode APK inatteso; atteso $projectVersionCode."
}
if ($badgingOutput -notmatch ("versionName='" + [regex]::Escape($Version) + "'")) {
    throw "versionName APK inatteso; atteso $Version."
}
if ($badgingOutput -notmatch "minSdkVersion:'29'") {
    throw "APK ufficiale senza minSdk 29: la variante DAT non e stata prodotta."
}

$dexPackages = (& $apkAnalyzer dex packages --defined-only $apkPath 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "apkanalyzer non riesce a ispezionare il DEX dell'APK."
}
foreach ($className in @(
    "com.nemoclaw.chat.jarvis.meta.MetaWearablesFrameSource",
    "com.nemoclaw.chat.jarvis.meta.MetaWearablesSetupBridgeImpl"
)) {
    if ($dexPackages -notmatch ("(?m)^C .*" + [regex]::Escape($className) + "\s*$")) {
        throw "Classe DAT assente dall'APK finale: $className."
    }
}

$manifestOutput = (& $aapt2 dump xmltree --file AndroidManifest.xml $apkPath 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 non riesce a ispezionare AndroidManifest.xml."
}
Assert-MetaManifestValue `
    -ManifestText $manifestOutput `
    -MetadataName "com.meta.wearable.mwdat.APPLICATION_ID" `
    -ExpectedValue $metaApplicationId
Assert-MetaManifestValue `
    -ManifestText $manifestOutput `
    -MetadataName "com.meta.wearable.mwdat.CLIENT_TOKEN" `
    -ExpectedValue $metaClientToken

$signatureOutput = (& $apksigner verify --verbose --print-certs $apkPath 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $signatureOutput -notmatch 'Verified using v2 scheme \(APK Signature Scheme v2\): true') {
    throw "Firma APK v2 non valida."
}
$certificateMatch = [regex]::Match($signatureOutput, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
if (-not $certificateMatch.Success) {
    throw "Digest SHA-256 del certificato APK non trovato."
}
if (-not $CiValidation -and $certificateMatch.Groups[1].Value.ToLowerInvariant() -ne $expectedCertificateSha256) {
    throw "Certificato APK diverso dalla firma storica Hermes Hub."
}
if ($CiValidation) {
    Write-Warning "CI validation: firma v2 valida; digest storico non richiesto per artefatto non pubblicabile."
}

$outputRoot = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    [System.IO.Path]::GetFullPath($OutputDirectory)
}
else {
    [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$assetName = if ($CiValidation) {
    "HermesHub-$Version-android-DAT-validation-only.apk"
}
else {
    "HermesHub-$Version-android.apk"
}
$target = Join-Path $outputRoot $assetName
$partial = "$target.partial"
if (Test-Path -LiteralPath $partial) {
    Remove-Item -LiteralPath $partial -Force
}
Copy-Item -LiteralPath $apkPath -Destination $partial
$sourceHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$partialHash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne $partialHash) {
    throw "Copia APK incompleta: hash sorgente e staging diversi."
}
Move-Item -LiteralPath $partial -Destination $target -Force

if ($CiValidation) {
    Write-Output "APK DAT CI validation-only pronto: $target"
}
else {
    Write-Output "APK DAT ufficiale pronto: $target"
}
Write-Output "SHA-256: $sourceHash"
Write-Output "DAT: classi, BuildConfig, minSdk 29, credenziali Meta e firma storica verificati."
if ($CiValidation) {
    Write-Output "ATTENZIONE: asset CI validation-only, non pubblicabile in una release GitHub."
}
