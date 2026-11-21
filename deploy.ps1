# Build, install and launch Slim Volume on the connected Thor.
# Usage: .\deploy.ps1            (uses the single connected device)
#        .\deploy.ps1 192.168.1.50:41234   (connects first; use the port shown under Wireless debugging)
param([string]$Device)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($Device) { adb connect $Device | Write-Host }

.\gradlew.bat assembleDebug --offline -q --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

adb install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw "Install failed (is the Thor connected? run: adb devices)" }

adb shell am start -n com.thorhud.volume/.MainActivity | Out-Null
Write-Host "Installed. The accessibility toggle normally stays on across reinstalls; if the bar stops showing, toggle it off/on."
