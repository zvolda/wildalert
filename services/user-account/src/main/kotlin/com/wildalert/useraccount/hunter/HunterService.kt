package com.wildalert.useraccount.hunter

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Business logic for hunters. Kept separate from the controller so it can be unit-tested
 * without any web layer, and reused later by other entry points (e.g. Kafka consumers).
 */
@Service
class HunterService(
    private val repository: HunterRepository,
    private val inboundAddresses: InboundAddresses,
) {

    @Transactional
    fun register(request: RegisterHunterRequest): Hunter {
        val email = normalizeEmail(request.email)
        if (repository.existsByEmail(email)) {
            throw DuplicateEmailException(email)
        }
        val hunter = Hunter(
            email = email,
            phone = request.phone,
            plan = request.plan,
        )
        return repository.save(hunter)
    }

    @Transactional(readOnly = true)
    fun getById(id: UUID): Hunter =
        repository.findById(id).orElseThrow { HunterNotFoundException(id) }

    @Transactional(readOnly = true)
    fun getByEmail(email: String): Hunter {
        val normalized = normalizeEmail(email)
        return repository.findByEmail(normalized) ?: throw HunterNotFoundByEmailException(normalized)
    }

    /** The hunter whose personal inbound address this is (the recipient of a trail-cam email). */
    @Transactional(readOnly = true)
    fun getByInboundAddress(address: String): Hunter {
        val token = inboundAddresses.tokenOf(address)
        return token?.let { repository.findByInboundToken(it) }
            ?: throw HunterNotFoundByInboundAddressException(address.trim())
    }

    /**
     * Emails are case-insensitive in practice, and forwarded senders arrive with inconsistent
     * casing/whitespace (e.g. "Hunter@Example.com "). Storing and looking them up in a canonical
     * lowercase, trimmed form makes matching reliable and keeps the unique constraint meaningful.
     */
    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    @Transactional
    fun update(id: UUID, request: UpdateHunterRequest): Hunter {
        val hunter = getById(id)
        // Only overwrite fields the caller actually provided.
        request.phone?.let { hunter.phone = it }
        request.plan?.let { hunter.plan = it }
        request.active?.let { hunter.active = it }
        // Inside a transaction, JPA "dirty checking" flushes these changes on commit —
        // no explicit save() needed.
        return hunter
    }
}
