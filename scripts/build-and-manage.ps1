param(
    [ValidateSet("debug","release")]
    [string]$BuildType = "debug",
    [switch]$DryRun,
    [switch]$SkipManage
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=== XInvox Build + APK Retention ==="
Write-Host "Project:  $ProjectRoot"
Write-Host "Build:    $BuildType"
Write-Host "Time:     $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ""

# Step 1: Build
Write-Host "[1/2] Building APK ($BuildType)..."
Push-Location $ProjectRoot
try {
    $task = if ($BuildType -eq "debug") { ":app:assembleDebug" } else { ":app:assembleRelease" }
    & .\gradlew.bat $task 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "BUILD FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
}
finally {
    Pop-Location
}
Write-Host "Build succeeded." -ForegroundColor Green
Write-Host ""

# Step 2: Manage APK retention
if ($SkipManage) {
    Write-Host "[2/2] Skipping APK retention management (-SkipManage)."
    exit 0
}

Write-Host "[2/2] Managing APK retention..."
$manageScript = Join-Path $PSScriptRoot "manage-apk.ps1"
$params = @()
if ($DryRun) { $params += "-DryRun" }
& $manageScript @params
if ($LASTEXITCODE -ne 0) {
    Write-Host "APK management failed (exit $LASTEXITCODE)" -ForegroundColor Red
    exit 1
}
