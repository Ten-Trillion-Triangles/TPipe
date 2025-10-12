package Util

fun cleanJsonString(input: String): String
{
    val firstBrace = input.indexOf('{')
    val lastBrace = input.lastIndexOf('}')
    return if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
        input.substring(firstBrace, lastBrace + 1)
    } else {
        input
    }
}