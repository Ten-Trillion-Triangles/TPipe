package com.TTT

import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

object TestCredentialUtils 
{
    fun hasAwsCredentials(): Boolean 
    {
        val accessKey = System.getenv("AWS_ACCESS_KEY_ID")
        val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY")
        val bearerToken = System.getenv("AWS_BEARER_TOKEN_BEDROCK")
        
        return (!accessKey.isNullOrEmpty() && !secretKey.isNullOrEmpty()) || !bearerToken.isNullOrEmpty()
    }
    
    fun requireAwsCredentials() 
    {
        assumeTrue(hasAwsCredentials(), "Skipping test - AWS credentials not found (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or AWS_BEARER_TOKEN_BEDROCK)")
    }
}