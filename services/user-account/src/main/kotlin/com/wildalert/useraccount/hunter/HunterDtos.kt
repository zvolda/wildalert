package com.wildalert.useraccount.hunter

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

// Phone numbers must be E.164 (e.g. +420123456789): a leading + then 7-15 digits.
private const val E164 = "^\\+[1-9]\\d{6,14}$"

/** Body for POST /api/hunters — everything required to register a hunter. */
data class RegisterHunterRequest(
    @field:NotBlank
    @field:Email(message = "must be a valid email address")
    val email: String,

    @field:NotBlank
    @field:Pattern(regexp = E164, message = "must be E.164 format, e.g. +420123456789")
    val phone: String,

    val plan: Plan = Plan.FREE,
)

/** Body for PUT /api/hunters/{id} — every field optional; only provided ones change. */
data class UpdateHunterRequest(
    @field:Pattern(regexp = E164, message = "must be E.164 format, e.g. +420123456789")
    val phone: String? = null,

    val plan: Plan? = null,

    val active: Boolean? = null,
)

/** What we return to clients. Never exposes the entity directly. */
data class HunterResponse(
    val id: UUID,
    val email: String,
    val phone: String,
    val plan: Plan,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(h: Hunter) = HunterResponse(
            id = h.id!!,
            email = h.email,
            phone = h.phone,
            plan = h.plan,
            active = h.active,
            createdAt = h.createdAt!!,
            updatedAt = h.updatedAt!!,
        )
    }
}
