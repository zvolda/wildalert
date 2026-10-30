package com.wildalert.useraccount.password

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Deletes spent and expired reset tokens, in a transaction of its own.
 *
 * Same shape and same justification as [com.wildalert.useraccount.session.ExpiredSessionSweeper],
 * which that class documents at length: a bulk JPQL delete clears the persistence context, so
 * joining the caller's transaction would detach whatever it was holding, and a failed cleanup
 * must never fail the reset it rode along with.
 *
 * Called when a reset is *requested*, which is the rare, bounded event in this flow -- and not
 * from a `@Scheduled` task, because on Cloud Run with scale-to-zero that would run only while an
 * instance happened to be alive.
 */
@Component
class ExpiredResetTokenSweeper(private val tokens: PasswordResetTokenRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sweep(): Int {
        val swept = tokens.deleteSpentOrExpiredAt(Instant.now())
        if (swept > 0) log.info("Swept {} spent/expired password-reset token(s)", swept)
        return swept
    }
}
