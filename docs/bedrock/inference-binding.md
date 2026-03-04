# AWS Bedrock Inference Binding - Cross-Region Routing

AWS Bedrock inference binding is the configuration system that maps foundation models to specific **Inference Profiles** (ARNs). This enables cross-region model access and optimized model routing, ensuring your TPipe infrastructure can reach high-performance models regardless of your local region's availability.

Think of inference binding as the **Global Routing Table** for your AWS refinery.

## Table of Contents
- [What is Inference Binding?](#what-is-inference-binding)
- [Why Binding is Mandatory](#why-binding-is-mandatory)
- [Configuration Methods](#configuration-methods)
- [Cross-Region Logic](#cross-region-logic)
- [Best Practices](#best-practices)

---

## What is Inference Binding?

Inference binding is the process of linking a model ID (e.g., `amazon.nova-pro-v1:0`) to an AWS Inference Profile ARN. This system:
- **Enables Cross-Region Access**: Use models available in `us-east-1` from an application running in `eu-west-1`.
- **Consistent Routing**: Automatically routes calls through the most efficient regional endpoint.
- **Access Management**: Handles models that AWS mandates must be accessed via profiles rather than direct IDs.

---

## Why Binding is Mandatory

Several modern AWS foundation models have **Cross-Region Inference Requirements**. You cannot call these models using a simple ID; you must use an inference profile.

**Models Requiring Profiles:**
- `amazon.nova-pro-v1:0` (Nova Pro)
- `amazon.nova-lite-v1:0` (Nova Lite)
- `deepseek.r1-v1:0` (DeepSeek R1)
- `meta.llama4-maverick-...` (Latest Llama models)

---

## Configuration Methods: Mapping the Mainline

TPipe-Bedrock provides three ways to manage your routing table.

### 1. The Inference Configuration File
The standard way to define bindings. TPipe looks for a file at `~/.aws/inference.txt`.

**File Format (`modelId=profileARN`):**
```text
amazon.nova-pro-v1:0=arn:aws:bedrock:us-east-1:123456789:inference-profile/us.amazon.nova-pro-v1:0
deepseek.r1-v1:0=arn:aws:bedrock:us-east-2:123456789:inference-profile/us.deepseek.r1-v1:0
```

### 2. Programmatic Binding (In Code)
You can define bindings dynamically at runtime using the `bedrockEnv` singleton. This is the recommended method for cloud-native applications.

```kotlin
import env.bedrockEnv

// Register the binding before the pipe is initialized
bedrockEnv.bindInferenceProfile(
    "amazon.nova-pro-v1:0", 
    "arn:aws:bedrock:us-east-1:123456789:inference-profile/us.amazon.nova-pro-v1:0"
)
```

### 3. The CLI Configuration Tool
TPipe includes a shell script for managing your `inference.txt` file interactively.

```bash
# Search for models and bind them via the CLI
./inference-config.sh bind "amazon.nova-pro-v1:0" "arn:aws:bedrock:..."
```

---

## Cross-Region Logic: How the Flow Routes

When a `BedrockPipe` is executed, TPipe follows this routing logic:
1.  **Lookup**: It checks the `bedrockEnv` for a binding for the requested model.
2.  **Substitution**: If a binding exists, TPipe swaps the model ID for the ARN in the final AWS API call.
3.  **Handoff**: The AWS network handles the regional handoff, delivering the request to the high-capacity refinery where the model resides.

---

## Best Practices

*   **Initialize Early**: Always call `bedrockEnv.loadInferenceConfig()` or perform your manual `bindInferenceProfile` calls during application startup, before any pipelines are executed.
*   **Use Regional ARNs**: Ensure your inference profile ARNs match the regions where you have requested model access in the AWS Console.
*   **IAM Permissions**: Your IAM role must have permission to `bedrock:InvokeModel` on the specific **Inference Profile Resource ARN**, not just the base model.
*   **Environment Sync**: Use different `inference.txt` files for development and production to ensure your agents always draw from the correct regional capacity.

## Next Steps

Now that you can route data across regions, return to the provider overview to see other Bedrock capabilities.

**→ [Getting Started with TPipe-Bedrock](getting-started.md)** - Setup, configuration, and first steps.
