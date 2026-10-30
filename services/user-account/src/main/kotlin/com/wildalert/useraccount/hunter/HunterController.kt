package com.wildalert.useraccount.hunter

import com.wildalert.useraccount.session.SessionCookies
import com.wildalert.useraccount.session.SessionService
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/hunters")
class HunterController(
    private val service: HunterService,
    private val responses: HunterResponseFactory,
    private val sessions: SessionService,
    private val cookies: SessionCookies,
) {

    /**
     * Register a new hunter. Returns 201 Created with the stored record, **and logs them in**.
     *
     * Issuing the session here means a hunter who has just chosen a password is not immediately
     * asked to type it again -- they land on their dashboard. The alternative (register, then
     * redirect to a login form) is a worse first experience for no security gain: we have just
     * proved they know the password, because they picked it a moment ago.
     */
    @PostMapping
    fun register(@Valid @RequestBody request: RegisterHunterRequest): ResponseEntity<HunterResponse> {
        val hunter = service.register(request)
        val token = sessions.open(hunter)
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.SET_COOKIE, cookies.issue(token, sessions.ttl).toString())
            .body(responses.of(hunter))
    }

    /** Fetch a hunter by id. Returns 404 if it doesn't exist. */
    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): HunterResponse =
        responses.of(service.getById(id))

    /**
     * Look up a hunter by email address, so other services (e.g. email-ingestion) can match a
     * forwarded email's sender to a hunter. Returns 404 if no hunter has that email.
     * A distinct `/by-email` path (not a path variable) so it never collides with `/{id}`.
     */
    @GetMapping("/by-email")
    fun getByEmail(@RequestParam email: String): HunterResponse =
        responses.of(service.getByEmail(email))

    /**
     * Look up the hunter who owns a personal inbound address, so email-ingestion can match a
     * trail-cam email by its recipient. Returns 404 for any address that isn't a hunter's.
     */
    @GetMapping("/by-inbound-address")
    fun getByInboundAddress(@RequestParam address: String): HunterResponse =
        responses.of(service.getByInboundAddress(address))

    /** Update phone / active. Returns the updated record. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateHunterRequest,
    ): HunterResponse =
        responses.of(service.update(id, request))
}
