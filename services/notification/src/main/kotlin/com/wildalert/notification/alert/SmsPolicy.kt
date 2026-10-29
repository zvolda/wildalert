package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterCharge
import com.wildalert.notification.hunter.Notice
import com.wildalert.notification.sms.SmsMessage

/**
 * Decides whether a recognition result is texted to the hunter, and what the SMS says.
 * Pure logic (no I/O), so every rule is unit-tested. Current policy: one SMS per recognized photo.
 * Smart alerts (target species only, daily digest) are a later cost-control feature.
 */
object SmsPolicy {

    /** Returns the SMS to send, or null when this hunter should not be texted. */
    fun compose(event: AnimalRecognized, hunter: HunterCharge): SmsMessage? {
        if (!hunter.active) return null
        return SmsMessage(to = hunter.phone, body = bodyFor(event) + creditSuffix(hunter))
    }

    // Plain ASCII keeps each message within a single 160-character GSM-7 SMS segment.
    private fun bodyFor(event: AnimalRecognized): String =
        if (event.lowConfidence) {
            // Never state an uncertain species as fact — hedge instead (roadmap precision safeguard).
            "WildAlert: animal detected, species uncertain (maybe ${event.species})"
        } else {
            "WildAlert: ${event.species} detected (${percent(event.confidence)}% confidence)"
        }

    /**
     * The credit warning, appended to the alert rather than sent on its own.
     *
     * **It rides along because it always can.** user-account only ever returns a notice on a
     * *successful* charge — a refusal answers `NONE`, and the warning for running dry is issued by
     * the charge that reached zero, not by the ones that bounce afterwards. So whenever there is
     * something to say there is also an alert going out to say it on, which makes the warning free.
     * Sending it separately would cost an SMS to tell someone they have run out of SMS credit.
     *
     * Told at most once per depletion cycle, which is user-account's `warning_level` claim doing
     * the work, not this function — thirty photos on an empty balance produce one warning.
     */
    private fun creditSuffix(hunter: HunterCharge): String = when (hunter.notice) {
        Notice.NONE -> ""
        Notice.LOW_BALANCE -> " Credit low: ${czk(hunter.balanceAfterMinor)} CZK left."
        // No top-up link yet (Stripe is a later slice), so this says what is true and no more.
        Notice.OUT_OF_CREDIT -> " Credit used up - no more alerts until you top up."
    }

    /** haléře to a plain ASCII CZK amount: 350 -> "3.50". Never negative; the balance cannot be. */
    private fun czk(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    // Rounded down, so a 0.9999 result reads 99% rather than claiming certainty.
    private fun percent(confidence: Double): Int = (confidence * 100).toInt()
}
