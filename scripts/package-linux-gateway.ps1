param(
    [string]$Version,
    [string]$OutputDirectory = "artifacts"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$archiveBuilder = Join-Path $PSScriptRoot "create-linux-gateway-archive.py"

if ([string]::IsNullOrWhiteSpace($Version)) {
    $adminProject = Join-Path $root "src\ChatClaw.AdminBridge\ChatClaw.AdminBridge.csproj"
    [xml]$project = Get-Content -LiteralPath $adminProject
    $Version = $project.Project.PropertyGroup.Version
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "Version not found. Pass -Version X.Y.Z."
}
$Version = $Version.Trim()
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?$') {
    throw "Invalid version '$Version'. Expected X.Y.Z or X.Y.Z.W using decimal components only."
}

$outDir = [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
$stageRoot = Join-Path $outDir ("linux-gateway-stage-" + [Guid]::NewGuid().ToString("N"))
$stageScripts = Join-Path $stageRoot "scripts"
$archive = Join-Path $outDir "HermesHub-$Version-linux-gateway.tar.gz"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$resolvedStage = [System.IO.Path]::GetFullPath($stageRoot)
$outPrefix = $outDir.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedStage.StartsWith($outPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe staging path outside output directory: $resolvedStage"
}
New-Item -ItemType Directory -Force -Path $stageScripts | Out-Null

$files = @(
    "hermes-hub-linux.sh",
    "patch-hermes-gateway-native.py",
    "hermes-hub-linux-update.sh",
    "install-hermes-hub-linux.sh",
    "hermes-hub-linux.service",
    "hermes-hub-linux-update.service",
    "hermes-hub-linux-update.timer",
    "hermes-wait-tailscale.sh",
    "hermes-wait-llama.sh",
    "hermes-power-monitor.sh",
    "hermes-power-monitor.service"
)

try {
    foreach ($file in $files) {
        $source = Join-Path $PSScriptRoot $file
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Required gateway file missing: $source"
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path $stageScripts $file)
    }

    Set-Content -LiteralPath (Join-Path $stageRoot "VERSION") -Value $Version -NoNewline -Encoding Ascii

    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }

    & python $archiveBuilder --stage $stageRoot --output $archive
    if ($LASTEXITCODE -ne 0) {
        throw "Deterministic archive creation failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or (Get-Item -LiteralPath $archive).Length -le 0) {
        throw "Linux gateway archive was not created or is empty: $archive"
    }

    $listing = & tar -tzf $archive
    if ($LASTEXITCODE -ne 0) {
        throw "tar verification failed with exit code $LASTEXITCODE."
    }
    $expectedEntries = @("./", "./VERSION", "./scripts/")
    $expectedEntries += $files | ForEach-Object { "./scripts/$_" }
    $actualEntries = @($listing | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $missingEntries = @($expectedEntries | Where-Object { $actualEntries -notcontains $_ })
    $unexpectedEntries = @($actualEntries | Where-Object { $expectedEntries -notcontains $_ })
    $duplicateEntries = @($actualEntries | Group-Object | Where-Object Count -ne 1 | ForEach-Object Name)
    if ($missingEntries.Count -gt 0 -or $unexpectedEntries.Count -gt 0 -or $duplicateEntries.Count -gt 0) {
        throw "Archive verification failed. Missing=[$($missingEntries -join ', ')]; unexpected=[$($unexpectedEntries -join ', ')]; duplicates=[$($duplicateEntries -join ', ')]"
    }

    Write-Output "Linux gateway asset: $archive"
}
finally {
    if (Test-Path -LiteralPath $resolvedStage) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
