# Build a signed release APK ready to attach to a GitHub release.
# 1. Bump versionCode + versionName in app/build.gradle.kts
# 2. Run: .\release.cmd
# 3. On GitHub: Releases -> Draft a new release -> tag "v<versionName>" -> attach the APK from .\release\
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path keystore.properties)) { throw "keystore.properties missing: releases must be signed with the Slim Volume key" }

$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value

# lintVital* needs lint jars that aren't cached offline (it hangs), so it's skipped.
.\gradlew.bat assembleRelease --offline -q --console=plain -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

New-Item -ItemType Directory -Force release | Out-Null
$out = "release\thor-slim-volume-v$version.apk"
Copy-Item app\build\outputs\apk\release\app-release.apk $out -Force
Write-Host "Built $out"
Write-Host "Create a GitHub release with tag v$version and attach that file."
