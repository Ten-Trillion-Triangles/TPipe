import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import kotlinx.coroutines.runBlocking

object TestCredentialUtils 
{
    fun hasAwsCredentials(): Boolean 
    {
        return try {
            runBlocking {
                val credentialsProvider = DefaultChainCredentialsProvider()
                val credentials = credentialsProvider.resolve()
                !credentials.accessKeyId.isNullOrEmpty() && !credentials.secretAccessKey.isNullOrEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun requireAwsCredentials() 
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(hasAwsCredentials() && allowTest == "true", "Skipping test - AWS credentials not found in credential chain")
    }
}