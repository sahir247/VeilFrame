# Installs one immutable, checksum-verified FFmpeg release for Windows CI.
# This deliberately does not trust Chocolatey's success exit code: on 2026-11-24
# Chocolatey returned 0 after HTTP 504 and reported "installed 0/0 packages".
# Both sources below serve the SAME official GyanD 9.1 ZIP, verified by its SHA-256.
[CmdletBinding()]
param(
    [switch]$StageResources
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$version = '9.1'
$expectedSha256 = 'b9bbbb3acece000cbeec994f856649354e52c773807841e78908374b24d658fd'
$archiveName = "ffmpeg-$version-essentials_build.zip"
$archivePath = Join-Path $env:GITHUB_WORKSPACE ".ci-cache/$archiveName"
$extractDir = Join-Path $env:RUNNER_TEMP "veilframe-ffmpeg-$version-extract"
$binDir = Join-Path $env:RUNNER_TEMP "veilframe-ffmpeg-$version-bin"
$sources = @(
    "https://github.com/GyanD/codexffmpeg/releases/download/$version/$archiveName",
    "https://www.gyan.dev/ffmpeg/builds/packages/$archiveName"
)

function Test-VerifiedArchive {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return $actual -eq $expectedSha256
}

function Assert-WorkingBinaries {
    param([string]$Directory)
    foreach ($tool in @('ffmpeg', 'ffprobe')) {
        $exePath = Join-Path $Directory "$tool.exe"
        if (-not (Test-Path -LiteralPath $exePath -PathType Leaf)) {
            throw "Missing $exePath"
        }
        if ((Get-Item -LiteralPath $exePath).Length -lt 1048576) {
            throw "Invalid or truncated binary: $exePath"
        }
        $output = (& $exePath -version | Out-String)
        if ($LASTEXITCODE -ne 0 -or $output -notmatch "(?m)^$tool version 9\.1(?:[^0-9]|$)") {
            throw "$tool binary failed its pinned $version version/execute check (exit $LASTEXITCODE): $output"
        }
    }
}

New-Item -ItemType Directory -Path (Split-Path -Parent $archivePath) -Force | Out-Null

# Always re-hash even a cache hit: cache corruption or poisoning must fail closed.
if (-not (Test-VerifiedArchive -Path $archivePath)) {
    Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
    $downloaded = $false
    foreach ($source in $sources) {
        for ($attempt = 1; $attempt -le 2; $attempt++) {
            try {
                Write-Host "Fetching pinned FFmpeg $version from $source (attempt $attempt/2)"
                Invoke-WebRequest -Uri $source -OutFile $archivePath -UseBasicParsing -TimeoutSec 240
                if (-not (Test-VerifiedArchive -Path $archivePath)) {
                    throw "Downloaded archive SHA-256 does not match the pinned release"
                }
                $downloaded = $true
                break
            } catch {
                Write-Warning "FFmpeg source failed: $($_.Exception.Message)"
                Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
                if ($attempt -lt 2) { Start-Sleep -Seconds 4 }
            }
        }
        if ($downloaded) { break }
    }
    if (-not $downloaded) {
        throw "Could not obtain SHA-256-verified FFmpeg $version from either source"
    }
}
Write-Host "FFmpeg archive SHA-256 verified: $expectedSha256"

Remove-Item -LiteralPath $extractDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
Expand-Archive -LiteralPath $archivePath -DestinationPath $extractDir -Force

$ffmpegFiles = @(Get-ChildItem -LiteralPath $extractDir -Recurse -File -Filter 'ffmpeg.exe')
if ($ffmpegFiles.Count -ne 1) {
    throw "Expected one ffmpeg.exe in the verified archive; found $($ffmpegFiles.Count)"
}
$extractedBinDir = Split-Path -Parent $ffmpegFiles[0].FullName
Assert-WorkingBinaries -Directory $extractedBinDir

New-Item -ItemType Directory -Path $binDir -Force | Out-Null
foreach ($tool in @('ffmpeg', 'ffprobe')) {
    Copy-Item -LiteralPath (Join-Path $extractedBinDir "$tool.exe") -Destination (Join-Path $binDir "$tool.exe") -Force
}
Assert-WorkingBinaries -Directory $binDir

if ($StageResources) {
    foreach ($resourceDir in @('resources/ffmpeg', 'veilframe/resources/ffmpeg')) {
        New-Item -ItemType Directory -Path $resourceDir -Force | Out-Null
        foreach ($tool in @('ffmpeg', 'ffprobe')) {
            Copy-Item -LiteralPath (Join-Path $binDir "$tool.exe") -Destination (Join-Path $resourceDir "$tool.exe") -Force
        }
        Assert-WorkingBinaries -Directory $resourceDir
    }
}

# GitHub Actions adds GITHUB_PATH entries to subsequent steps (not this process).
$binDir | Out-File -FilePath $env:GITHUB_PATH -Encoding utf8 -Append
$env:PATH = "$binDir;$env:PATH"
Write-Host "Ready: pinned FFmpeg $version binaries at $binDir"
