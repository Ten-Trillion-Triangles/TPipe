@echo off
setlocal

if "%~1"=="" (
    echo Usage: tuner.bat --test-string "<string>" --expected-tokens ^<integer^>
    echo Example: tuner.bat --test-string "Hello, world!" --expected-tokens 4
)

:: Navigate to root directory to use the main gradlew
cd %~dp0..
call gradlew.bat :TPipe-Tuner:run --args="%*"
endlocal
