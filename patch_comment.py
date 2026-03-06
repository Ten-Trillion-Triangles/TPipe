import sys

file_path = "TPipe-Defaults/src/main/kotlin/Defaults/reasoning/ReasoningBuilder.kt"
with open(file_path, "r") as f:
    content = f.read()

target = """        if(settings.numberOfRounds <= 1)
        {
            settings.numberOfRounds = 1
        }"""

replacement = """        if(settings.numberOfRounds <= 1)
        {
            /**
             * Clamp to 1 so we don't have to ever assume 0 is a number we're having to account for when handling
             * more reasoning rounds. This also ensures we're defended against any divide by zero errors.
             */
            settings.numberOfRounds = 1
        }"""

if target in content:
    content = content.replace(target, replacement)
    with open(file_path, "w") as f:
        f.write(content)
    print("Comment restored successfully")
else:
    print("Could not find target block")
