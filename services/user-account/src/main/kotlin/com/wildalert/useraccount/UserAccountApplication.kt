package com.wildalert.useraccount

import com.wildalert.useraccount.password.PasswordResetProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(PasswordResetProperties::class)
class UserAccountApplication

fun main(args: Array<String>) {
    runApplication<UserAccountApplication>(*args)
}
