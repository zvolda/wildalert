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
) {

    @Transactional
    fun register(request: RegisterHunterRequest): Hunter {
        if (repository.existsByEmail(request.email)) {
            throw DuplicateEmailException(request.email)
        }
        val hunter = Hunter(
            email = request.email,
            phone = request.phone,
            plan = request.plan,
        )
        return repository.save(hunter)
    }

    @Transactional(readOnly = true)
    fun getById(id: UUID): Hunter =
        repository.findById(id).orElseThrow { HunterNotFoundException(id) }

    @Transactional(readOnly = true)
    fun getByEmail(email: String): Hunter =
        repository.findByEmail(email) ?: throw HunterNotFoundByEmailException(email)

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
