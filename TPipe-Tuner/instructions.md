# TPipe Tuner Instructions for LLM Agents

## Overview
The **TPipe Tuner** is a command-line application designed to help developers and AI agents figure out the optimal token counting settings for specific LLM models.

TPipe uses an internal `Dictionary` and `TruncationSettings` system to estimate how many tokens a given string uses. Since different LLM providers (e.g., OpenAI, Anthropic, Google, DeepSeek) have their own tokenization algorithms, TPipe provides tunable parameters (such as `tokenCountingBias`, `favorWholeWords`, `nonWordSplitCount`, etc.) to get its token estimation as close as possible to the target LLM's real token counting algorithm.

This tuner tool automates the process of finding the optimal `TruncationSettings`. It iterates over a large set of parameter combinations, finds the closest match to an expected token count, and then applies a fine-grained `tokenCountingBias` to perfectly hit the target.

## How to use the Tuner

As an LLM agent, when you are tasked with "tuning TPipe for a specific LLM model", you need to:

1. **Acquire a Test String and an Expected Token Count**
   The user should provide you with a test string and the exact number of tokens that string evaluates to on the target LLM model's official tokenizer.

2. **Invoke the Tuner Script**
   You can run the tuner tool by invoking the `tuner.sh` (Linux/macOS) or `tuner.bat` (Windows) scripts located in the root of the `TPipe-Tuner` module. You must pass the `--test-string` and `--expected-tokens` arguments.

   **Example:**
   ```bash
   ./TPipe-Tuner/tuner.sh --test-string "Hello world! This is a test." --expected-tokens 7
   ```

   **For large strings or JSON (recommended):**
   ```bash
   ./TPipe-Tuner/tuner.sh --test-string "$(cat your-file.json)" --expected-tokens 1305
   ```

3. **Read the Output**
   The application will run for a few seconds (or 1-2 minutes for large inputs) scanning combinations and applying bias. It will print the exact optimal configuration in a JSON block at the end of the output.

   **Sample Output:**
   ```json
   ================ OPTIMAL CONFIGURATION ================
   {
       "countSubWordsInFirstWord": true,
       "favorWholeWords": true,
       "countOnlyFirstWordFound": false,
       "splitForNonWordChar": true,
       "alwaysSplitIfWholeWordExists": false,
       "countSubWordsIfSplit": false,
       "nonWordSplitCount": 4,
       "tokenCountingBias": 0.05
   }
   =======================================================
   ```

4. **Apply the Settings**
   You can take the values returned in the JSON schema and map them into the `TruncationSettings` configuration for the specific LLM model in the codebase (for instance, inside the appropriate provider class like `TPipe-Ollama/src/main/kotlin/com/TTT/OllamaPipe.kt`).

## Notes for Agents

* The test string must be properly quoted when passing it as a command-line argument. Use double quotes around the entire string.
* For large strings, multi-line content, or JSON, use command substitution: `--test-string "$(cat file.txt)"` - this works reliably for any size input.
* The tuner uses a temp file internally to avoid shell escaping issues, so strings with spaces, newlines, quotes, and special characters are handled correctly.
* The tuner may take 1-2 minutes for very large strings (4000+ characters) as it tests 512 parameter combinations.
* Progress dots appear every 64 combinations for large inputs to show the tuner is working.
* The script calls Gradle (`gradle :TPipe-Tuner:run`) under the hood, so expect standard Gradle build output before the tuner output appears.
* If the tuner output says "Failed to find any viable combinations", verify that the expected token count makes sense for the size of the test string.
