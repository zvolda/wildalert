package com.wildalert.useraccount.web

import com.wildalert.useraccount.hunter.DuplicateEmailException
import com.wildalert.useraccount.hunter.HunterNotFoundByEmailException
import com.wildalert.useraccount.hunter.HunterNotFoundByInboundAddressException
import com.wildalert.useraccount.hunter.HunterNotFoundException
import com.wildalert.useraccount.hunter.InvalidCredentialsException
import com.wildalert.useraccount.password.InvalidResetTokenException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates exceptions into HTTP responses so controllers stay clean.
 * Uses ProblemDetail (RFC 9457), Spring's standard error body.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(
        HunterNotFoundException::class,
        HunterNotFoundByEmailException::class,
        HunterNotFoundByInboundAddressException::class,
    )
    fun handleNotFound(ex: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found")

    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicate(ex: DuplicateEmailException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Conflict")

    /**
     * A failed login → 401, with the exception's own deliberately uninformative message.
     *
     * One handler for every cause (unknown email, wrong password, no password, deactivated) so
     * the four cannot drift into four distinguishable responses. Matches the body shape of
     * ProblemDetailAuthenticationEntryPoint, which answers the same 401 when the *filter chain*
     * rejects a request rather than a controller.
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.message ?: "Unauthorized")

    /**
     * A dead reset link → 400, with the exception's own message, which is safe to show: it tells
     * the hunter to ask for a new link and nothing about why this one failed.
     *
     * 400 rather than 404 deliberately. A 404 would distinguish "no such token" from "expired or
     * spent", which is the distinction the single exception exists to hide.
     */
    @ExceptionHandler(InvalidResetTokenException::class)
    fun handleInvalidResetToken(ex: InvalidResetTokenException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request")

    /** Bean-validation failures (@Valid) → 400 with a field→message map. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Request validation failed",
        )
        val errors = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        problem.setProperty("errors", errors)
        return problem
    }
}
