@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ==========================================================
echo   btprint-sdk - Build Maven Central deployment bundle
echo   (manual upload mode)
echo ==========================================================
echo.

rem ============ 1. Read version ============
set "VERSION="
for /f "tokens=2 delims='" %%v in ('findstr /b "version" btprint-sdk\build.gradle') do set "VERSION=%%v"
if "%VERSION%"=="" (
    echo [ERROR] version not found in btprint-sdk\build.gradle
    exit /b 1
)
echo [1/5] Version: %VERSION%

rem ============ 2. Build artifacts ============
echo [2/5] Building artifacts (AAR / POM / sources / javadoc) ...
call gradlew.bat :btprint-sdk:assembleRelease :btprint-sdk:generatePomFileForMavenPublication :btprint-sdk:sourceReleaseJar :btprint-sdk:javaDocReleaseJar
if errorlevel 1 (
    echo [ERROR] Build failed, check the log above
    exit /b 1
)

rem ============ 3. Collect files into Maven layout ============
set "GROUP_PATH=io\github\jicg"
set "OUT=app\release\maven-central\%GROUP_PATH%\btprint-sdk\%VERSION%"
set "SRC=btprint-sdk\build"
if exist "app\release\maven-central" rmdir /s /q "app\release\maven-central"
if not exist "%OUT%" mkdir "%OUT%"

echo [3/5] Collecting files to %OUT% ...
copy /y "%SRC%\outputs\aar\btprint-sdk-release.aar"                  "%OUT%\btprint-sdk-%VERSION%.aar"            >nul
copy /y "%SRC%\publications\maven\pom-default.xml"                   "%OUT%\btprint-sdk-%VERSION%.pom"            >nul
copy /y "%SRC%\libs\btprint-sdk-%VERSION%-sources.jar"               "%OUT%\btprint-sdk-%VERSION%-sources.jar"     >nul
copy /y "%SRC%\intermediates\java_doc_jar\release\release-javadoc.jar" "%OUT%\btprint-sdk-%VERSION%-javadoc.jar" >nul
copy /y "%SRC%\publications\maven\module.json"                       "%OUT%\btprint-sdk-%VERSION%.module"          >nul

if not exist "%OUT%\btprint-sdk-%VERSION%.aar" (
    echo [ERROR] AAR artifact not found, check build output
    exit /b 1
)

rem ============ 4. GPG sign + md5/sha1 checksums ============
rem Read signing config from user-level gradle.properties (not in git)
set "GUG=%GRADLE_USER_HOME%"
if "%GUG%"=="" set "GUG=%USERPROFILE%\.gradle"
set "SIGN_PASSWORD="
set "SIGN_KEYID="
if exist "%GUG%\gradle.properties" (
    for /f "usebackq tokens=1,* delims==" %%a in ("%GUG%\gradle.properties") do (
        if "%%a"=="signing.password" set "SIGN_PASSWORD=%%b"
        if "%%a"=="signing.keyId"    set "SIGN_KEYID=%%b"
    )
)
if "%SIGN_PASSWORD%"=="" (
    echo [ERROR] signing.password not found in %GUG%\gradle.properties
    exit /b 1
)

set "GPG=D:\Program Files\GnuPG\bin\gpg.exe"
if not exist "%GPG%" set "GPG=gpg"

echo [4/5] Generating GPG signatures and md5/sha1 checksums ...
set "KEYARG="
if defined SIGN_KEYID set "KEYARG=-u %SIGN_KEYID%"
for %%F in ("%OUT%\*") do (
    echo   Processing %%~nxF
    "%GPG%" --batch --yes --pinentry-mode loopback --passphrase "%SIGN_PASSWORD%" %KEYARG% --detach-sign --armor --output "%%~fF.asc" "%%~fF"
    if errorlevel 1 (
        echo [ERROR] GPG signing failed: %%~nxF
        exit /b 1
    )
    powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-FileHash -Algorithm MD5 -Path '%%~fF').Hash.ToLower() | Out-File -Encoding ascii '%%~fF.md5' -NoNewline"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-FileHash -Algorithm SHA1 -Path '%%~fF').Hash.ToLower() | Out-File -Encoding ascii '%%~fF.sha1' -NoNewline"
)

rem ============ 5. Package ZIP ============
echo [5/5] Packaging ZIP ...
set "JAR=D:\Program Files\Android\Android Studio\jbr\bin\jar.exe"
if exist "%JAR%" (
    "%JAR%" cf "app\release\btprint-sdk-%VERSION%-upload.zip" -C "app\release\maven-central" .
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path 'app\release\maven-central\*' -DestinationPath 'app\release\btprint-sdk-%VERSION%-upload.zip' -Force"
)
if not exist "app\release\btprint-sdk-%VERSION%-upload.zip" (
    echo [ERROR] ZIP packaging failed
    exit /b 1
)

echo.
echo ==========================================================
echo   Bundle ready: app\release\btprint-sdk-%VERSION%-upload.zip
echo ==========================================================
echo.
echo Manual upload steps:
echo   1. Login https://central.sonatype.com
echo   2. Go to Deployments page, click Upload
echo   3. Drag and drop app\release\btprint-sdk-%VERSION%-upload.zip
echo   4. Wait for status VALIDATED, then click Publish
echo.
echo After ~10 minutes it syncs to Maven Central. Consumers can use
echo   implementation 'io.github.jicg:btprint-sdk:%VERSION%'
echo.
echo NOTE: Artifacts on Maven Central are immutable, bump version for releases!
pause
