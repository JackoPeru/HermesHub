param(
    [string]$Version,
    [string]$OutputDirectory = "artifacts"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($Version)) {
    $adminProject = Join-Path $root "src\ChatClaw.AdminBridge\ChatClaw.AdminBridge.csproj"
    [xml]$project = Get-Content -LiteralPath $adminProject
    $Version = $project.Project.PropertyGroup.Version
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "Version not found. Pass -Version X.Y.Z."
}

$outDir = Join-Path $root $OutputDirectory
$stageRoot = Join-Path $outDir "linux-gateway-stage"
$stageScripts = Join-Path $stageRoot "scripts"
$archive = Join-Path $outDir "HermesHub-$Version-linux-gateway.tar.gz"

Remove-Item -LiteralPath $stageRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stageScripts | Out-Null
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$files = @(
    "hermes-hub-linux.sh",
    "patch-hermes-gateway-native.py",
    "hermes-hub-linux-update.sh",
    "install-hermes-hub-linux.sh",
    "hermes-hub-linux.service",
    "hermes-hub-linux-update.service",
    "hermes-hub-linux-update.timer"
)

foreach ($file in $files) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $stageScripts $file)
}

Set-Content -LiteralPath (Join-Path $stageRoot "VERSION") -Value $Version -NoNewline

if (Test-Path -LiteralPath $archive) {
    Remove-Item -LiteralPath $archive -Force
}

tar -czf $archive -C $stageRoot .
Remove-Item -LiteralPath $stageRoot -Recurse -Force

Write-Host "Linux gateway asset: $archive"
