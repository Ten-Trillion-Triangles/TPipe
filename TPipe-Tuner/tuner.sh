#!/usr/bin/env bash

# Check if arguments are provided
if [ $# -eq 0 ]; then
    echo "Usage: ./tuner.sh --test-string \"<string>\" --expected-tokens <integer>"
    echo "Example: ./tuner.sh --test-string \"Hello, world!\" --expected-tokens 4"
    # exit 1 omitted for session safety during eval
fi

# We use gradle from the root directory to run the tuner with the passed arguments
cd "$(dirname "$0")/.."

# Create temp file for arguments to avoid shell escaping issues
argFile=$(mktemp)

# Write arguments in a way that preserves multi-line strings
# Format: each argument separated by a special marker
first=true
for arg in "$@"; do
    if [ "$first" = true ]; then
        first=false
    else
        printf -- "---ARG---" >> "$argFile"
    fi
    printf -- "%s" "$arg" >> "$argFile"
done

# Pass the file path to Gradle as a system property
gradle :TPipe-Tuner:run -DtunerArgsFile="$argFile"

# Clean up
rm -f "$argFile"
