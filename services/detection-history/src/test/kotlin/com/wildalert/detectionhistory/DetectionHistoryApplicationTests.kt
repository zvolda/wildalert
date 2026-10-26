package com.wildalert.detectionhistory

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class DetectionHistoryApplicationTests {

    @Test
    fun contextLoads() {
        // Verifies the Spring application context starts successfully -- and, because the default
        // store is in-memory, that it does so with no database and no broker available.
    }
}
