package com.example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8000

    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    // Turns on JSON formatting
    install(ContentNegotiation) {
        gson { }
    }

    // Connects this file to your routing.kt file!
    configureRouting()
}