package com.TTT.AgentCore

import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider

/** Immutable configuration shared by AgentCore data and control clients. */
data class AgentCoreConfig(
    val region: String,
    val credentialsProvider: CredentialsProvider? = null,
    val userAgent: String = "TPipe-AgentCore/1.0.0"
)
