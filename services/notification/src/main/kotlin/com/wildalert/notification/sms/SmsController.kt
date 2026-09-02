package com.wildalert.notification.sms

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sms")
class SmsController(
    private val smsSender: SmsSender,
) {

    /** Manual test hook: send an SMS via whichever SmsSender is wired in. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun sendTest(@Valid @RequestBody request: SendSmsRequest): SmsResult =
        smsSender.send(SmsMessage(to = request.to, body = request.body))
}
