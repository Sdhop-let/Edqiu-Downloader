param(
    [string]$PublicRepoPath = "C:\Users\LENOVO\2026-07-01-12-22-36\Edqiu-application-public-",
    [ValidateSet("patch", "minor", "major", "none")]
    [string]$Bump = "patch",
    [string]$ReleaseNotes = "",
    [switch]$SkipBuild,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$GradleFile = Join-Path $ProjectRoot "app\build.gradle.kts"
$DebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
$DateStamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

function Read-VersionInfo {
    param([string]$Path)
    $text = Get-Content -Raw -Encoding UTF8 $Path
    $codeMatch = [regex]::Match($text, 'versionCode\s*=\s*(\d+)')
    $nameMatch = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"')
    if (-not $codeMatch.Success -or -not $nameMatch.Success) {
        throw "Cannot find versionCode/versionName in $Path"
    }
    return [pscustomobject]@{
        Text = $text
        Code = [int]$codeMatch.Groups[1].Value
        Name = $nameMatch.Groups[1].Value
    }
}

function Get-NextVersionName {
    param([string]$VersionName, [string]$BumpKind)
    if ($BumpKind -eq "none") { return $VersionName }
    $parts = @($VersionName.Split('.') | ForEach-Object { [int]$_ })
    while ($parts.Count -lt 3) { $parts += 0 }
    switch ($BumpKind) {
        "major" { $parts[0] += 1; $parts[1] = 0; $parts[2] = 0 }
        "minor" { $parts[1] += 1; $parts[2] = 0 }
        default { $parts[2] += 1 }
    }
    return ($parts[0..2] -join '.')
}

function Update-VersionInfo {
    param([string]$Path, [int]$Code, [string]$Name)
    $info = Read-VersionInfo -Path $Path
    $updated = $info.Text -replace 'versionCode\s*=\s*\d+', "versionCode = $Code"
    $updated = $updated -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$Name`""
    Set-Content -Path $Path -Value $updated -Encoding UTF8
}

function Ensure-PublicRepo {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        throw "Public repo not found: $Path"
    }
    Push-Location $Path
    try {
        git rev-parse --is-inside-work-tree | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Not a git repository: $Path" }
        git lfs version | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Git LFS is required because APK files are larger than GitHub's 100 MB limit." }
        git lfs install --local | Out-Null
        git lfs track "apk/*.apk" | Out-Null
        git lfs track "releases/**/*.apk" | Out-Null
        git config user.name "qiuqiu-fist"
        git config user.email "qiuqiu-fist@users.noreply.github.com"
    }
    finally {
        Pop-Location
    }
}

function Get-ChangeSummary {
    param([string]$Root)
    $ignore = @("\.gradle", "\build\", "\.kotlin", "local.properties")
    $files = Get-ChildItem -Path $Root -Recurse -File |
        Where-Object {
            $path = $_.FullName
            -not ($ignore | Where-Object { $path -match [regex]::Escape($_) })
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 12

    if (-not $files) { return "- Latest app build included." }
    return ($files | ForEach-Object { "- Updated: $($_.Name)" }) -join [Environment]::NewLine
}

$version = Read-VersionInfo -Path $GradleFile
$newVersionCode = if ($Bump -eq "none") { $version.Code } else { $version.Code + 1 }
$newVersionName = Get-NextVersionName -VersionName $version.Name -BumpKind $Bump

if ($Bump -ne "none") {
    Update-VersionInfo -Path $GradleFile -Code $newVersionCode -Name $newVersionName
    Write-Host "Version updated: $($version.Name) ($($version.Code)) -> $newVersionName ($newVersionCode)"
} else {
    Write-Host "Version unchanged: $newVersionName ($newVersionCode)"
}

if (-not $SkipBuild) {
    Push-Location $ProjectRoot
    try {
        .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $DebugApk)) {
    throw "APK not found: $DebugApk"
}

Ensure-PublicRepo -Path $PublicRepoPath

$releaseName = "v$newVersionName"
$releaseDir = Join-Path $PublicRepoPath "releases\$releaseName"
$apkDir = Join-Path $PublicRepoPath "apk"
$docsDir = Join-Path $PublicRepoPath "docs"
New-Item -ItemType Directory -Path $releaseDir, $apkDir, $docsDir -Force | Out-Null

$versionedApk = Join-Path $releaseDir "twitter-downloader-$releaseName.apk"
$latestApk = Join-Path $apkDir "twitter-downloader-latest.apk"
Copy-Item -Path $DebugApk -Destination $versionedApk -Force
Copy-Item -Path $DebugApk -Destination $latestApk -Force

if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
    $ReleaseNotes = Get-ChangeSummary -Root $ProjectRoot
}

$upgradeLines = @(
    "# edqiu downloader $releaseName upgrade notes",
    "",
    "Published at: $DateStamp",
    "",
    "## Version",
    "",
    "- Version name: $newVersionName",
    "- Version code: $newVersionCode",
    "- APK: twitter-downloader-$releaseName.apk",
    "",
    "## Changes",
    "",
    $ReleaseNotes,
    "",
    "## Upgrade steps",
    "",
    "1. Download this APK.",
    "2. Install it directly on the Android phone.",
    "3. Existing app data, settings, download history and local videos are preserved during normal overwrite install.",
    "4. If Android shows a source warning, confirm that the APK came from this GitHub repository before installing.",
    "",
    "## Data preservation",
    "",
    "- Existing database migration keeps old records.",
    "- Downloaded videos are not deleted by version upgrade.",
    "- The Resource Storage page can rescan the current download directory if old local videos need to be re-detected.",
    "",
    "## Rollback",
    "",
    "If this version has issues, install an older APK from the releases directory without clearing app data first."
)

$upgradeMd = $upgradeLines -join [Environment]::NewLine
$upgradePath = Join-Path $releaseDir "UPGRADE.md"
$latestUpgradePath = Join-Path $docsDir "latest-upgrade.md"
Set-Content -Path $upgradePath -Value $upgradeMd -Encoding UTF8
Set-Content -Path $latestUpgradePath -Value $upgradeMd -Encoding UTF8

$latestJson = @{
    versionName = $newVersionName
    versionCode = $newVersionCode
    release = $releaseName
    publishedAt = $DateStamp
    apk = "apk/twitter-downloader-latest.apk"
    versionedApk = "releases/$releaseName/twitter-downloader-$releaseName.apk"
    upgradeDocument = "releases/$releaseName/UPGRADE.md"
} | ConvertTo-Json -Depth 4
Set-Content -Path (Join-Path $PublicRepoPath "releases\latest.json") -Value $latestJson -Encoding UTF8

$readmeLines = @(
    "# Edqiu-application-public-",
    "",
    "Personal app release repository.",
    "",
    "## edqiu downloader",
    "",
    "- Latest version: $releaseName",
    "- Latest APK: apk/twitter-downloader-latest.apk",
    "- Latest upgrade notes: docs/latest-upgrade.md",
    "- Historical releases: releases/",
    "",
    "## User upgrade steps",
    "",
    "1. Download apk/twitter-downloader-latest.apk from this repository.",
    "2. Install it on the Android phone as an overwrite update.",
    "3. Read docs/latest-upgrade.md for the update details.",
    "",
    "## Release note",
    "",
    "APK files exceed GitHub's 100 MB normal file limit, so Git LFS manages apk/*.apk and releases/**/*.apk."
)
$readme = $readmeLines -join [Environment]::NewLine
Set-Content -Path (Join-Path $PublicRepoPath "README.md") -Value $readme -Encoding UTF8

Push-Location $PublicRepoPath
try {
    git add .gitattributes README.md apk releases docs
    $status = git status --porcelain
    if ($status) {
        git commit -m "Publish Twitter Downloader $releaseName" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
        if ($LASTEXITCODE -ne 0) { throw "git commit failed with exit code $LASTEXITCODE" }
        if (-not $NoPush) {
            git push origin HEAD
            if ($LASTEXITCODE -ne 0) { throw "git push failed with exit code $LASTEXITCODE" }
        }
    } else {
        Write-Host "No public repo changes to commit."
    }
}
finally {
    Pop-Location
}

Write-Host "Release prepared: $releaseName"
Write-Host "APK: $versionedApk"
Write-Host "Upgrade MD: $upgradePath"