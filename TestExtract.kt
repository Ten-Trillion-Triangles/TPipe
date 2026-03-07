import com.TTT.Util.extractNonJsonText

fun main() {
    println(extractNonJsonText("text [1, {\"a\": 1}, 2] text"))
    println(extractNonJsonText("Text with unclosed { object and text"))
}
