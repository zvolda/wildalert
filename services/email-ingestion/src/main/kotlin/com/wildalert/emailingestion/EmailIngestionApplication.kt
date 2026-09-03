package com.wildalert.emailingestion

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EmailIngestionApplication

fun main(args: Array<String>) {
    runApplication<EmailIngestionApplication>(*args)
}
