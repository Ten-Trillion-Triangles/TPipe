package com.TTT

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*



fun main() {
    
}

fun Application.module() {
    configureSerialization()
    configureRouting()
}
