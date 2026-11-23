# Xflix TV - versioned APK backup with a rolling keep-4 policy.
#
# Every build is archived as  Xflix-TV-v<versionName>-b<versionCode>.apk  under
# backups\apk\, and anything older than the four newest is deleted so a stale
# build can never be reinstalled by mistake.
#
# Usage:  powershell -ExecutionPolicy Bypass -File .\backup-apk.ps1

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$apk  = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$dest = Join-Path $root "backups\apk"
$KEEP = 4

if (-not (Test-Path $apk)) {
    throw "APK not found at $apk - run gradlew :app:assembleDebug first."
}

# Read versionCode / versionName straight from the built APK so the archive name
# always matches what is actually inside the file.
$aapt2 = "C:\Android\Sdk\build-tools\34.0.0\aapt2.exe"
$badging = (& $aapt2 dump badging $apk 2>$null | Select-Object -First 1)

$versionName = "unknown"
$versionCode = "0"
if ($badging -match "versionName='([^']+)'") { $versionName = $Matches[1] }
if ($badging -match "versionCode='([^']+)'") { $versionCode = $Matches[1] }

New-Item -ItemType Directory -Force -Path $dest | Out-Null

$name   = "Xflix-TV-v$versionName-b$versionCode.apk"
$target = Join-Path $dest $name

Copy-Item -Force $apk $target
$sizeMb = [Math]::Round((Get-Item $target).Length / 1MB, 2)
Write-Host "Archived  $name  ($sizeMb MB)"

# Drop anything beyond the four newest archives.
$archives = Get-ChildItem -Path $dest -Filter "Xflix-TV-v*.apk" |
    Sort-Object LastWriteTime -Descending

$archives | Select-Object -Skip $KEEP | ForEach-Object {
    Remove-Item -Force $_.FullName
    Write-Host "Pruned    $($_.Name)"
}

Write-Host ""
Write-Host "Retained archives:"
Get-ChildItem -Path $dest -Filter "Xflix-TV-v*.apk" |
    Sort-Object LastWriteTime -Descending |
    ForEach-Object {
        $mb = [Math]::Round($_.Length / 1MB, 2)
        Write-Host "  $($_.Name)  ($mb MB)"
    }
