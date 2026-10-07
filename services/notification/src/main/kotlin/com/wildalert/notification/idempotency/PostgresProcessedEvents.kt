package com.wildalert.notification.idempotency

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import javax.sql.DataSource

/**
 * Real ProcessedEvents backed by Postgres, so the memory survives restarts and is shared by every
 * replica. Active only when idempotency.store=postgres.
 *
 * `insert ... on conflict do nothing` makes the claim a single atomic statement: whichever replica
 * inserts the row wins and the other gets 0 rows back, so there is no read-then-write race.
 */
@Component
@ConditionalOnProperty(name = ["idempotency.store"], havingValue = "postgres")
class PostgresProcessedEvents(
    private val jdbc: JdbcTemplate,
) : ProcessedEvents {

    override fun claim(sourceEventId: UUID): Boolean {
        val inserted = jdbc.update(
            "insert into notification.processed_event (source_event_id) values (?) on conflict do nothing",
            sourceEventId,
        )
        return inserted == 1
    }

    override fun release(sourceEventId: UUID) {
        jdbc.update("delete from notification.processed_event where source_event_id = ?", sourceEventId)
    }
}

/**
 * Builds the DataSource only for the Postgres store. DataSourceAutoConfiguration is switched off in
 * [com.wildalert.notification.NotificationApplication], so with the default in-memory store the app
 * has no DataSource at all and starts with no database available — Flyway and JdbcTemplate
 * auto-configuration both stand down when there is no DataSource bean.
 */
@Configuration
@ConditionalOnProperty(name = ["idempotency.store"], havingValue = "postgres")
class PostgresIdempotencyConfig {

    /** Binds spring.datasource.* (url/username/password) the same way Boot normally would. */
    @Bean
    @ConfigurationProperties("spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    // Goes through DataSourceProperties rather than binding onto the pool directly: Hikari calls
    // the URL "jdbcUrl", so spring.datasource.url would silently not reach it.
    @Bean
    fun dataSource(properties: DataSourceProperties): DataSource =
        properties.initializeDataSourceBuilder().build()
}
