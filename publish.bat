@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ==========================================
echo   btprint-sdk 一键发布到 Gitee Maven 仓库
echo ==========================================
echo.

rem 1. 从 build.gradle 读取版本号
set "VERSION="
for /f "tokens=2 delims='" %%v in ('findstr /b "version" btprint-sdk\build.gradle') do set "VERSION=%%v"
if "%VERSION%"=="" (
    echo [错误] 未能在 btprint-sdk\build.gradle 中找到 version 配置
    exit /b 1
)
echo [1/3] 当前版本号: %VERSION%

rem 2. 执行 maven-publish 生成产物
echo.
echo [2/3] 执行 gradlew :btprint-sdk:publish ...
call gradlew.bat :btprint-sdk:publish
if errorlevel 1 (
    echo [错误] publish 失败，请检查上方错误信息
    exit /b 1
)

rem 3. 提交产物并推送
echo.
echo [3/3] git 提交并推送到 Gitee ...
git add repo/ btprint-sdk/build.gradle
if errorlevel 1 (
    echo [错误] git add 失败
    exit /b 1
)
git commit -m "release: btprint-sdk %VERSION%"
if errorlevel 1 (
    rem git commit 返回 1 通常表示无改动可提交（如产物未变化），属正常
    echo [提示] 无新改动可提交，继续推送
)
git push origin master
if errorlevel 1 (
    echo [错误] git push 失败，请检查远程仓库与网络
    exit /b 1
)

echo.
echo ==========================================
echo   发布完成: com.gitee.jicg:btprint-sdk:%VERSION%
echo ==========================================
pause
