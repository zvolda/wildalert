package com.wildalert.notification

import com.wildalert.notification.sms.TwilioProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

// No DataSource unless idempotency.store=postgres asks for one (PostgresIdempotencyConfig),
// so the service still starts with no database.
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
@EnableConfigurationProperties(TwilioProperties::class)
class NotificationApplication

fun main(args: Array<String>) {
    runApplication<NotificationApplication>(*args)
}
