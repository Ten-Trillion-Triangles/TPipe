package com.TTT.Util

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@Serializable
data class ChangesToMake(
    val narrative_perspective: String = "",
    val dialogue_tags: String = "",
    val descriptive_ownership: String = "",
    val collective_actions: String = "",
    val internal_monologue: String = "",
    val scene_transitions: String = "",
    val emotional_connections: String = "",
    val temporal_references: String = "",
    val sensory_experiences: String = "",
    val group_dynamics: String = ""
)

@Serializable
data class TestResponse(
    val needsChanges: Boolean = false,
    val changesToMake: ChangesToMake = ChangesToMake()
)

class JsonRepairTest
{
    @Test
    fun testOriginalMalformedJson()
    {
        val malformedJson = """{"needsChanges":true,"changesToMake":{"narrative_perspective":"Replace first-person pronouns (I, me, my, we, our) with third-person references to Marina/Chikako/Melike or 'the three friends'","dialogue_tags":"Maintain character voices but remove first-person narrative framing (e.g. 'I felt' becomes 'Marina noticed')","descriptive_ownership":"Convert 'our dormitory' to 'their dormitory', 'my memories' to 'Melike's memories'","collective_actions":"Change 'we dressed' to 'the girls dressed', 'we joined' to 'they joined'","internal_monologue":"Convert personal reflections to observational narration (e.g. 'I dreamed' becomes 'Marina recalled dreaming')","scene_transitions":"Replace first-person spatial awareness with omniscient descriptions (e.g. 'I found myself looking' becomes 'The view from the window revealed')","emotional_connections":"Convert personal bonds to third-person observations (e.g. 'we had found family' becomes 'their bond resembled family')","temporal_references":"Adjust time perception from personal to objective (e.g. 'we had been here' becomes 'two years had passed')","sensory_experiences":"Depersonalize descriptions (e.g. 'my ears flattened' becomes 'Marina's feline ears twitched')","group_dynamics":"Maintain plural references through named actions rather than 'we' (e.g. 'we developed a system' becomes 'the trio had developed')}}"""
        
        val result = deserialize<TestResponse>(malformedJson)
        
        assertNotNull(result)
        assertTrue(result!!.needsChanges)
        assertEquals("Replace first-person pronouns (I, me, my, we, our) with third-person references to Marina/Chikako/Melike or 'the three friends'", 
                    result.changesToMake.narrative_perspective)
    }
    
    @Test
    fun testMissingQuotesOnKeys()
    {
        val malformedJson = """{needsChanges: true, changesToMake: {narrative_perspective: "test"}}"""
        
        val result = deserialize<TestResponse>(malformedJson)
        
        assertNotNull(result)
        assertTrue(result!!.needsChanges)
    }
    
    @Test
    fun testTrailingCommas()
    {
        val malformedJson = """{"needsChanges": true, "changesToMake": {"narrative_perspective": "test",},}"""
        
        val result = deserialize<TestResponse>(malformedJson)
        
        assertNotNull(result)
        assertTrue(result!!.needsChanges)
    }
    
    @Test
    fun testExtractJsonData()
    {
        val malformedJson = """{needsChanges: true, changesToMake: {narrative_perspective: "test value"}}"""
        
        val extracted = extractJsonData(malformedJson)
        
        assertTrue(extracted.containsKey("needsChanges"))
        assertTrue(extracted.containsKey("narrative_perspective"))
        assertEquals("true", extracted["needsChanges"])
        assertEquals("test value", extracted["narrative_perspective"])
    }
    
    @Test
    fun testReflectionBasedReconstruct()
    {
        val severelyMalformed = """{needsChanges: true, changesToMake: broken_nested_object}"""
        
        val result = reflectionBasedReconstruct<TestResponse>(severelyMalformed)
        
        assertNotNull(result)
        assertTrue(result!!.needsChanges)
    }
}