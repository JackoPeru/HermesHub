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

function Test-MsixIdentity(
    [string]$Path,
    [string]$ExpectedVersion,
    [string]$ExpectedArchitecture
) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = $null
    $reader = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        $manifestEntry = $archive.Entries |
            Where-Object { $_.FullName -eq "AppxManifest.xml" } |
            Select-Object -First 1
        if (-not $manifestEntry) {
            throw "AppxManifest.xml assente dal pacchetto MSIX."
        }

        $reader = [System.IO.StreamReader]::new($manifestEntry.Open(), [System.Text.Encoding]::UTF8, $true)
        [xml]$packageManifest = $reader.ReadToEnd()
        $identity = $packageManifest.Package.Identity
        if (-not $identity) {
            throw "Identity assente da AppxManifest.xml."
        }
        if ($identity.Name -ne "2D8AF35F-8122-4C3B-8487-0201987F4E61") {
            throw "Package identity inattesa: $($identity.Name)."
        }
        if ($identity.Publisher -ne "CN=AppPublisher") {
            throw "Publisher MSIX inatteso: $($identity.Publisher)."
        }
        if ($identity.Version -ne $ExpectedVersion) {
            throw "Versione MSIX inattesa: $($identity.Version), attesa $ExpectedVersion."
        }
        if ($identity.ProcessorArchitecture -ne $ExpectedArchitecture) {
            throw "Architettura MSIX inattesa: $($identity.ProcessorArchitecture), attesa $ExpectedArchitecture."
        }
    }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($archive) { $archive.Dispose() }
    }
}

[xml]$project = Get-Content -LiteralPath $csprojPath
$projectVersion = $project.Project.PropertyGroup.Version | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = $projectVersion
}
$packageVersion = Get-PackageVersion $Version
$projectPackageVersion = Get-PackageVersion $projectVersion
if ($packageVersion -ne $projectPackageVersion) {
    throw "Versione richiesta $packageVersion diversa dal progetto $projectPackageVersion. Aggiorna prima csproj, Android e release metadata."
}

$originalManifestText = Get-Content -LiteralPath $manifestPath -Raw
$manifestText = $originalManifestText
$identityVersion = [regex]'(<Identity\b[^>]*\bVersion=")[^"]+(")'
if (-not $identityVersion.IsMatch($manifestText)) {
    throw "Versione Identity non trovata in Package.appxmanifest."
}
$manifestText = $identityVersion.Replace($manifestText, {
    param($match)
    $match.Groups[1].Value + $packageVersion + $match.Groups[2].Value
}, 1)
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
try {
    [System.IO.File]::WriteAllText($manifestPath, $manifestText, [System.Text.UTF8Encoding]::new($false))

    $appPackages = Join-Path $projectDir "AppPackages"
    if (Test-Path -LiteralPath $appPackages) {
        Get-ChildItem -Path $appPackages -Recurse -Filter "*.msix" |
            Where-Object { $_.Name -like "*$packageVersion*" -and $_.Name -like "*$Platform*" } |
            Remove-Item -Force
    }
    $buildStartedUtc = [DateTime]::UtcNow

    & dotnet publish $csprojPath `
        -c $Configuration `
        -p:Platform=$Platform `
        -p:PublishProfile= `
        -p:WindowsPackageType=MSIX `
        -p:GenerateAppxPackageOnBuild=true `
        -p:AppxSymbolPackageEnabled=false `
        -p:DebugSymbols=false `
        -p:DebugType=None `
        -p:AppxPackageSigningEnabled=false
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish fallito con exit code $LASTEXITCODE."
    }

    $msixCandidates = @(Get-ChildItem -Path $appPackages -Recurse -Filter "*.msix" |
        Where-Object {
            $_.Name -like "*$packageVersion*" -and
            $_.Name -like "*$Platform*" -and
            $_.LastWriteTimeUtc -ge $buildStartedUtc.AddSeconds(-2)
        } |
        Sort-Object LastWriteTimeUtc -Descending)

    if ($msixCandidates.Count -ne 1) {
        throw "Atteso un solo pacchetto MSIX nuovo in $appPackages, trovati $($msixCandidates.Count)."
    }
    $msix = $msixCandidates[0]
    if ($msix.Length -le 0) { throw "Pacchetto MSIX vuoto: $($msix.FullName)." }
    Test-MsixIdentity -Path $msix.FullName -ExpectedVersion $packageVersion -ExpectedArchitecture $Platform

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

        $lmRootCert = Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
        if (-not $lmRootCert) {
            Write-Output "Richiesta privilegi per installare il certificato in LocalMachine\Root (necessario per App Installer)..."
            $importProcess = Start-Process powershell.exe -ArgumentList "-NoProfile -WindowStyle Hidden -Command `"Import-Certificate -FilePath '$certPath' -CertStoreLocation Cert:\LocalMachine\Root`"" -Verb RunAs -Wait -PassThru
            if ($importProcess.ExitCode -ne 0) {
                throw "Import certificato LocalMachine\\Root fallito o annullato (exit code $($importProcess.ExitCode))."
            }
            $lmRootCert = Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
            if (-not $lmRootCert) {
                throw "Certificato non presente in LocalMachine\\Root dopo l'import."
            }
        }

        $signTool = Get-SignTool
        & $signTool sign /fd SHA256 /sha1 $cert.Thumbprint /tr http://timestamp.digicert.com /td SHA256 $msix.FullName
        if ($LASTEXITCODE -ne 0) {
            & $signTool sign /fd SHA256 /sha1 $cert.Thumbprint /tr http://timestamp.sectigo.com /td SHA256 $msix.FullName
            if ($LASTEXITCODE -ne 0) {
                throw "Firma MSIX con timestamp fallita."
            }
        }
        & $signTool verify /pa /all $msix.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "Verifica Authenticode MSIX fallita con exit code $LASTEXITCODE."
        }
    }

    # Signing changes the file size after Get-ChildItem created $msix. Re-read it
    # instead of comparing the copied asset with the stale FileInfo.Length value.
    $sourceLength = (Get-Item -LiteralPath $msix.FullName).Length
    $target = Join-Path $releaseDir $msix.Name
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
    Copy-Item -LiteralPath $msix.FullName -Destination $target
    if (-not (Test-Path -LiteralPath $target -PathType Leaf) -or (Get-Item -LiteralPath $target).Length -ne $sourceLength) {
        throw "Copia dell'asset MSIX incompleta: $target"
    }

    Write-Output "MSIX pronto: $target"
    if (-not $SkipSigning) {
        Write-Output "Cert installato in CurrentUser\\TrustedPeople e CurrentUser\\Root: CN=AppPublisher"
    }
}
finally {
    [System.IO.File]::WriteAllText($manifestPath, $originalManifestText, [System.Text.UTF8Encoding]::new($false))
}
