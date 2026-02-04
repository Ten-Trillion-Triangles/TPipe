package bedrockPipe

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.test.*

// Case 1: Parent with body property (no annotation)
@Serializable
abstract class ParentNoAnnotation {
    var pName: String = "parentDefault"
}

@Serializable
class ChildInheritNoAnnotation : ParentNoAnnotation() {
    var cName: String = "childDefault"
}

// Case 2: Parent with body property (WITH annotation)
@Serializable
open class ParentWithAnnotation {
    @Serializable
    var pName: String = "parentDefault"
}

@Serializable
class ChildInheritWithAnnotation : ParentWithAnnotation() {
    var cName: String = "childDefault"
}

class SerializationInheritanceTest {

    @Test
    fun `test inherited body property WITHOUT annotation`() {
        val child = ChildInheritNoAnnotation()
        child.pName = "parentChanged"
        child.cName = "childChanged"
        
        val json = Json.encodeToString(child)
        println("ChildInheritNoAnnotation: $json")
        
        // Hypothesis: pName is NOT serialized because it's not in Child's constructor 
        // and Parent's serializer might not be invoked for body properties of Child?
        // Let's see.
        assertTrue(json.contains("pName"), "Parent body property should be serialized")
        assertTrue(json.contains("parentChanged"), "Value should be preserved")
    }

    @Test
    fun `test inherited body property WITH annotation`() {
        val child = ChildInheritWithAnnotation()
        child.pName = "parentChanged"
        child.cName = "childChanged"
        
        val json = Json.encodeToString(child)
        println("ChildInheritWithAnnotation: $json")
        
        assertTrue(json.contains("pName"), "Parent body property with annotation should be serialized")
    }
}
