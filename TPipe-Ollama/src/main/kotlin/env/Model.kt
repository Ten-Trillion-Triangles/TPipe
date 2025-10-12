package env

import kotlinx.serialization.Serializable

@Serializable
data class Model(var model: String){ //input is name of model
    var from: String? = null //name of model to create from
    var files: List<String>? = null //a dictionary of file names to SHA256 digests of blobs to create the model from
    var adapters: List<String>? = null //a dictionary of file names to SHA256 digests of blobs for LORA adapters
    var template: String? = null // the prompt template for the model
    var license: String? = null // a string or list of strings containing the license or licenses for the model
    var system: String? = null // a string containing the system prompt for the model
    var parameters: OllamaOptions? = null // a dictionary of parameters for the model
    var messages: List<message>? = null //a list of message objects used to create a conversation
    var stream: Boolean = false
    var quantize: Boolean = false
    var verbose: Boolean = false
    var destination: String? = null
    var insecure: Boolean = false //pull and push only
    var options: OllamaOptions? = null //Maybe required by some instances needing a model json.
}

