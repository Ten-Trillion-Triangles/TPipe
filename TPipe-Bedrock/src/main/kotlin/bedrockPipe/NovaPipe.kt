package bedrockPipe

import com.TTT.Enums.ProviderName
import com.TTT.Pipe.Pipe
import env.bedrockEnv

class NovaPipe : BedrockPipe() 
{
    init 
    {
        setProvider(ProviderName.Aws)
        setModel("amazon.nova-pro-v1:0")
        setRegion("us-east-2")
        setMaxTokens(2000)
        setTemperature(0.7)
        setTopP(0.9)
    }

    override suspend fun init(): Pipe 
    {
        return super.init()
    }
}