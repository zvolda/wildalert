package com.wildalert.notification.event

/**
 * The JSON Pub/Sub POSTs to a push endpoint. The event itself is base64 in [PubSubPushMessage.data];
 * everything else is delivery metadata.
 *
 * All fields are nullable with defaults on purpose: this is someone else's wire format, and a
 * missing field should produce a logged ack-or-nack decision rather than a Jackson failure before
 * our code runs. Unknown fields are ignored by Boot's ObjectMapper, so Google can add more.
 *
 * `deliveryAttempt` is documented as part of the message but has also been observed alongside it,
 * so it is accepted in both places — and nothing here depends on it, it is only logged.
 */
data class PubSubPushEnvelope(
    val message: PubSubPushMessage? = null,
    val subscription: String? = null,
    val deliveryAttempt: Int? = null,
) {
    /** Whichever of the two positions carried the attempt count, if either did. */
    fun attempt(): Int? = deliveryAttempt ?: message?.deliveryAttempt
}

data class PubSubPushMessage(
    val data: String? = null,
    val messageId: String? = null,
    val publishTime: String? = null,
    val attributes: Map<String, String>? = null,
    val orderingKey: String? = null,
    val deliveryAttempt: Int? = null,
)
