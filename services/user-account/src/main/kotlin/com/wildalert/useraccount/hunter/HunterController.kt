package com.wildalert.useraccount.hunter

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/hunters")
class HunterController(
    private val service: HunterService,
    private val inboundAddresses: InboundAddresses,
) {

    /** Register a new hunter. Returns 201 Created with the stored record. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterHunterRequest): HunterResponse =
        response(service.register(request))

    /** Fetch a hunter by id. Returns 404 if it doesn't exist. */
    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): HunterResponse =
        response(service.getById(id))

    /**
     * Look up a hunter by email address, so other services (e.g. email-ingestion) can match a
     * forwarded email's sender to a hunter. Returns 404 if no hunter has that email.
     * A distinct `/by-email` path (not a path variable) so it never collides with `/{id}`.
     */
    @GetMapping("/by-email")
    fun getByEmail(@RequestParam email: String): HunterResponse =
        response(service.getByEmail(email))

    /**
     * Look up the hunter who owns a personal inbound address, so email-ingestion can match a
     * trail-cam email by its recipient. Returns 404 for any address that isn't a hunter's.
     */
    @GetMapping("/by-inbound-address")
    fun getByInboundAddress(@RequestParam address: String): HunterResponse =
        response(service.getByInboundAddress(address))

    /** Update phone / plan / active. Returns the updated record. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateHunterRequest,
    ): HunterResponse =
        response(service.update(id, request))

    private fun response(hunter: Hunter) = HunterResponse.from(hunter, inboundAddresses.addressFor(hunter))
}
