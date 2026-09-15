package com.wildalert.emailingestion

import com.wildalert.emailingestion.storage.FileSystemProperties
import com.wildalert.emailingestion.storage.R2Properties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(R2Properties::class, FileSystemProperties::class)
class EmailIngestionApplication

fun main(args: Array<String>) {
    runApplication<EmailIngestionApplication>(*args)
}
