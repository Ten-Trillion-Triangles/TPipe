package com.TTT

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configures JSON serialization for the TPipe Ktor application.
 */
fun Application.configureSerialization()
{
    install(ContentNegotiation)
    {
        json()
    }
    routing {
        get("/json/kotlinx-serialization")
        {
            call.respond(mapOf("hello" to "world"))
        }
    }
}
