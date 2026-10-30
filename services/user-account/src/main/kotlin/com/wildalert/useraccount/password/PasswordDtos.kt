package com.wildalert.useraccount.password

import com.wildalert.useraccount.hunter.PASSWORD_MAX_LENGTH
import com.wildalert.useraccount.hunter.PASSWORD_MIN_LENGTH
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * The three request bodies of the password flows.
 *
 * `newPassword` is validated against the same `PASSWORD_MIN_LENGTH`/`PASSWORD_MAX_LENGTH`
 * constants registration uses -- imported, not re-stated, so the policy cannot drift between the
 * place a password is chosen and the places it is changed. The upper bound is BCrypt's 72-byte
 * truncation and is explained on those constants.
 *
 * `currentPassword` and `token`, by contrast, carry **presence checks only**. Restating the length
 * policy on a value being *verified* would announce the rule and would reject a password that
 * predates whatever the rule becomes -- the same reasoning `LoginRequest` already documents.
 */

/** Body for POST /api/me/password — changing your own password while signed in. */
data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,

    @field:NotBlank
    @field:Size(
        min = PASSWORD_MIN_LENGTH,
        max = PASSWORD_MAX_LENGTH,
        message = "must be $PASSWORD_MIN_LENGTH-$PASSWORD_MAX_LENGTH characters",
    )
    val newPassword: String,
)

/**
 * Body for POST /api/password-resets — asking for a link.
 *
 * `@Email` is a format check, not an existence check: a malformed address cannot belong to anyone,
 * so rejecting it reveals nothing. A *well-formed* address always answers 202 whether or not it
 * has an account, which is where the real privacy rule lives (see PasswordResetService.request).
 */
data class RequestPasswordResetRequest(
    @field:NotBlank
    @field:Email(message = "must be a valid email address")
    val email: String,
)

/** Body for POST /api/password-resets/confirm — spending a link. */
data class ConfirmPasswordResetRequest(
    @field:NotBlank
    val token: String,

    @field:NotBlank
    @field:Size(
        min = PASSWORD_MIN_LENGTH,
        max = PASSWORD_MAX_LENGTH,
        message = "must be $PASSWORD_MIN_LENGTH-$PASSWORD_MAX_LENGTH characters",
    )
    val newPassword: String,
)
