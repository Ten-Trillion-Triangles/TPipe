@echo off
REM AWS Bedrock Inference Profile Configuration Tool - Windows Version
REM Optimized fallback chain: JAR -> Kotlin -> Build -> Fallback scripts

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
set CLASSPATH=build/libs/TPipe-0.0.1.jar;TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar

REM Main execution logic
if "%1"=="--interactive" goto interactive_mode
if "%1"=="-i" goto interactive_mode
goto command_mode

:interactive_mode
shift
echo Interactive mode requested...

REM Try JAR methods first
call :try_jar_java %*
if %errorlevel% equ 0 exit /b 0

kotlinc -version >nul 2>&1
if %errorlevel% equ 0 (
    call :try_jar_kotlin %*
    if %errorlevel% equ 0 exit /b 0
)

REM Try building
gradle -version >nul 2>&1
if %errorlevel% equ 0 (
    call :try_gradle_build gradle %*
    if %errorlevel% equ 0 exit /b 0
)

call :try_gradle_build gradlew.bat %*
if %errorlevel% equ 0 exit /b 0

REM Fall back to interactive CLI
kotlinc -version >nul 2>&1
if %errorlevel% equ 0 (
    call :try_interactive_cli
    if %errorlevel% equ 0 exit /b 0
)
goto error_exit

:command_mode
echo Command mode: %*

REM Try JAR methods first
call :try_jar_java %*
if %errorlevel% equ 0 exit /b 0

kotlinc -version >nul 2>&1
if %errorlevel% equ 0 (
    call :try_jar_kotlin %*
    if %errorlevel% equ 0 exit /b 0
)

REM Try building
gradle -version >nul 2>&1
if %errorlevel% equ 0 (
    call :try_gradle_build gradle %*
    if %errorlevel% equ 0 exit /b 0
)

call :try_gradle_build gradlew.bat %*
if %errorlevel% equ 0 exit /b 0

REM Fall back to simple CLI
kotlinc -version >nul 2>&1
if %errorlevel% equ 0 (
    call :try_simple_cli %*
    if %errorlevel% equ 0 exit /b 0
)

:error_exit
echo ERROR: All CLI methods failed.
echo Requirements:
echo   - Java runtime for JAR execution
echo   - Kotlin runtime for standalone scripts
echo   - Gradle for building
pause
exit /b 1

REM Function to try running existing JAR with java
:try_jar_java
cd /d "%PROJECT_DIR%"
if exist "build\libs\TPipe-0.0.1.jar" if exist "TPipe-Bedrock\build\libs\TPipe-Bedrock-0.0.1.jar" (
    echo [1/5] Trying existing JAR with java...
    java -cp "%CLASSPATH%" cli.InferenceConfigCli %* >nul 2>&1
    if %errorlevel% equ 0 exit /b 0
)
exit /b 1

REM Function to try running existing JAR with kotlin
:try_jar_kotlin
cd /d "%PROJECT_DIR%"
if exist "build\libs\TPipe-0.0.1.jar" if exist "TPipe-Bedrock\build\libs\TPipe-Bedrock-0.0.1.jar" (
    echo [2/5] Trying existing JAR with kotlin...
    kotlin -cp "%CLASSPATH%" cli.InferenceConfigCli %* >nul 2>&1
    if %errorlevel% equ 0 exit /b 0
)
exit /b 1

REM Function to build and try with gradle
:try_gradle_build
set gradle_cmd=%1
shift
cd /d "%PROJECT_DIR%"
echo [3/5] Building with gradle and trying...
%gradle_cmd% :TPipe-Bedrock:assemble >nul 2>&1
if %errorlevel% equ 0 (
    java -cp "%CLASSPATH%" cli.InferenceConfigCli %* >nul 2>&1
    if %errorlevel% equ 0 exit /b 0
    kotlin -cp "%CLASSPATH%" cli.InferenceConfigCli %* >nul 2>&1
    if %errorlevel% equ 0 exit /b 0
)
exit /b 1

REM Function to try interactive CLI
:try_interactive_cli
cd /d "%SCRIPT_DIR%"
echo [4/5] Falling back to interactive CLI...
kotlinc interactive-cli.kt -include-runtime -d interactive-temp.jar >nul 2>&1
if %errorlevel% equ 0 (
    java -jar interactive-temp.jar
    del interactive-temp.jar >nul 2>&1
    exit /b 0
)
exit /b 1

REM Function to try simple CLI
:try_simple_cli
cd /d "%SCRIPT_DIR%"
echo [5/5] Falling back to simple CLI...
kotlinc run-cli-simple.kt -include-runtime -d cli-temp.jar >nul 2>&1
if %errorlevel% equ 0 (
    java -jar cli-temp.jar %*
    del cli-temp.jar >nul 2>&1
    exit /b 0
)
exit /b 1