param(
    [string]$SchemaPath = "config/visual-blocks.schema.json",
    [string]$FixturePath = "tests/contracts/visual-blocks-fixture.json",
    [string]$WindowsTypesPath = "src/NemoclawChat.Windows/Services/VisualBlocks.cs",
    [string]$AndroidTypesPath = "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
)

$ErrorActionPreference = "Stop"

function Assert-True($Condition, $Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Read-Json($Path) {
    Assert-True (Test-Path $Path) "Missing file: $Path"
    return Get-Content $Path -Raw | ConvertFrom-Json
}

$schema = Read-Json $SchemaPath
$fixture = Read-Json $FixturePath

& python (Join-Path $PSScriptRoot "validate-visual-blocks-contract.py") --schema $SchemaPath --fixture $FixturePath
if ($LASTEXITCODE -ne 0) {
    throw "Visual Blocks JSON Schema validation failed with exit code $LASTEXITCODE."
}

$expectedTypes = @("markdown", "code", "table", "chart", "diagram", "image_gallery", "media_file", "callout", "metric", "progress", "approval", "device", "unknown_block")
$expectedChartTypes = @("bar", "line")
$expectedCallouts = @("info", "warning", "error", "success")
$expectedModes = @("auto", "always", "never")

Assert-True ($schema.properties.visual_blocks_version.minimum -eq 1) "Schema visual_blocks_version must accept version >= 1."
Assert-True ($schema.properties.visual_blocks.maxItems -eq 20) "Schema max visual blocks must be 20."
Assert-True ($fixture.visual_blocks_version -eq 1) "Fixture visual_blocks_version must be 1."

$fixtureTypes = @($fixture.visual_blocks | ForEach-Object { $_.type })
foreach ($type in $expectedTypes) {
    Assert-True ($fixtureTypes -contains $type) "Fixture missing block type '$type'."
}

$schemaText = Get-Content $SchemaPath -Raw
foreach ($type in $expectedTypes + $expectedChartTypes + $expectedCallouts) {
    Assert-True ($schemaText.Contains("`"$type`"")) "Schema missing enum/wire value '$type'."
}

$windowsText = if (Test-Path $WindowsTypesPath) { Get-Content $WindowsTypesPath -Raw } else { "" }

# Android protocol/rendering code is intentionally feature-owned.  Keep the
# parameter as a compatibility override for callers that pass one file, while
# the default path validates the complete source tree after MainActivity's
# decomposition.
$androidText = ""
if ($AndroidTypesPath -eq "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt" -and (Test-Path $AndroidTypesPath)) {
    $androidRoot = Split-Path -Parent $AndroidTypesPath
    $androidFiles = Get-ChildItem -LiteralPath $androidRoot -Recurse -Filter *.kt -File | Sort-Object FullName
    $androidText = ($androidFiles | ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 }) -join "`n"
} elseif (Test-Path $AndroidTypesPath) {
    $androidText = Get-Content $AndroidTypesPath -Raw -Encoding UTF8
}

foreach ($type in $expectedTypes + $expectedChartTypes + $expectedCallouts + $expectedModes) {
    if ($windowsText) {
        Assert-True ($windowsText.Contains("`"$type`"")) "Windows code missing wire value '$type'."
    }
    if ($androidText) {
        Assert-True ($androidText.Contains("`"$type`"")) "Android code missing wire value '$type'."
    }
}

Assert-True ($windowsText.Contains('"type"') -or $windowsText.Contains("Type")) "Windows code missing discriminator handling."
Assert-True ($androidText.Contains('"type"') -or $androidText.Contains(".type")) "Android code missing discriminator handling."

Write-Output "Visual Blocks contract OK."
