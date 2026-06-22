param(
    [switch]$SkipBuild
)

$Root = "C:\BlitzBank_android_app\do-in-place"
$Out  = "C:\BlitzBank_android_app\play-release\do-in-place"

Write-Host "[PLAY_RELEASE] bundleRelease start" -ForegroundColor Cyan

if (-not $SkipBuild) {
    Push-Location $Root
    .\gradlew.bat :app:bundleRelease
    if ($LASTEXITCODE -ne 0) {
        Write-Error "bundleRelease FAILED (exit $LASTEXITCODE)"
        exit 1
    }
    Pop-Location
}

$AabSrc = "$Root\app\build\outputs\bundle\release\app-release.aab"
$AabDst = "$Out\aab\do-in-place-release.aab"

New-Item -ItemType Directory -Force "$Out\aab"       | Out-Null
New-Item -ItemType Directory -Force "$Out\graphics"  | Out-Null
New-Item -ItemType Directory -Force "$Out\screenshots" | Out-Null

if (Test-Path $AabSrc) {
    Copy-Item $AabSrc $AabDst -Force
    Write-Host "[PLAY_RELEASE] copied aab path=$AabDst" -ForegroundColor Green
} else {
    Write-Warning "[PLAY_RELEASE] AAB not found at $AabSrc — build may have failed"
}

# ── Verify required files ──────────────────────────────────────────────────────
$Required = @(
    "$Out\aab\do-in-place-release.aab",
    "$Out\graphics\icon-512.png",
    "$Out\graphics\feature-graphic-1024x500.png",
    "$Out\store-listing.md"
)

$ScreenshotDir = "$Out\screenshots"
$Screenshots = Get-ChildItem $ScreenshotDir -Filter "*.png" -ErrorAction SilentlyContinue
$ScreenshotsOk = $Screenshots.Count -ge 4

Write-Host ""
Write-Host "══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host " Do In Place — Play Release Checklist"     -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════" -ForegroundColor Cyan

foreach ($f in $Required) {
    $exists = Test-Path $f
    $name   = Split-Path $f -Leaf
    $status = if ($exists) { "✓" } else { "✗ MISSING" }
    $color  = if ($exists) { "Green" } else { "Red" }
    Write-Host "  $status  $name" -ForegroundColor $color
}

$ssStatus = if ($ScreenshotsOk) { "✓ ($($Screenshots.Count) screenshots)" } else { "✗ MISSING (need ≥4, have $($Screenshots.Count))" }
$ssColor  = if ($ScreenshotsOk) { "Green" } else { "Red" }
Write-Host "  $ssStatus  screenshots/" -ForegroundColor $ssColor

Write-Host ""
Write-Host "Manual steps before upload to Google Play:"
Write-Host "  1. Add icon-512.png (512×512 px) to $Out\graphics\"
Write-Host "  2. Add feature-graphic-1024x500.png (1024×500 px) to $Out\graphics\"
Write-Host "  3. Add ≥4 phone screenshots (1080×1920) to $Out\screenshots\"
Write-Host "  4. Upload $AabDst to Play Console → Production"
Write-Host "  5. Paste store-listing.md content into Play Console store listing"
Write-Host ""

if ($ScreenshotsOk -and (Test-Path $AabDst)) {
    Write-Host "[PLAY_RELEASE] ✓ Release package ready" -ForegroundColor Green
} else {
    Write-Warning "[PLAY_RELEASE] Release package incomplete — see checklist above"
}
