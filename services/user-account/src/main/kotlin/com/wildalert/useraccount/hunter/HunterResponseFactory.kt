package com.wildalert.useraccount.hunter

import com.wildalert.useraccount.credit.CreditService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Builds the HunterResponse every endpoint returns.
 *
 * This was a private helper on HunterController until /api/me needed exactly the same thing. One
 * assembler rather than two means a hunter reading their own record cannot drift away from what
 * the registration call returns -- which matters, because the React client parses one type.
 */
@Component
class HunterResponseFactory(
    private val inboundAddresses: InboundAddresses,
    private val credit: CreditService,

    /**
     * The hunter-facing price of one alert: recognition (100) plus the SMS (150).
     *
     * **Known duplication, recorded on purpose.** notification owns `billing.sms-cost-minor` and
     * recognition will own its own debit; those are the *internal* amounts each service charges.
     * This is the *price shown to a hunter*, and price belongs with the account that holds the
     * money. The two can drift, and consolidating them is the job to do when recognition starts
     * charging -- see STATUS.md.
     */
    @Value("\${billing.alert-price-minor}") private val alertPriceMinor: Long,
) {
    fun of(hunter: Hunter): HunterResponse = HunterResponse.from(
        hunter,
        inboundAddresses.addressFor(hunter),
        credit.balanceOf(hunter.id!!),
        alertPriceMinor,
    )
}
