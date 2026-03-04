import re

with open("src/main/kotlin/Debug/TraceVisualizer.kt", "r") as f:
    content = f.read()

search = r"""                val reasoningKey = event\.metadata\.keys\.find \{ it in reasoningKeys \}
                val inputKey = event\.metadata\.keys\.find \{ it == "inputText" \}
                val outputKey = event\.metadata\.keys\.find \{ it == "outputText" \}

                val keysToExtract = setOfNotNull\(reasoningKey, inputKey, outputKey\)"""

replace = r"""                val reasoningKey = event.metadata.keys.find { it in reasoningKeys }
                val inputKey = event.metadata.keys.find { it == "inputText" }
                val outputKey = event.metadata.keys.find { it == "outputText" }
                val requestObjectKey = event.metadata.keys.find { it == "requestObject" }
                val generatedContentKey = event.metadata.keys.find { it == "generatedContent" }

                val keysToExtract = setOfNotNull(reasoningKey, inputKey, outputKey, requestObjectKey, generatedContentKey)"""

content = re.sub(search, replace, content)

search2 = r"""                // Add outputText
                val outputText = outputKey\?\.let \{ event\.metadata\[it\]\?\.toString\(\) \} \?:
                    if \(event\.eventType == TraceEventType\.PIPE_SUCCESS \|\| event\.eventType == TraceEventType\.API_CALL_SUCCESS\)
                        event\.content\?\.text
                    else null

                if \(!outputText\.isNullOrBlank\(\) && outputText != "N/A" && outputText != "null"\) \{
                    sections\.add\(createExpandableSection\("Output Content", outputText, "📤", "#17a2b8"\)\)
                \}

                // Add reasoning content in an expandable section"""

replace2 = r"""                // Add outputText
                val outputText = outputKey?.let { event.metadata[it]?.toString() } ?:
                    if (event.eventType == TraceEventType.PIPE_SUCCESS || event.eventType == TraceEventType.API_CALL_SUCCESS)
                        event.content?.text
                    else null

                if (!outputText.isNullOrBlank() && outputText != "N/A" && outputText != "null") {
                    sections.add(createExpandableSection("Output Content", outputText, "📤", "#17a2b8"))
                }

                // Add requestObject
                val requestObject = requestObjectKey?.let { event.metadata[it]?.toString() }
                if (!requestObject.isNullOrBlank() && requestObject != "N/A" && requestObject != "null") {
                    sections.add(createExpandableSection("Request Object", requestObject, "📦", "#6c757d"))
                }

                // Add generatedContent
                val generatedContent = generatedContentKey?.let { event.metadata[it]?.toString() }
                if (!generatedContent.isNullOrBlank() && generatedContent != "N/A" && generatedContent != "null") {
                    sections.add(createExpandableSection("Generated Content", generatedContent, "✨", "#fd7e14"))
                }

                // Add reasoning content in an expandable section"""

content = re.sub(search2, replace2, content)

with open("src/main/kotlin/Debug/TraceVisualizer.kt", "w") as f:
    f.write(content)
