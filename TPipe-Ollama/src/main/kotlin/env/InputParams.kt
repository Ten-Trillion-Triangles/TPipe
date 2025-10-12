package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InputParams(var model: String, var prompt: String = ""){

    @Serializable
    var messages: MutableList<message>? = null

    @Serializable
    var tools: MutableList<String>? = null
    //tools are not in use for us

    @Serializable
    var suffix: String?= null
    //non-chat only

    @Serializable
    var think: Boolean = false

    @Serializable
    var format: String?= null

    @Serializable
    var options:OllamaOptions?= null
    //this one needs addressing =Ollamaoptions?

    @Serializable
    var truncate: Boolean = true
    //for embed

    @Serializable
    var embedding: Boolean = false

    @Serializable
    var embed: Boolean = false

    @Serializable
    var stream: Boolean = false

    @Serializable
    var keep_alive: Int = 300
}

