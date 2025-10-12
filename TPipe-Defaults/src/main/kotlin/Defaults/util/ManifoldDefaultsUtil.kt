package Defaults.util

import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.TaskProgress
import com.TTT.Util.extractJson

/**
 * Saves the input that's set as converse history in place to avoid it getting stomped later. We will need it
 * as pipeline context at the second stage.
 */
suspend fun preInitEntryPipe(content: MultimodalContent)
{
    val context = ContextWindow()
    context.contextElements.add(content.text)
    content.workspaceContext.contextMap["history"] = context
}

suspend fun transformEntryPipe(content: MultimodalContent) : MultimodalContent
{
    //Null saftey is irrelevant here because the validation function would have handled this problem already.
    val result = extractJson<TaskProgress>(content.text) ?: TaskProgress()

    if(result.isTaskComplete)
    {
        content.passPipeline = true //End the manifold because the task is finished.
    }

    content.context = content.workspaceContext.contextMap["history"] ?: ContextWindow()
    return content
}