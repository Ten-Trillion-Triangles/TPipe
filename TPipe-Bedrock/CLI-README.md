# AWS Bedrock Inference Profile Configuration CLI

Command line tool for managing AWS Bedrock model to inference profile mappings in TPipe-Bedrock.

## Quick Start

```bash
# Interactive mode
kotlin run-cli.kt

# List all models
kotlin run-cli.kt list

# Search for Claude models
kotlin run-cli.kt search claude

# List models by provider
kotlin run-cli.kt provider anthropic

# Bind a model to inference profile
kotlin run-cli.kt bind anthropic.claude-3-sonnet-20240229-v1:0 my-profile-id
```

## Features

- **Interactive Mode**: Menu-driven interface for easy configuration
- **Model Search**: Find models by name, provider, or region
- **Profile Binding**: Map models to inference profiles or direct calls
- **Regional Filtering**: Show only models available in specific AWS regions
- **Current Status**: View all configured bindings

## Commands

| Command | Description | Example |
|---------|-------------|---------|
| `list [filter]` | List all models or filter by term | `kotlin run-cli.kt list claude` |
| `search <term>` | Search models containing term | `kotlin run-cli.kt search anthropic` |
| `provider <name>` | List models by provider | `kotlin run-cli.kt provider meta` |
| `bind <model> <profile>` | Bind model to inference profile | `kotlin run-cli.kt bind model-id profile-id` |
| `help` | Show help information | `kotlin run-cli.kt help` |

## Configuration File

The tool manages `~/.aws/inference.txt` with format:
```
model-id=inference-profile-id
anthropic.claude-3-sonnet-20240229-v1:0=my-claude-profile
meta.llama3-70b-instruct-v1:0=
```

Empty profile ID means direct model calls (no inference profile).

## Supported Providers

- **Anthropic**: Claude 3.5 Sonnet, Claude 3 Opus/Sonnet/Haiku
- **Meta**: Llama 3.1/3.2/3.3/4 models
- **Amazon**: Nova, Titan models
- **Mistral**: Large, Small, 7B, Mixtral models
- **Cohere**: Command R+, Command R, Command models
- **AI21**: Jamba models
- **DeepSeek**: R1 models (inference profile only)
- **OpenAI**: GPT models (us-west-2 only)

## Regional Availability

- **US East/West**: All models available
- **EU**: Most models except OpenAI, Llama 405B, Writer Palmyra
- **Asia Pacific**: Anthropic, Amazon Nova/Titan models
- **Canada**: Claude, Titan, Llama 3, Mistral models

## Requirements

- Kotlin compiler
- AWS credentials configured
- TPipe-Bedrock project structure