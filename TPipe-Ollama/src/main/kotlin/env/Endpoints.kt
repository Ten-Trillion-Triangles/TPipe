package env

/**
 * Global container that holds all Ollama API endpoints. Provides shorthand acsess to
 * all the api endpoints to simplify api calls for the programmer.
 */
object Endpoints
{
    private var ip = "127.0.0.1"
    private var port = 11434

    val generateEndpoint = "http://$ip:$port/api/generate"
    val chatEndpoint = "http://$ip:$port/api/chat"
    val pullEndpoint = "http://$ip:$port/api/pull"
    val pushEndpoint = "http://$ip:$port/api/push"
    val createEndpoint = "http://$ip:$port/api/create"
    val deleteEndpoint = "http://$ip:$port/api/delete"
    val copyEndpoint = "http://$ip:$port/api/copy"
    val listEndpoint = "http://$ip:$port/api/tags"
    val runningEndpoint = "http://$ip:$port/api/ps"
    val showEndpoint = "http://$ip:$port/api/show"
    val embeddingsEndpoint = "http://$ip:$port/api/embeddings"
    val versionEndpoint = "http://$ip:$port/api/version"

    /**
     * Required if the Ollama server is not running on the same machine as TPipe.
     * @param ip The IP address of the Ollama server.
     * @param port The port number that the Ollama server is listening on.
     */
    fun init(ip: String, port: Int)
    {
        this.ip = ip
        this.port = port
    }
}