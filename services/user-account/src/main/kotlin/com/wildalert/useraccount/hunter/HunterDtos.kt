package com.wildalert.useraccount.hunter

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// Phone numbers must be E.164 (e.g. +420123456789): a leading + then 7-15 digits.
private const val E164 = "^\\+[1-9]\\d{6,14}$"

/**
 * The upper bound is not a style choice: **BCrypt silently truncates its input at 72 bytes.**
 * Accepting a longer password would mean two different passwords unlocking the same account, and
 * the hunter who chose the longer one would never find out. Rejecting it is the honest answer.
 */
const val PASSWORD_MIN_LENGTH = 10
const val PASSWORD_MAX_LENGTH = 72

/** Body for POST /api/hunters — everything required to register a hunter. */
data class RegisterHunterRequest(
    @field:NotBlank
    @field:Email(message = "must be a valid email address")
    val email: String,

    @field:NotBlank
    @field:Pattern(regexp = E164, message = "must be E.164 format, e.g. +420123456789")
    val phone: String,

    // Never stored, never logged, never echoed back: HunterService encodes it and keeps the hash.
    @field:NotBlank
    @field:Size(
        min = PASSWORD_MIN_LENGTH,
        max = PASSWORD_MAX_LENGTH,
        message = "must be $PASSWORD_MIN_LENGTH-$PASSWORD_MAX_LENGTH characters",
    )
    val password: String,
)

/** Body for PUT /api/hunters/{id} — every field optional; only provided ones change. */
data class UpdateHunterRequest(
    @field:Pattern(regexp = E164, message = "must be E.164 format, e.g. +420123456789")
    val phone: String? = null,

    val active: Boolean? = null,
)

/** What we return to clients. Never exposes the entity directly. */
data class HunterResponse(
    val id: UUID,
    val email: String,
    val phone: String,
    val active: Boolean,
    // Where the hunter forwards trail-cam emails (or points the camera).
    val inboundAddress: String,
    // Remaining credit in CZK minor units (haléře), never a formatted string -- the client
    // decides how to display money. 2500 is the signup grant.
    val creditBalanceMinor: Long,
    // What one alert costs a hunter, end to end (recognition + SMS), so the client can turn a
    // balance into the only unit a hunter actually cares about: how many more alerts it buys.
    // "25.00 CZK" is not information; "about ten alerts" is.
    val alertPriceMinor: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(
            h: Hunter,
            inboundAddress: String,
            creditBalanceMinor: Long,
            alertPriceMinor: Long,
        ) = HunterResponse(
            id = h.id!!,
            email = h.email,
            phone = h.phone,
            active = h.active,
            inboundAddress = inboundAddress,
            creditBalanceMinor = creditBalanceMinor,
            alertPriceMinor = alertPriceMinor,
            createdAt = h.createdAt!!,
            updatedAt = h.updatedAt!!,
        )
    }
}

/**
 * Body for POST /api/sessions — logging in.
 *
 * Deliberately validated only for presence. No `@Email`, and no `@Size` on the password: a login
 * is not the place to restate the registration policy. A 400 that says "password must be at least
 * 10 characters" tells an attacker the shape of every password in the system, and it would lock
 * out any hunter whose password predates the current rule. Wrong credentials get one answer, and
 * it is 401.
 */
data class LoginRequest(
    @field:NotBlank
    val email: String,

    @field:NotBlank
    val password: String,
)
