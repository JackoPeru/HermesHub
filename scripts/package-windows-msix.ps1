param(
    [string]$Configuration = "Release",
    [ValidateSet("x86", "x64", "ARM64")]
    [string]$Platform = "x64",
    [string]$Version = "",
    [switch]$SkipSigning
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$projectDir = Join-Path $root "src\NemoclawChat.Windows"
$csprojPath = Join-Path $projectDir "NemoclawChat.Windows.csproj"
$manifestPath = Join-Path $projectDir "Package.appxmanifest"
$releaseDir = Join-Path $root "release-assets\windows"

function Get-SignTool {
    $cmd = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $kitRoot = "C:\Program Files (x86)\Windows Kits\10\bin"
    $match = Get-ChildItem -Path $kitRoot -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\x64\\signtool\.exe$" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($match) { return $match.FullName }

    throw "signtool.exe non trovato. Installa Windows SDK."
}

function Get-PackageVersion([string]$value) {
    $clean = $value.Trim().TrimStart("v", "V")
    if ($clean -match "^\d+\.\d+\.\d+$") { return "$clean.0" }
    if ($clean -match "^\d+\.\d+\.\d+\.\d+$") { return $clean }
    throw "Versione non valida: $value. Usa X.Y.Z o X.Y.Z.W."
}

[xml]$project = Get-Content -LiteralPath $csprojPath
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = $project.Project.PropertyGroup.Version | Select-Object -First 1
}
$packageVersion = Get-PackageVersion $Version

$manifestText = Get-Content -LiteralPath $manifestPath -Raw
$identityVersion = [regex]'(<Identity\b[^>]*\bVersion=")[^"]+(")'
if (-not $identityVersion.IsMatch($manifestText)) {
    throw "Versione Identity non trovata in Package.appxmanifest."
}
$manifestText = $identityVersion.Replace($manifestText, {
    param($match)
    $match.Groups[1].Value + $packageVersion + $match.Groups[2].Value
}, 1)
[System.IO.File]::WriteAllText($manifestPath, $manifestText, [System.Text.UTF8Encoding]::new($false))

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

dotnet publish $csprojPath `
    -c $Configuration `
    -p:Platform=$Platform `
    -p:PublishProfile= `
    -p:WindowsPackageType=MSIX `
    -p:GenerateAppxPackageOnBuild=true `
    -p:AppxPackageSigningEnabled=false

$appPackages = Join-Path $projectDir "AppPackages"
$msix = Get-ChildItem -Path $appPackages -Recurse -Filter "*.msix" |
    Where-Object { $_.Name -like "*$packageVersion*" -and $_.Name -like "*$Platform*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $msix) {
    throw "Pacchetto MSIX non trovato in $appPackages."
}

if (-not $SkipSigning) {
    $subject = "CN=AppPublisher"
    $cert = Get-ChildItem Cert:\CurrentUser\My |
        Where-Object { $_.Subject -eq $subject -and $_.NotAfter -gt (Get-Date).AddMonths(1) } |
        Sort-Object NotAfter -Descending |
        Select-Object -First 1

    if (-not $cert) {
        $cert = New-SelfSignedCertificate `
            -Type CodeSigningCert `
            -Subject $subject `
            -CertStoreLocation Cert:\CurrentUser\My `
            -NotAfter (Get-Date).AddYears(5)
    }

    $certPath = Join-Path $releaseDir "HermesHub-AppPublisher.cer"
    Export-Certificate -Cert $cert -FilePath $certPath | Out-Null
    Import-Certificate -FilePath $certPath -CertStoreLocation Cert:\CurrentUser\TrustedPeople | Out-Null
    Import-Certificate -FilePath $certPath -CertStoreLocation Cert:\CurrentUser\Root | Out-Null

    $signTool = Get-SignTool
    & $signTool sign /fd SHA256 /sha1 $cert.Thumbprint /tr http://timestamp.digicert.com /td SHA256 $msix.FullName
    if ($LASTEXITCODE -ne 0) {
        & $signTool sign /fd SHA256 /sha1 $cert.Thumbprint $msix.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "Firma MSIX fallita."
        }
    }
}

$target = Join-Path $releaseDir $msix.Name
Copy-Item -LiteralPath $msix.FullName -Destination $target -Force

Write-Host "MSIX pronto: $target"
if (-not $SkipSigning) {
    Write-Host "Cert installato in CurrentUser\\TrustedPeople e CurrentUser\\Root: CN=AppPublisher"
}
