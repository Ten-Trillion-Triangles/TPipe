#!/bin/bash

# AWS Bedrock Inference Profile Configuration Tool (macOS version)
# Optimized fallback chain: JAR -> Kotlin -> Build -> Fallback scripts

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
CLASSPATH="build/libs/TPipe-0.0.1.jar:TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar"

# Function to try running existing JAR with java
try_jar_java() {
    cd "$PROJECT_DIR"
    if [ -f "build/libs/TPipe-0.0.1.jar" ] && [ -f "TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar" ]; then
        echo "[1/5] Trying existing JAR with java..."
        if java -cp "$CLASSPATH" cli.InferenceConfigCli "$@" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

# Function to try running existing JAR with kotlin
try_jar_kotlin() {
    cd "$PROJECT_DIR"
    if [ -f "build/libs/TPipe-0.0.1.jar" ] && [ -f "TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar" ]; then
        echo "[2/5] Trying existing JAR with kotlin..."
        if kotlin -cp "$CLASSPATH" cli.InferenceConfigCli "$@" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

# Function to build and try with gradle
try_gradle_build() {
    local gradle_cmd="$1"
    shift
    
    cd "$PROJECT_DIR"
    echo "[3/5] Building with gradle and trying..."
    if "$gradle_cmd" :TPipe-Bedrock:assemble &>/dev/null; then
        if java -cp "$CLASSPATH" cli.InferenceConfigCli "$@" 2>/dev/null; then
            return 0
        fi
        if kotlin -cp "$CLASSPATH" cli.InferenceConfigCli "$@" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

# Function to try interactive CLI
try_interactive_cli() {
    cd "$SCRIPT_DIR"
    echo "[4/5] Falling back to interactive CLI..."
    if kotlinc interactive-cli.kt -include-runtime -d interactive-temp.jar 2>/dev/null; then
        java -jar interactive-temp.jar
        rm -f interactive-temp.jar
        return 0
    fi
    return 1
}

# Function to try simple CLI
try_simple_cli() {
    cd "$SCRIPT_DIR"
    echo "[5/5] Falling back to simple CLI..."
    if kotlinc run-cli-simple.kt -include-runtime -d cli-temp.jar 2>/dev/null; then
        java -jar cli-temp.jar "$@"
        rm -f cli-temp.jar
        return 0
    fi
    return 1
}

# Main execution logic
if [ "$1" = "--interactive" ] || [ "$1" = "-i" ]; then
    shift
    echo "Interactive mode requested..."
    
    # Try JAR methods first
    if try_jar_java "$@"; then exit 0; fi
    if command -v kotlin &>/dev/null && try_jar_kotlin "$@"; then exit 0; fi
    
    # Try building
    if command -v gradle &>/dev/null && try_gradle_build gradle "$@"; then exit 0; fi
    if try_gradle_build "bash gradlew" "$@"; then exit 0; fi
    
    # Fall back to interactive CLI
    if command -v kotlin &>/dev/null && try_interactive_cli; then exit 0; fi
else
    echo "Command mode: $*"
    
    # Try JAR methods first
    if try_jar_java "$@"; then exit 0; fi
    if command -v kotlin &>/dev/null && try_jar_kotlin "$@"; then exit 0; fi
    
    # Try building
    if command -v gradle &>/dev/null && try_gradle_build gradle "$@"; then exit 0; fi
    if try_gradle_build "bash gradlew" "$@"; then exit 0; fi
    
    # Fall back to simple CLI
    if command -v kotlin &>/dev/null && try_simple_cli "$@"; then exit 0; fi
fi

# All methods failed
echo "ERROR: All CLI methods failed."
echo "Requirements:"
echo "  - Java runtime for JAR execution"
echo "  - Kotlin runtime for standalone scripts"
echo "  - Gradle for building"
exit 1