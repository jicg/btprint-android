@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ==========================================
echo   btprint-sdk 一键发布到 Maven Central
echo ==========================================
echo.

rem 1. 从 build.gradle 读取版本号
set "VERSION="
for /f "tokens=2 delims='" %%v in ('findstr /b "version" btprint-sdk\build.gradle') do set "VERSION=%%v"
if "%VERSION%"=="" (
    echo [错误] 未能在 btprint-sdk\build.gradle 中找到 version 配置
    exit /b 1
)
echo [1/2] 当前版本号: %VERSION%

rem 2. 执行发布（上传 + 签名）
echo.
echo [2/2] 执行 gradlew :btprint-sdk:publishToMavenCentral ...
call gradlew.bat :btprint-sdk:publishToMavenCentral
if errorlevel 1 (
    echo [错误] 发布失败，请检查：
    echo   1. gradle.properties 中的 mavenCentral.username / password 是否已填写
    echo   2. signing.keyId / password / secretKeyRingFile 是否正确
    echo   3. 是否已在 central.sonatype.com 验证命名空间 io.github.jicg
    exit /b 1
)

echo.
echo ==========================================
echo   上传成功: io.github.jicg:btprint-sdk:%VERSION%
echo ==========================================
echo.
echo 下一步（手动完成发布）:
echo   1. 登录 https://central.sonatype.com
echo   2. 进入 Deployments 页面
echo   3. 找到本次上传的部署，点击 Publish 按钮
echo      （若已在 Account 设置中开启 Auto Publish，则跳过此步）
echo.
echo 发布后约 10 分钟同步到 Maven Central，消费方即可使用
echo   implementation 'io.github.jicg:btprint-sdk:%VERSION%'
echo.
echo 注意: Maven Central 产物不可覆盖，发新版必须递增版本号！
pause
