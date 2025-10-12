package env

import kotlinx.serialization.Serializable

@Serializable
data class message(val role: String, val content: String, val thinking: Boolean) {

    @Serializable
    var images: List<String>? = null

    @Serializable
    var Tool_Calls: List<String>? = null
}

