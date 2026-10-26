package com.wildalert.detectionhistory.history

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Builds the DataSource only for the Postgres store. DataSourceAutoConfiguration is switched off
 * in [com.wildalert.detectionhistory.DetectionHistoryApplication], so with the default in-memory
 * store the app has no DataSource bean at all and starts with no database available — Flyway and
 * jOOQ auto-configuration both stand down when there is none.
 *
 * Copied from notification's PostgresIdempotencyConfig rather than extracted into a shared
 * module: four lines of wiring is a poor reason to couple two services' release cycles together.
 */
@Configuration
@ConditionalOnProperty(name = ["history.store"], havingValue = "postgres")
class PostgresHistoryConfig {

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
