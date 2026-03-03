package env

import kotlinx.serialization.Serializable

/**
 * Represents an Ollama model and its configuration.
 * @property model The name of the model.
 */
@Serializable
data class Model(var model: String)
{
    /** Name of model to create from */
    var from: String? = null
    /** A dictionary of file names to SHA256 digests of blobs to create the model from */
    var files: List<String>? = null
    /** A dictionary of file names to SHA256 digests of blobs for LORA adapters */
    var adapters: List<String>? = null
    /** The prompt template for the model */
    var template: String? = null
    /** A string or list of strings containing the license or licenses for the model */
    var license: String? = null
    /** A string containing the system prompt for the model */
    var system: String? = null
    /** A dictionary of parameters for the model */
    var parameters: OllamaOptions? = null
    /** A list of message objects used to create a conversation */
    var messages: List<OllamaMessage>? = null
    /** Whether to stream the response */
    var stream: Boolean = false
    /** Whether to quantize the model */
    var quantize: Boolean = false
    /** Whether to include verbose output */
    var verbose: Boolean = false
    /** Destination for model copying */
    var destination: String? = null
    /** Whether to allow insecure connections for pulling/pushing */
    var insecure: Boolean = false
    /** Inference options */
    var options: OllamaOptions? = null
}
