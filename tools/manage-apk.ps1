param(
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectRoot   = Split-Path -Parent $PSScriptRoot
$GradleFile    = Join-Path $ProjectRoot "app\build.gradle.kts"
$ApkSource      = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
$ArchiveDir    = Join-Path $ProjectRoot "apk-archive"
$HistoryFile   = Join-Path $ArchiveDir "build-history.json"

function Read-VersionInfo {
    param([string]$Path)
    $text = Get-Content -Raw -Encoding UTF8 $Path
    $codeMatch = [regex]::Match($text, 'versionCode\s*=\s*(\d+)')
    $nameMatch = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"')
    if (-not $codeMatch.Success -or -not $nameMatch.Success) {
        throw "Cannot find versionCode/versionName in $Path"
    }
    return [pscustomobject]@{
        Code = [int]$codeMatch.Groups[1].Value
        Name = $nameMatch.Groups[1].Value
    }
}

function Load-History {
    if (Test-Path -LiteralPath $HistoryFile) {
        $raw = Get-Content -Raw -Encoding UTF8 $HistoryFile
        $h = $raw | ConvertFrom-Json
        if (-not $h.builds) { $h = [pscustomobject]@{ builds = @() } }
        return $h
    }
    return [pscustomobject]@{ builds = @() }
}

function Save-History {
    param($History)
    $dir = Split-Path -Parent $HistoryFile
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $History | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $HistoryFile -Encoding UTF8
}

function Format-Timestamp {
    param([datetime]$Time)
    return $Time.ToString("yyyyMMdd-HHmmss")
}

Write-Host "=== APK Retention Manager ==="
Write-Host "Project: $ProjectRoot"
Write-Host ""

# 1. Read current version
$version = Read-VersionInfo -Path $GradleFile
Write-Host "Current version: $($version.Name) (code $($version.Code))"

# 2. Check APK exists
if (-not (Test-Path -LiteralPath $ApkSource)) {
    Write-Host ""
    Write-Host "ERROR: Built APK not found at:" -ForegroundColor Red
    Write-Host "  $ApkSource" -ForegroundColor Red
    Write-Host "Run .\gradlew.bat :app:assembleDebug first." -ForegroundColor Yellow
    exit 1
}

$apkItem = Get-Item -LiteralPath $ApkSource
$buildTime = Get-Date
$timestamp = Format-Timestamp -Time $buildTime
$apkName = "XInvox-v$($version.Name)-build$($version.Code)-$timestamp.apk"
$archivePath = Join-Path $ArchiveDir $apkName

# 3. Ensure archive dir exists
if (-not (Test-Path $ArchiveDir)) {
    New-Item -ItemType Directory -Path $ArchiveDir -Force | Out-Null
}

# 4. Copy new APK to archive
Write-Host ""
Write-Host "[1/3] Archiving new APK..."
if (-not $DryRun) {
    Copy-Item -LiteralPath $ApkSource -Destination $archivePath -Force
    Write-Host "  -> $apkName ($([math]::Round($apkItem.Length/1MB,1)) MB)"
} else {
    Write-Host "  (dry-run) would copy -> $apkName"
}

# 5. Update build history
$history = Load-History
$newEntry = [pscustomobject]@{
    file        = $apkName
    versionName = $version.Name
    versionCode = $version.Code
    buildTime   = $buildTime.ToString("yyyy-MM-ddTHH:mm:ss")
    size        = $apkItem.Length
}
$buildsList = @($history.builds) + $newEntry
$history = [pscustomobject]@{ builds = $buildsList }

# 6. Apply retention policy
Write-Host ""
Write-Host "[2/3] Applying retention policy..."
# Sort by build time descending (newest first)
$sorted = $buildsList | Sort-Object { [datetime]$_.buildTime } -Descending

$currentVersion = $version.Name
$versions = $sorted | Group-Object -Property versionName | Sort-Object { [datetime]($_.Group[0].buildTime) } -Descending
$keepFiles = @{}
$deleteFiles = @()

foreach ($vg in $versions) {
    $vname = $vg.Name
    $vbuilds = $vg.Group | Sort-Object { [datetime]$_.buildTime } -Descending
    if ($vname -eq $currentVersion) {
        # Current version: keep latest 2 builds, delete the rest
        $keep = @($vbuilds | Select-Object -First 2)
        $del = @($vbuilds | Select-Object -Skip 2)
        Write-Host "  Version $vname (current): keeping latest $($keep.Count), deleting $($del.Count) old build(s)"
    } else {
        # Previous version (upgrade): keep latest 1 as archive milestone
        $keep = @($vbuilds | Select-Object -First 1)
        $del = @($vbuilds | Select-Object -Skip 1)
        Write-Host "  Version $vname (archived): keeping 1 milestone, deleting $($del.Count) old build(s)"
    }
    foreach ($k in $keep) { $keepFiles[$k.file] = $true }
    foreach ($d in $del)  { $deleteFiles += $d }
}

# 7. Delete old APKs
Write-Host ""
Write-Host "[3/3] Cleaning up old APKs..."
if ($deleteFiles.Count -eq 0) {
    Write-Host "  Nothing to delete."
}
foreach ($entry in $deleteFiles) {
    $filePath = Join-Path $ArchiveDir $entry.file
    if (Test-Path -LiteralPath $filePath) {
        if (-not $DryRun) {
            Remove-Item -LiteralPath $filePath -Force
            Write-Host "  DELETED: $($entry.file) (v$($entry.versionName), $($entry.buildTime))"
        } else {
            Write-Host "  (dry-run) would delete: $($entry.file)"
        }
    }
}

# 8. Save updated history (only kept entries)
$finalBuilds = $sorted | Where-Object { $keepFiles[$_.file] } | Sort-Object { [datetime]$_.buildTime } -Descending
$history = [pscustomobject]@{ builds = $finalBuilds }
if (-not $DryRun) {
    Save-History -History $history
}

# 9. Summary
Write-Host ""
Write-Host "=== Summary ==="
Write-Host "Archived: $apkName"
Write-Host "Total APKs in archive: $($finalBuilds.Count)"
Write-Host ""
Write-Host "Archive contents:"
$finalBuilds | ForEach-Object {
    $status = if ($_.versionName -eq $currentVersion) { "current" } else { "milestone" }
    $sizeMB = [math]::Round($_.size / 1MB, 1)
    Write-Host ("  [{0}] v{1} (code {2})  {3}  {4} MB  {5}" -f $status, $_.versionName, $_.versionCode, $_.file, $sizeMB, $_.buildTime)
}
Write-Host ""
Write-Host "Archive dir: $ArchiveDir"
if ($DryRun) { Write-Host "(DRY RUN - no files were modified)" -ForegroundColor Yellow }
