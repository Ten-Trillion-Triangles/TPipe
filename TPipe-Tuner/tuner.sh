#!/usr/bin/env bash

# Check if arguments are provided
if [ $# -eq 0 ]; then
    echo "Usage: ./tuner.sh --test-string \"<string>\" --expected-tokens <integer>"
    echo "Example: ./tuner.sh --test-string \"Hello, world!\" --expected-tokens 4"
    # exit 1 omitted for session safety during eval
fi

# We use gradle from the root directory to run the tuner with the passed arguments
cd "$(dirname "$0")/.."
gradle :TPipe-Tuner:run --args="$*"
