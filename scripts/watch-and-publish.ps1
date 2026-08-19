param(
    [string]$PublicRepoPath = "C:\Users\LENOVO\2026-07-01-12-22-36\Edqiu-application-public-",
    [int]$DebounceSeconds = 120,
    [ValidateSet("patch", "minor", "major")]
    [string]$Bump = "patch"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PublishScript = Join-Path $PSScriptRoot "publish-github-release.ps1"

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $ProjectRoot
$watcher.IncludeSubdirectories = $true
$watcher.EnableRaisingEvents = $true
$watcher.Filter = "*.*"

$script:lastChange = Get-Date
$script:hasChange = $false
$ignored = @("\.gradle", "\build\", "\.kotlin", "\app\build\", "\docs\release-automation.md")

$action = {
    $path = $Event.SourceEventArgs.FullPath
    foreach ($item in $ignored) {
        if ($path -match [regex]::Escape($item)) { return }
    }
    $script:hasChange = $true
    $script:lastChange = Get-Date
    Write-Host "Detected change: $path"
}

$subs = @(
    Register-ObjectEvent $watcher Changed -Action $action,
    Register-ObjectEvent $watcher Created -Action $action,
    Register-ObjectEvent $watcher Deleted -Action $action,
    Register-ObjectEvent $watcher Renamed -Action $action
)

Write-Host "Watching $ProjectRoot"
Write-Host "When changes stop for $DebounceSeconds seconds, a $Bump version will be built and pushed."
Write-Host "Press Ctrl+C to stop."

try {
    while ($true) {
        Start-Sleep -Seconds 5
        if ($script:hasChange) {
            $idle = (New-TimeSpan -Start $script:lastChange -End (Get-Date)).TotalSeconds
            if ($idle -ge $DebounceSeconds) {
                $script:hasChange = $false
                & $PublishScript -PublicRepoPath $PublicRepoPath -Bump $Bump -ReleaseNotes "- Auto release generated from latest local project changes."
            }
        }
    }
}
finally {
    foreach ($sub in $subs) {
        Unregister-Event -SubscriptionId $sub.Id -ErrorAction SilentlyContinue
    }
    $watcher.Dispose()
}