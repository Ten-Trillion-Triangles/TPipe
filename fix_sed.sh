#!/bin/bash
# First clean up the previously appended duplicate blocks
# Since git checkout didn't work (maybe file was not committed), let's use git restore or just clean up manually

git restore TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt
