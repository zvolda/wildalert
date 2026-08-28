package com.wildalert.useraccount.web

import com.wildalert.useraccount.hunter.DuplicateEmailException
import com.wildalert.useraccount.hunter.HunterNotFoundException
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

    @ExceptionHandler(HunterNotFoundException::class)
    fun handleNotFound(ex: HunterNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found")

    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicate(ex: DuplicateEmailException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Conflict")

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
