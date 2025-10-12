package com.TTT.Context

import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcpContext
import java.util.UUID

/**
 * Determines chat role of a ConverseData object. This allows the llm to know what the context of what it's
 * talking to is.
 */
enum class ConverseRole
{
    developer,
    system,
    user,
    agent,
    assistant
}

/**
 * Data class that houses one turn of conversation if context is being stored as a chat format.
 * @param role Denotes the role of the thing conversing. LLM's sometimes care about this depending on the provider
 * and api structuring.
 * @param content TPipe multimodal content that makes up the actual generated output of the llm or user request.
 */
@kotlinx.serialization.Serializable
data class ConverseData(
    var role: ConverseRole,
    var content: MultimodalContent,
    private var uuid: String = ""
)
{
    //Assign uuid which we'll use for the equals operator overload.
    fun setUUID()
    {
        uuid = UUID.randomUUID().toString()
    }

    fun getUUID() : String
    {
        val id = uuid
        return id
    }
    
    override operator fun equals(other: Any?): Boolean
    {
        return other is ConverseData && uuid == other.uuid
    }
}

/**
 * Actual class that stores a user to agent conversation history. Houses an array of ConverseData to keep each
 * turn of conversation in order.
 */
@kotlinx.serialization.Serializable
data class ConverseHistory(
    val history:  MutableList<ConverseData> = mutableListOf()
)
{
    /**
     * Add to the converse history list using direct supply of role, content, and pcp.
     * This add will be ignored if this object already exists in the array.
     */
    fun add(role: ConverseRole, content: MultimodalContent)
    {
        val converseTurn = ConverseData(role = role, content = content)
        converseTurn.setUUID()

        if(history.contains(converseTurn)) return

        history.add(converseTurn)
    }

    /**
     * Add using existing converse data object. This add will be ignored if this object already exists in the array.
     */
    fun add(converseData: ConverseData)
    {
        if(converseData.getUUID().isEmpty()) converseData.setUUID()
        if(history.contains(converseData)) return
        history.add(converseData)
    }

}
