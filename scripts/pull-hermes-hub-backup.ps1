param(
    [Parameter(Mandatory = $true)][string]$SshHost,
    [ValidatePattern('^\d{8}T\d{12}Z-[0-9a-f]{8}$')][string]$Snapshot,
    [string]$RemoteRoot = "~/.hermes/backups/hub",
    [string]$LocalRoot = (Join-Path $env:LOCALAPPDATA "HermesHub\backups"),
    [ValidateRange(1, 365)][int]$Retention = 7
)

$ErrorActionPreference = "Stop"
$script:SnapshotPattern = '^\d{8}T\d{12}Z-[0-9a-f]{8}$'
$script:MaxTransferMilliseconds = 15 * 60 * 1000
$script:TransferClock = [System.Diagnostics.Stopwatch]::StartNew()

if ($SshHost -notmatch '^[A-Za-z0-9][A-Za-z0-9_.@-]*$') {
    throw "SshHost must be an operator supplied OpenSSH host or alias."
}
if ($Snapshot -and $Snapshot -notmatch $script:SnapshotPattern) {
    throw "Snapshot must use the UTC timestamp and identifier format."
}
if ($RemoteRoot -notmatch '^[A-Za-z0-9._~/\-]+$') {
    throw "RemoteRoot may contain only path characters supported by this utility."
}
if ([string]::IsNullOrWhiteSpace($LocalRoot)) {
    throw "LocalRoot is empty; set LOCALAPPDATA or pass -LocalRoot."
}

function Quote-NativeArgument([string]$Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append([char]34)
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) {
            $slashes++
            continue
        }
        if ($character -eq [char]34) {
            for ($index = 0; $index -lt (2 * $slashes + 1); $index++) {
                [void]$builder.Append([char]92)
            }
            [void]$builder.Append([char]34)
        } else {
            for ($index = 0; $index -lt $slashes; $index++) {
                [void]$builder.Append([char]92)
            }
            [void]$builder.Append($character)
        }
        $slashes = 0
    }
    for ($index = 0; $index -lt (2 * $slashes); $index++) {
        [void]$builder.Append([char]92)
    }
    [void]$builder.Append([char]34)
    return $builder.ToString()
}

function Invoke-OpenSsh([string]$Name, [string[]]$Arguments) {
    $remaining = [int][Math]::Floor($script:MaxTransferMilliseconds - $script:TransferClock.Elapsed.TotalMilliseconds)
    if ($remaining -le 0) {
        throw "OpenSSH transfer timed out after 15 minutes."
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command -or -not $command.Source) {
        throw "OpenSSH $Name was not found in PATH."
    }
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $command.Source
    $start.Arguments = (($Arguments | ForEach-Object { Quote-NativeArgument ([string]$_) }) -join " ")
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) {
            throw "Could not start OpenSSH $Name."
        }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($remaining)) {
            try { $process.Kill() } catch { }
            try { $process.WaitForExit() } catch { }
            throw "OpenSSH transfer timed out after 15 minutes."
        }
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "OpenSSH $Name failed with exit code $($process.ExitCode): $($errorText.Trim())"
        }
        return [pscustomobject]@{ Output = $output; Error = $errorText }
    } finally {
        $process.Dispose()
    }
}

function Set-PrivateAcl([string]$Path, [bool]$Directory) {
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) {
        $acl.RemoveAccessRuleAll($rule)
    }
    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = [System.Security.Principal.SecurityIdentifier]::new("S-1-5-18")
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::None
    if ($Directory) {
        $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
    }
    $rights = [System.Security.AccessControl.FileSystemRights]::FullControl
    $allow = [System.Security.AccessControl.AccessControlType]::Allow
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($currentUser, $rights, $inheritance, [System.Security.AccessControl.PropagationFlags]::None, $allow))
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($system, $rights, $inheritance, [System.Security.AccessControl.PropagationFlags]::None, $allow))
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Assert-Properties($Object, [string[]]$Expected, [string]$Label) {
    if ($null -eq $Object) {
        throw "$Label is missing."
    }
    $actual = @($Object.PSObject.Properties.Name | Sort-Object)
    $expectedSorted = @($Expected | Sort-Object)
    if (Compare-Object -ReferenceObject $expectedSorted -DifferenceObject $actual) {
        throw "$Label contains unexpected or missing fields."
    }
}

