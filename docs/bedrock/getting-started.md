# Getting Started with TPipe-Bedrock

TPipe-Bedrock is the high-pressure "refinery" for the TPipe ecosystem. It provides industrial-grade access to AWS Bedrock's foundation models—including Claude, Titan, and Llama—through the unified TPipe Pipe and Pipeline interface.

By connecting your infrastructure to Bedrock, you gain the reliability, security, and scale of AWS while maintaining the deterministic control of TPipe's "plumbing."

## Table of Contents
- [What is TPipe-Bedrock?](#what-is-tpipe-bedrock)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [AWS Configuration](#aws-configuration)
- [Your First Bedrock Pipe](#your-first-bedrock-pipe)
- [Model Catalog](#model-catalog)
- [Service Tiers: Managing Flow Capacity](#service-tiers-managing-flow-capacity)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## What is TPipe-Bedrock?

TPipe-Bedrock enables you to:
- **Unified Interface**: Access all Bedrock models through the same `Pipe` API.
- **Enterprise Scale**: Leverage AWS's high-availability infrastructure for massive agent swarms.
- **DITL & PCP Integration**: Use Bedrock models for complex tool-calling and developer-in-the-loop validation.
- **Cross-Region Inference**: Route data across regions for optimal performance and capacity.

## Prerequisites

### 1. AWS Account & Model Access
You must have an active AWS account. Crucially, **you must request access** to specific models (like Claude 3.5 Sonnet) in the AWS Bedrock Console before TPipe can use them.

### 2. IAM Permissions
Ensure your IAM user or role has the following "valve permissions":
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:InvokeModel",
                "bedrock:InvokeModelWithResponseStream"
            ],
            "Resource": "*"
        }
    ]
}
```

---

## Installation

Add the Bedrock "fitting" to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.TTT:TPipe-Core:1.0.0")
    implementation("com.TTT:TPipe-Bedrock:1.0.0")
}
```

> [!NOTE]
> TPipe strictly requires **Kotlin Gradle DSL**. Maven and Groovy Gradle are not supported.

---

## AWS Configuration: Setting the Flow

TPipe-Bedrock checks for credentials in this priority order:

1.  **Programmatic Keys**: Use `bedrockEnv.setKeys()` (best for desktop apps or dynamic credential fetching).
2.  **Environment Variables**: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`.
3.  **AWS Credentials File**: `~/.aws/credentials`.
4.  **IAM Roles**: Automatically detected if running on EC2, ECS, or Lambda.

### Programmatic Example:
```kotlin
import env.bedrockEnv
import bedrockPipe.BedrockPipe

// Configure the environment before the pipe starts
bedrockEnv.setKeys(public = "AKIA...", secret = "wJal...")

val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
```

---

## Your First Bedrock Pipe

```kotlin
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() {
    val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        .setSystemPrompt("You are an expert hydraulic engineer.")
        .setTemperature(0.7)
    
    runBlocking {
        val result = pipe.execute("Explain how a pressure relief valve works.")
        println(result.text)
    }
}
```

---

## Service Tiers: Managing Flow Capacity

AWS Bedrock offers different Pressure Tiers to optimize for speed vs. cost. TPipe allows you to set these explicitly on your Pipe.

| Tier | Use Case |
| :--- | :--- |
| **Standard** | (Default) Consistent performance for routine tasks. |
| **Priority** | High-speed flow for customer-facing or mission-critical agents. |
| **Flex** | Cost-effective processing for background agentic workflows. |
| **Reserved** | Guaranteed capacity for 24/7 industrial applications. |

```kotlin
val priorityPipe = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    .setServiceTier(BedrockPriorityTier.Priority) // High-speed mainline
```

---

## Best Practices

*   **Model Matching**: Use **Haiku** for fast Scrubbing (validation), **Sonnet** for general Flow (logic), and **Opus** for complex Architectural (reasoning) tasks.
*   **Low Temperature**: For structured JSON output, set temperature to **0.0** to prevent the model from "leaking" out of the schema.
*   **Trace Every Turn**: Use `enableTracing()` to monitor latency and token consumption at the Bedrock level.
*   **Graceful Retries**: Enable `autoRetry = true` in your pipeline to handle transient AWS "Rate Exceeded" or network bursts automatically.

---

## Troubleshooting

> [!CAUTION]
> **"Model Access Denied"**: This is almost always because the model hasn't been enabled in your AWS Console for the specific region you are calling.

> [!IMPORTANT]
> **"Rate Exceeded"**: You've exceeded your AWS account's TPS (Transactions Per Second) quota. Consider using a higher service tier or implementing a `setRetryFunction` on your Pipe.

## Next Steps

For advanced multi-region infrastructure, learn about inference binding.

**→ [AWS Bedrock Inference Binding](inference-binding.md)** - Cross-region model access and configuration.
