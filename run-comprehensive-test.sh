#!/bin/bash

export AWS_ACCESS_KEY_ID=AKIAXSZADVN7RIEI6KED
export AWS_SECRET_ACCESS_KEY=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

echo "=== RUNNING COMPREHENSIVE PIPELINE TEST ==="
echo "AWS Region: us-east-2"
echo "Model: deepseek.r1-v1:0"
echo "Features: Validator + Transformer + Failure Handler + Context Management"
echo ""

# Run the test and capture output
./gradlew :TPipe-Bedrock:test --tests BedrockTest.testComprehensivePipelineWithAllFeatures --console=plain 2>&1 | tee test-output.log

echo ""
echo "=== TEST EXECUTION COMPLETED ==="
echo "Check test-output.log for detailed results"