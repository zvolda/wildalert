package com.wildalert.useraccount

import com.wildalert.useraccount.password.EmailProperties
import com.wildalert.useraccount.password.PasswordResetProperties
import com.wildalert.useraccount.password.ResendProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(
    PasswordResetProperties::class,
    EmailProperties::class,
    ResendProperties::class,
)
class UserAccountApplication

fun main(args: Array<String>) {
    runApplication<UserAccountApplication>(*args)
}
