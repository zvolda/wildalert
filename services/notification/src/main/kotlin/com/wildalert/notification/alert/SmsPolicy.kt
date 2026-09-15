package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterContact
import com.wildalert.notification.sms.SmsMessage

/**
 * Decides whether a recognition result is texted to the hunter, and what the SMS says.
 * Pure logic (no I/O), so every rule is unit-tested. Current policy: one SMS per recognized photo.
 * Smart alerts (target species only, daily digest) are a later cost-control feature.
 */
object SmsPolicy {

    /** Returns the SMS to send, or null when this hunter should not be texted. */
    fun compose(event: AnimalRecognized, hunter: HunterContact): SmsMessage? {
        if (!hunter.active) return null
        return SmsMessage(to = hunter.phone, body = bodyFor(event))
    }

    // Plain ASCII keeps each message within a single 160-character GSM-7 SMS segment.
    private fun bodyFor(event: AnimalRecognized): String =
        if (event.lowConfidence) {
            // Never state an uncertain species as fact — hedge instead (roadmap precision safeguard).
            "WildAlert: animal detected, species uncertain (maybe ${event.species})"
        } else {
            "WildAlert: ${event.species} detected (${percent(event.confidence)}% confidence)"
        }

    // Rounded down, so a 0.9999 result reads 99% rather than claiming certainty.
    private fun percent(confidence: Double): Int = (confidence * 100).toInt()
}
