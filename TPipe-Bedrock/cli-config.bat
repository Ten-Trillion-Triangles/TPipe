@echo off
REM AWS Bedrock Inference Profile Configuration Tool - Windows Version
REM Auto-detects runtime and executes appropriate version

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0

REM Check for interactive mode flag
if "%1"=="--interactive" goto interactive
if "%1"=="-i" goto interactive
goto kotlin_check

:interactive
shift
echo Starting interactive mode...
cd /d "%SCRIPT_DIR%\.."
if exist "gradlew.bat" (
    echo Starting interactive shell...
    kotlinc interactive-cli.kt -include-runtime -d interactive-temp.jar >nul 2>&1
    if %errorlevel% equ 0 (
        java -jar interactive-temp.jar
        del interactive-temp.jar >nul 2>&1
        exit /b 0
    )
) else (
    echo Gradle wrapper not found. Please run from parent directory.
    pause
    exit /b 1
)

:kotlin_check
REM Check if Kotlin is available
kotlinc -version >nul 2>&1
if %errorlevel% equ 0 (
    echo Using Kotlin runtime...
    cd /d "%SCRIPT_DIR%"
    kotlinc run-cli-simple.kt -include-runtime -d cli-temp.jar >nul 2>&1
    if %errorlevel% equ 0 (
        java -jar cli-temp.jar %*
        del cli-temp.jar >nul 2>&1
        exit /b 0
    ) else (
        echo Kotlin compilation failed, trying Java fallback...
    )
)

REM Check if Java is available
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo Error: Neither Kotlin nor Java runtime found.
    echo Please install one of the following:
    echo   - Kotlin: https://kotlinlang.org/docs/command-line.html
    echo   - Java 8+: https://adoptium.net/
    pause
    exit /b 1
)

echo Kotlin not found, using Java with simple compilation...
echo Please install Kotlin for full functionality, or use the Gradle build from parent directory
echo Run: cd .. ^&^& gradlew :TPipe-Bedrock:run
pause
exit /b 1