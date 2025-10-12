# AWS Bedrock Inference Profile Configuration CLI

Command line tool for managing AWS Bedrock model to inference profile mappings in TPipe-Bedrock.

## Quick Start

### Linux/Unix
```bash
./cli-config.sh list
```

### macOS
```bash
./cli-config.command list
# Or double-click cli-config.command in Finder
```

### Windows
```cmd
cli-config.bat list
# Or double-click cli-config.bat in Explorer
```

### Manual Runtime Selection
```bash
# With Kotlin
kotlin run-cli.kt list

# With Java/Gradle
./gradlew build
java -cp "build/classes/kotlin/main" cli.InferenceConfigCli list
```

## Features

- **Interactive Mode**: Menu-driven interface for easy configuration
- **Model Search**: Find models by name, provider, or region
- **Profile Binding**: Map models to inference profiles or direct calls
- **Regional Filtering**: Show only models available in specific AWS regions
- **Current Status**: View all configured bindings

## Commands

| Command | Description | Linux/Unix | macOS | Windows |
|---------|-------------|------------|-------|----------|
| `list [filter]` | List all models or filter by term | `./cli-config.sh list claude` | `./cli-config.command list claude` | `cli-config.bat list claude` |
| `search <term>` | Search models containing term | `./cli-config.sh search anthropic` | `./cli-config.command search anthropic` | `cli-config.bat search anthropic` |
| `provider <name>` | List models by provider | `./cli-config.sh provider meta` | `./cli-config.command provider meta` | `cli-config.bat provider meta` |
| `bind <model> <profile>` | Bind model to inference profile | `./cli-config.sh bind model-id profile-id` | `./cli-config.command bind model-id profile-id` | `cli-config.bat bind model-id profile-id` |
| `help` | Show help information | `./cli-config.sh help` | `./cli-config.command help` | `cli-config.bat help` |

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

### System Dependencies
- **Java 8+** (JRE/JDK)
- **Kotlin 1.9+** (for script execution)

### Installation Options

**Option 1: Install Kotlin (Recommended)**
```bash
# macOS with Homebrew
brew install kotlin

# Linux with SDKMAN
curl -s "https://get.sdkman.io" | bash
sdk install kotlin

# Manual download
# https://kotlinlang.org/docs/command-line.html
```

**Option 2: Use Gradle (JRE only)**
```bash
./gradlew build
java -cp "build/libs/tpipe-bedrock.jar:build/classes/kotlin/main" cli.InferenceConfigCli
```

### AWS Configuration
- AWS credentials configured via:
  - Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
  - AWS credentials file (`~/.aws/credentials`)
  - IAM roles (for EC2/Lambda)

### Verification
```bash
# Check Kotlin installation
kotlin -version

# Check Java installation
java -version
```