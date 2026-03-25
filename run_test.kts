import com.TTT.Util.semanticCompress
import com.TTT.Util.SemanticCompressionSettings

val settings = SemanticCompressionSettings(additionalStopWords = setOf("I", "We"))
val input = "I and We should go. I think We are ready."
val result = semanticCompress(input, settings)
println("Compressed: " + result.compressedText)
