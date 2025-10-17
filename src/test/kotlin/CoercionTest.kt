package com.TTT

import com.TTT.Util.extractJson
import com.TTT.Serializers.IntCoercionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Serializable
data class TestWeight(@Serializable(with = IntCoercionSerializer::class) val weight: Int)

class CoercionTest {

    @Test
    fun testCoercionWorks() {
        val jsonWithDouble = """{"weight": 0.9}"""
        
        val json = Json {
            coerceInputValues = true
            isLenient = true
        }
        
        try {
            val result = json.decodeFromString<TestWeight>(jsonWithDouble)
            println("✅ Coercion works: ${result.weight}")
            assertEquals(0, result.weight) // 0.9 should coerce to 0
        } catch (e: Exception) {
            println("❌ Coercion failed: ${e.message}")
            throw e
        }
    }
    
    @Test
    fun testExtractJsonCoercion() {
        val jsonWithDouble = """{"weight": 0.9}"""
        
        val result = extractJson<TestWeight>(jsonWithDouble)
        assertNotNull(result)
        println("✅ extractJson coercion works: ${result!!.weight}")
    }
}
