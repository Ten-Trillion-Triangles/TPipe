#!/bin/bash

# AWS Bedrock Inference Profile Configuration Tool
# Simplified script to run InferenceConfigCli

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."

cd "$PROJECT_DIR"

# Try to run the CLI with existing JARs
if [ -f "build/libs/TPipe-0.0.1.jar" ] && [ -f "TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar" ]; then
    kotlin -cp "build/libs/TPipe-0.0.1.jar:TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar" cli.InferenceConfigCli "$@"
    exit 0
fi

# Build if JARs don't exist
echo "Building project..."
if command -v gradle &>/dev/null; then
    gradle :TPipe-Bedrock:assemble
elif [ -f "gradlew" ]; then
    ./gradlew :TPipe-Bedrock:assemble
else
    echo "ERROR: No gradle found and no gradlew script"
    exit 1
fi

# Run after build
kotlin -cp "build/libs/TPipe-0.0.1.jar:TPipe-Bedrock/build/libs/TPipe-Bedrock-0.0.1.jar" cli.InferenceConfigCli "$@"