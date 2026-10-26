package com.wildalert.detectionhistory.history

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/**
 * The read side: what the dashboard (Phase 8) calls.
 *
 * Keyed on the hunter's inbound address rather than a hunter id, because that is what the stored
 * events carry — see the migration. The frontend already learns it from user-account when it
 * shows the hunter their forwarding address.
 *
 * **Not safe to expose publicly yet.** The inbound address is the only thing identifying a
 * hunter, so anyone holding one could read that hunter's history. It is a query parameter, which
 * also means it lands in access logs. Phase 7 puts auth on internal endpoints and Phase 9 brings
 * real user authentication; until then this service is for the push subscription and internal
 * callers, and must not get a public, unauthenticated Cloud Run URL.
 */
@RestController
@RequestMapping("/api/detections")
class DetectionController(
    private val service: DetectionHistoryService,
) {

    /**
     * One hunter's detections, newest sighting first, optionally filtered to one species.
     *
     * `limit` is clamped rather than validated: a dashboard asking for 10,000 rows is a bug in the
     * dashboard, and a quietly capped page is a better answer than a 400 for something the caller
     * cannot do anything about.
     */
    @GetMapping
    fun history(
        @RequestParam recipient: String,
        @RequestParam(required = false) species: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<DetectionResponse> =
        service.history(
            recipient = recipient,
            species = species?.trim()?.takeIf { it.isNotEmpty() },
            limit = limit.coerceIn(1, MAX_PAGE),
            offset = offset.coerceAtLeast(0),
        ).map(DetectionResponse::from)

    /**
     * Per-species overview for one hunter. [since] is an ISO-8601 instant; it defaults to the last
     * 30 days, which is the window the overview is actually about — "everything ever" grows
     * without bound and answers a question nobody asked.
     */
    @GetMapping("/summary")
    fun summary(
        @RequestParam recipient: String,
        @RequestParam(required = false) since: Instant?,
    ): List<SpeciesSummaryResponse> =
        service.summary(recipient, since ?: Instant.now().minus(DEFAULT_SUMMARY_WINDOW))
            .map(SpeciesSummaryResponse::from)

    companion object {
        private const val MAX_PAGE = 200
        private val DEFAULT_SUMMARY_WINDOW = Duration.ofDays(30)
    }
}
