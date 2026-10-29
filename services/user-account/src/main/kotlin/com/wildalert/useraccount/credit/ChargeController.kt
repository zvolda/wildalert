package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.HunterService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Resolve a hunter and charge them for one event, in a single call.
 *
 * **One call, not two, on purpose.** The caller (`notification`, and later `recognition`) needs
 * the phone number *and* permission to send. Splitting it would double the cold-start exposure on
 * a scaled-to-zero service and introduce a state nobody wants: looked-up-but-not-charged. It also
 * lets the "may this hunter be texted?" decision be made inside the transaction that holds the
 * money, which is the only place it can be made without a race.
 *
 * **These endpoints return a phone number and must never be proxied by the gateway.** They sit
 * alongside `by-email`, `by-inbound-address` and `GET /api/hunters/{id}` on that list;
 * `ApiRoutes.kt`'s comment names them. Nothing calls these yet.
 *
 * A charge is not idempotent by accident — it is keyed on the event id, so calling it twice is
 * safe and expected. See [CreditChargeService].
 */
@RestController
@RequestMapping("/api/hunters")
class ChargeController(
    private val hunters: HunterService,
    private val charges: CreditChargeService,
) {

    /**
     * The normal path: the event carries the recipient address, which is the only identity the
     * pipeline has (slice 13 moved recipient resolution onto the event). A 404 means the address
     * is not a hunter's — mail to a made-up name on our domain — and nothing is charged.
     */
    @PostMapping("/by-inbound-address/charges")
    fun chargeByInboundAddress(
        @RequestParam address: String,
        @Valid @RequestBody request: ChargeRequest,
    ): ChargeResponse =
        charges.charge(hunters.getByInboundAddress(address), request)

    /**
     * The fallback path, for an event that carries only the legacy `hunterId` — one published
     * before slice 13, or replayed from a dead-letter topic. Kept for the same reason the
     * `hunterId` field is: an old event in flight should still reach its hunter.
     */
    @PostMapping("/{id}/charges")
    fun chargeById(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ChargeRequest,
    ): ChargeResponse =
        charges.charge(hunters.getById(id), request)
}
