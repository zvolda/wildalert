package com.wildalert.detectionhistory.history

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * The default has to stay the in-memory store, because that is what lets this service — and every
 * test in this module — start with no database. If `matchIfMissing` were ever dropped from
 * [InMemoryDetectionStore], the context would instead fail with no DetectionStore bean at all.
 *
 * The postgres branch is deliberately not covered here: wiring it up builds a DataSource and runs
 * Flyway, so asserting it needs a real database rather than a context test.
 */
@SpringBootTest
class DetectionStoreWiringTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the in-memory store is the default and needs no database`() {
        assertThat(context.getBeansOfType(DetectionStore::class.java)).hasSize(1)
        assertThat(context.getBean(DetectionStore::class.java)).isInstanceOf(InMemoryDetectionStore::class.java)
        assertThat(context.getBeansOfType(JooqDetectionStore::class.java)).isEmpty()
    }
}