function Test-VerifiedSnapshot([string]$Directory) {
    $directoryItem = Get-Item -LiteralPath $Directory -Force
    if (-not $directoryItem.PSIsContainer -or ($directoryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw "Snapshot must be a real directory: $Directory"
    }
    $manifestPath = Join-Path $Directory "manifest.json"
    $manifestFile = Get-Item -LiteralPath $manifestPath -Force
    if ($manifestFile.PSIsContainer -or ($manifestFile.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -or $manifestFile.Length -gt 1048576) {
        throw "Snapshot manifest must be a regular file no larger than 1 MiB."
    }
    $manifestText = Get-Content -LiteralPath $manifestPath -Raw
    $manifest = $manifestText | ConvertFrom-Json
    Assert-Properties $manifest @("format", "version", "created_at", "databases") "Manifest"
    if ($manifest.format -ne "hermes-hub-sqlite-backup" -or $manifest.version -ne 1) {
        throw "Unsupported backup manifest format or version."
    }
    $createdAtMatch = [regex]::Match($manifestText, '"created_at"\s*:\s*"(?<value>[^"\\]+Z)"')
    if (-not $createdAtMatch.Success) {
        throw "Manifest creation time must be UTC."
    }
    try {
        $createdAt = [DateTimeOffset]::Parse($createdAtMatch.Groups["value"].Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal)
    } catch {
        throw "Manifest creation time is invalid."
    }
    if ($createdAt.Offset -ne [TimeSpan]::Zero) {
        throw "Manifest creation time must be UTC."
    }
    $entries = @($manifest.databases)
    if ($entries.Count -eq 0) {
        throw "Backup manifest has no databases."
    }
    $names = @("manifest.json")
    foreach ($entry in $entries) {
        Assert-Properties $entry @("snapshot", "source", "label", "size_bytes", "sha256") "Manifest database entry"
        $name = [string]$entry.snapshot
        $size = 0L
        if ($name -notmatch '^db-\d{3}-[A-Za-z0-9_.-]+$' -or
            $entry.source -isnot [string] -or [string]::IsNullOrWhiteSpace($entry.source) -or
            $entry.label -isnot [string] -or [string]::IsNullOrWhiteSpace($entry.label) -or
            -not [long]::TryParse([string]$entry.size_bytes, [ref]$size) -or $size -le 0 -or
            [string]$entry.sha256 -notmatch '^[0-9a-f]{64}$') {
            throw "Backup manifest contains an invalid database entry."
        }
        if ($names -contains $name) {
            throw "Backup manifest contains a duplicate file name: $name"
        }
        $names += $name
        $filePath = Join-Path $Directory $name
        $file = Get-Item -LiteralPath $filePath -Force
        if ($file.PSIsContainer -or ($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            throw "Snapshot database must be a regular file: $name"
        }
        if ([long]$file.Length -ne $size) {
            throw "Size mismatch for $name."
        }
        $digest = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($digest -ne [string]$entry.sha256) {
            throw "SHA-256 mismatch for $name."
        }
    }
    $children = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($children.Count -ne $names.Count) {
        throw "Snapshot contains missing or unexpected files."
    }
    foreach ($child in $children) {
        if ($child.PSIsContainer -or ($child.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -or $names -notcontains $child.Name) {
            throw "Snapshot contains missing or unexpected files."
        }
    }
    return $true
}

function Invoke-LocalRetention([string]$KeepSnapshot) {
    $separators = [char[]]@([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    $normalizeDirectory = {
        param([string]$Path)
        $fullPath = [System.IO.Path]::GetFullPath($Path)
        $normalized = $fullPath.TrimEnd($separators)
        if ([string]::IsNullOrEmpty($normalized)) {
            return [System.IO.Path]::GetPathRoot($fullPath)
        }
        return $normalized
    }
    $normalizedRoot = & $normalizeDirectory $localRoot
    $eligible = @()
    foreach ($candidate in @(Get-ChildItem -LiteralPath $localRoot -Directory -Force)) {
        if ($candidate.Attributes -band [System.IO.FileAttributes]::ReparsePoint -or $candidate.Name -notmatch $script:SnapshotPattern) {
            continue
        }
        try {
            Test-VerifiedSnapshot $candidate.FullName | Out-Null
            $eligible += $candidate
        } catch {
            continue
        }
    }
    $keep = @($eligible | Where-Object Name -eq $KeepSnapshot)
    $keep += @($eligible | Where-Object Name -ne $KeepSnapshot | Sort-Object Name -Descending | Select-Object -First ([Math]::Max(0, $Retention - 1)))
    $keepNames = @($keep | ForEach-Object Name)
    foreach ($candidate in $eligible) {
        if ($keepNames -notcontains $candidate.Name) {
            $candidatePath = [System.IO.Path]::GetFullPath([string]$candidate.FullName)
            $candidateParent = [System.IO.Path]::GetDirectoryName($candidatePath)
            $normalizedParent = & $normalizeDirectory $candidateParent
            if (-not [string]::Equals($normalizedRoot, $normalizedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Refusing to remove snapshot outside LocalRoot: $candidatePath"
            }
            try {
                $currentCandidate = Get-Item -LiteralPath $candidatePath -Force -ErrorAction Stop
            } catch {
                throw "Refusing to remove unavailable snapshot: $candidatePath"
            }
            if (-not $currentCandidate.PSIsContainer -or ($currentCandidate.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
                throw "Refusing to remove non-directory or reparse-point snapshot: $candidatePath"
            }
            Remove-Item -LiteralPath $candidatePath -Recurse -Force
        }
    }
}

$ssh = Get-Command ssh -ErrorAction SilentlyContinue
$scp = Get-Command scp -ErrorAction SilentlyContinue
if (-not $ssh -or -not $scp) {
    throw "OpenSSH ssh and scp were not found in PATH."
}

$localRoot = [System.IO.Path]::GetFullPath($LocalRoot)
$localRootExisted = [System.IO.Directory]::Exists($localRoot)
[System.IO.Directory]::CreateDirectory($localRoot) | Out-Null
if ((Get-Item -LiteralPath $localRoot -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
    throw "LocalRoot must not be a symlink or reparse point."
}
if (-not $localRootExisted) {
    Set-PrivateAcl $localRoot $true
}

if (-not $Snapshot) {
    $remoteCommand = "find -- $RemoteRoot -mindepth 1 -maxdepth 1 -type d -printf '%f\n'"
    $listing = Invoke-OpenSsh "ssh" @("-o", "BatchMode=yes", "-o", "ConnectTimeout=10", $SshHost, $remoteCommand)
    $validNames = @($listing.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match $script:SnapshotPattern } | Sort-Object -Descending -Unique)
    if ($validNames.Count -eq 0) {
        throw "No timestamp-valid backup snapshots were found on the remote host."
    }
    $Snapshot = $validNames[0]
}

$destination = Join-Path $localRoot $Snapshot
if (Test-Path -LiteralPath $destination -PathType Any) {
    try {
        Test-VerifiedSnapshot $destination | Out-Null
    } catch {
        throw "Existing local snapshot failed verification: $($_.Exception.Message)"
    }
    Invoke-LocalRetention $Snapshot
    Write-Output "Snapshot already verified locally: $destination"
    return
}

$partial = Join-Path $localRoot ("$Snapshot." + [Guid]::NewGuid().ToString("N") + ".partial")
[System.IO.Directory]::CreateDirectory($partial) | Out-Null
Set-PrivateAcl $partial $true
$published = $false
try {
    $remoteDirectory = "$RemoteRoot/$Snapshot"
    $manifestPath = Join-Path $partial "manifest.json"
    $options = @("-o", "BatchMode=yes", "-o", "ConnectTimeout=10")
    $manifestResult = Invoke-OpenSsh "scp" ($options + @("$SshHost`:$remoteDirectory/manifest.json", $manifestPath))
    Set-PrivateAcl $manifestPath $false
    if ((Get-Item -LiteralPath $manifestPath).Length -gt 1048576) {
        throw "Backup manifest exceeds 1 MiB."
    }
    $manifestText = Get-Content -LiteralPath $manifestPath -Raw
    $manifest = $manifestText | ConvertFrom-Json
    Assert-Properties $manifest @("format", "version", "created_at", "databases") "Manifest"
    if ($manifest.format -ne "hermes-hub-sqlite-backup" -or $manifest.version -ne 1) {
        throw "Unsupported backup manifest format or version."
    }
    $entries = @($manifest.databases)
    if ($entries.Count -eq 0) {
        throw "Backup manifest has no databases."
    }
    foreach ($entry in $entries) {
        Assert-Properties $entry @("snapshot", "source", "label", "size_bytes", "sha256") "Manifest database entry"
        $name = [string]$entry.snapshot
        if ($name -notmatch '^db-\d{3}-[A-Za-z0-9_.-]+$') {
            throw "Backup manifest contains an invalid database file name."
        }
        $localFile = Join-Path $partial $name
        Invoke-OpenSsh "scp" ($options + @("$SshHost`:$remoteDirectory/$name", $localFile)) | Out-Null
        Set-PrivateAcl $localFile $false
    }
    Test-VerifiedSnapshot $partial | Out-Null
    if (Test-Path -LiteralPath $destination -PathType Any) {
        throw "Snapshot appeared locally during transfer: $destination"
    }
    [System.IO.Directory]::Move($partial, $destination)
    $published = $true
    Invoke-LocalRetention $Snapshot
    Write-Output "Verified backup published: $destination"
} finally {
    if (-not $published -and [System.IO.Directory]::Exists($partial)) {
        $resolvedPartial = [System.IO.Path]::GetFullPath($partial)
        $partialParent = [System.IO.Path]::GetDirectoryName($resolvedPartial)
        if ([string]::Equals($partialParent, $localRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedPartial -Recurse -Force
        }
    }
}
