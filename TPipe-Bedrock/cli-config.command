#!/bin/bash

# AWS Bedrock Inference Profile Configuration Tool - macOS Version
# Auto-detects runtime and executes appropriate version

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Check for interactive mode flag
if [ "$1" = "--interactive" ] || [ "$1" = "-i" ]; then
    shift
    echo "Starting interactive mode..."
    cd "$SCRIPT_DIR/.."
    if [ -f "gradlew" ]; then
        echo "Starting interactive shell..."
        cd "$SCRIPT_DIR"
        if kotlinc interactive-cli.kt -include-runtime -d interactive-temp.jar 2>/dev/null; then
            java -jar interactive-temp.jar
            rm -f interactive-temp.jar
            exit 0
        fi
    else
        echo "Gradle wrapper not found. Please run from parent directory."
        exit 1
    fi
fi

# Check if Kotlin is available
if command -v kotlin &> /dev/null; then
    echo "Using Kotlin runtime..."
    cd "$SCRIPT_DIR"
    if kotlinc run-cli-simple.kt -include-runtime -d cli-temp.jar 2>/dev/null; then
        java -jar cli-temp.jar "$@"
        rm -f cli-temp.jar
        exit 0
    else
        echo "Kotlin compilation failed, trying Java fallback..."
    fi
fi

# Check if Java is available
if command -v java &> /dev/null; then
    echo "Kotlin not found, using Java with simple compilation..."
    cd "$SCRIPT_DIR"
    
    # Try to compile and run the simple version with Java
    if command -v javac &> /dev/null; then
        # Convert Kotlin to Java-compatible version
        echo "Please install Kotlin for full functionality, or use the Gradle build from parent directory"
        echo "Run: cd .. && ./gradlew :TPipe-Bedrock:run"
        exit 1
    fi
fi

# Neither Kotlin nor Java found
echo "Error: Neither Kotlin nor Java runtime found."
echo "Please install one of the following:"
echo "  - Kotlin: brew install kotlin"
echo "  - Java 8+: brew install openjdk"
exit 1