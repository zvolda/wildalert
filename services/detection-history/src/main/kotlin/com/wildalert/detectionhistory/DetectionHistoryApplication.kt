package com.wildalert.detectionhistory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.runApplication

/**
 * Detection history: the hunter-facing record of what has been photographed.
 *
 * It consumes the same `animal.recognized` events notification does — a second subscription on
 * the one topic, which is the point of a broker: adding a reader changes nothing about the
 * publisher or the existing consumer, and if this service is down the SMS still goes out.
 *
 * No DataSource unless history.store=postgres asks for one (PostgresHistoryConfig), so the
 * service — and its tests — still start with no database. Spring Boot's jOOQ auto-configuration
 * needs a DataSource bean and stands down when there is none, exactly like Flyway does.
 */
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
class DetectionHistoryApplication

fun main(args: Array<String>) {
    runApplication<DetectionHistoryApplication>(*args)
}
