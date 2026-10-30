package com.wildalert.notification.sms

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sms")
class SmsController(
    private val smsSender: SmsSender,
    private val providerCredit: SmsProviderCreditGuard,
) {

    /** Manual test hook: send an SMS via whichever SmsSender is wired in. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun sendTest(@Valid @RequestBody request: SendSmsRequest): SmsResult =
        smsSender.send(SmsMessage(to = request.to, body = request.body))

    /**
     * How much credit **we** have left at the gateway, read fresh.
     *
     * The operator check that answers "will the next alert go out?" without sending anything or
     * opening the GoSMS panel. Safe to expose because this service is private on Cloud Run — only
     * the Pub/Sub push service account holds `roles/run.invoker` — which is also why the actuator
     * endpoints that would leak configuration stay switched off.
     */
    @GetMapping("/credit")
    fun credit(): ProviderCreditResponse = when (val credit = providerCredit.inspect()) {
        null -> ProviderCreditResponse(
            state = "NOT_REPORTED",
            detail = "the configured SMS provider has no balance to report",
        )

        is ProviderCredit.Known -> ProviderCreditResponse(
            state = "KNOWN",
            remainingMinor = credit.remainingMinor,
            currency = credit.currency,
            messagesLeft = providerCredit.messagesLeft(credit),
        )

        is ProviderCredit.Unmetered -> ProviderCreditResponse(
            state = "UNMETERED",
            detail = "invoicingType=${credit.invoicingType}",
        )

        is ProviderCredit.Unknown -> ProviderCreditResponse(
            state = "UNKNOWN",
            detail = credit.reason,
        )
    }
}
