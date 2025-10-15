#!/bin/bash

# AWS Bedrock Inference Profile Configuration Tool
# Build and run script using Gradle

set -e

echo "Building TPipe-Bedrock CLI..."

# Build using Gradle
./gradlew compileKotlin

echo "Starting Inference Profile Configuration Tool..."
echo

# Run the CLI with arguments passed to script
java -cp "build/classes/kotlin/main:$(./gradlew -q printClasspath)" cli.InferenceConfigCli "$@"