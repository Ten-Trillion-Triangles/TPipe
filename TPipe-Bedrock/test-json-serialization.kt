import kotlinx.serialization.json.*

fun main() {
    // Test buildJsonObject toString() serialization
    val testJson = buildJsonObject {
        put("temperature", 0.7)
        put("max_tokens", 1000)
        put("top_p", 0.9)
        put("nested", buildJsonObject {
            put("scale", 0.0)
        })
        put("array", JsonArray(listOf("stop1", "stop2").map { JsonPrimitive(it) }))
    }
    
    println("buildJsonObject toString():")
    println(testJson.toString())
    println()
    
    println("Type: ${testJson::class.simpleName}")
    println("Is valid JSON: ${testJson.toString().startsWith("{") && testJson.toString().endsWith("}")}")
}
