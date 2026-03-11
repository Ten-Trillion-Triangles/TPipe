@echo off
setlocal EnableDelayedExpansion

if "%~1"=="" (
    echo Usage: tuner.bat --test-string "<string>" --expected-tokens ^<integer^>
    echo Example: tuner.bat --test-string "Hello, world!" --expected-tokens 4
    goto :eof
)

:: Navigate to root directory to use the main gradlew
cd %~dp0..

:: Build properly escaped args string for Gradle
set "args_string="
:parse_args
if "%~1"=="" goto :run_gradle
set "args_string=%args_string% %1"
shift
goto :parse_args

:run_gradle
call gradlew.bat :TPipe-Tuner:run --args="%args_string:~1%"
endlocal
