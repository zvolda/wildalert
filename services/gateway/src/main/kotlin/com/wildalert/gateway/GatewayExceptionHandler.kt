package com.wildalert.gateway

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Keeps the gateway's own failures distinguishable from the upstream service's. Uses
 * ProblemDetail (RFC 9457), the same body shape user-account produces, so the React app's error
 * parsing works on both without a special case.
 */
@RestControllerAdvice
class GatewayExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * We could not mint an ID token, so the request never left this service. 503 and not 500:
     * the metadata server being briefly unavailable is transient, and the browser is right to
     * offer the hunter a retry rather than tell them something is broken.
     */
    @ExceptionHandler(IdTokenUnavailableException::class)
    fun handleIdToken(ex: IdTokenUnavailableException): ProblemDetail {
        log.error("Refusing a request because no ID token could be minted", ex)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Temporarily unable to reach the account service. Please try again.",
        )
    }
}
