package com.wildalert.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
class GatewayApplicationTests {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun contextLoads() {
    }

    @Test
    fun `starts with no Google credentials, so local dev and CI need no GCP setup`() {
        // GcpIdTokenUserAccountAuth calls GoogleCredentials.getApplicationDefault() in its
        // constructor. If it were not conditional on services.user-account.auth, this context
        // would fail to start on any machine without ADC -- including a build worker.
        assertThat(context.getBeansOfType(UserAccountAuth::class.java)).isEmpty()
    }
}
