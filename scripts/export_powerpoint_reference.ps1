# Requires a locally installed, licensed Microsoft PowerPoint on Windows.
# Run only with a trusted, synthetic fixture, with macros disabled by Office policy.
param(
    [Parameter(Mandatory=$true)][string]$Fixture,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Fixture).Path
if ([IO.Path]::GetExtension($source) -ne '.pptx') { throw 'Expected a .pptx fixture' }
if (Test-Path -LiteralPath $Output) { throw 'Output directory must not exist' }
$out = [IO.Path]::GetFullPath($Output)
$null = New-Item -ItemType Directory -Path $out
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$app = $null
$deck = $null
try {
    $app = New-Object -ComObject PowerPoint.Application
    # msoAutomationSecurityForceDisable (3); do not lower Office security.
    $app.AutomationSecurity = 3
    $deck = $app.Presentations.Open($source, -1, 0, 0)
    $count = $deck.Slides.Count
    if ($count -lt 1 -or $count -gt 1000) { throw 'Unsupported slide count' }
    $width = 1920
    $height = [int][Math]::Floor($width * $deck.PageSetup.SlideHeight / $deck.PageSetup.SlideWidth)
    if ($height -lt 1 -or $height -gt 8192) { throw 'Unsupported slide aspect ratio' }
    for ($i=1; $i -le $count; $i++) {
        $slide = $deck.Slides.Item($i)
        try {
            $slide.Export((Join-Path $out ('slide-{0:D4}.png' -f $i)), 'PNG', $width, $height)
        } finally { $null = [Runtime.InteropServices.Marshal]::ReleaseComObject($slide) }
    }
    if ($hash -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Source mutated during export' }
    [ordered]@{
        schema=1; source_sha256=$hash; source_name=[IO.Path]::GetFileName($source)
        renderer='Microsoft PowerPoint'; renderer_version=[string]$app.Version
        os_version=[Environment]::OSVersion.VersionString; slide_count=$count
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'manifest.json') -Encoding UTF8
} finally {
    if ($null -ne $deck) { $deck.Close(); $null = [Runtime.InteropServices.Marshal]::ReleaseComObject($deck) }
    if ($null -ne $app) { $null = [Runtime.InteropServices.Marshal]::ReleaseComObject($app) }
}
