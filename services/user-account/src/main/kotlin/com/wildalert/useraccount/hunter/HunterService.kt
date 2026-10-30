package com.wildalert.useraccount.hunter

import com.wildalert.useraccount.credit.CreditService
import com.wildalert.useraccount.password.ChangePasswordRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Business logic for hunters. Kept separate from the controller so it can be unit-tested
 * without any web layer, and reused later by other entry points (e.g. event consumers).
 */
@Service
class HunterService(
    private val repository: HunterRepository,
    private val inboundAddresses: InboundAddresses,
    private val credit: CreditService,
    private val passwordEncoder: PasswordEncoder,
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
        )
        // Encoded before the row is written, so a plaintext password never reaches the database
        // even transiently. The encoder is the delegating one, so the stored value names its own
        // algorithm -- see SecurityConfig.
        hunter.setPassword(passwordEncoder.encode(request.password))
        val saved = repository.save(hunter)
        // Inside this same transaction deliberately: a hunter must never exist without a credit
        // account, and a registration that fails after this point must leave neither behind.
        // saved.id is non-null because save() flushes the generated UUID.
        credit.openAccount(saved.id!!)
        return saved
    }

    @Transactional(readOnly = true)
    fun getById(id: UUID): Hunter =
        repository.findById(id).orElseThrow { HunterNotFoundException(id) }

    @Transactional(readOnly = true)
    fun getByEmail(email: String): Hunter {
        val normalized = normalizeEmail(email)
        return repository.findByEmail(normalized) ?: throw HunterNotFoundByEmailException(normalized)
    }

    /**
     * The hunter with this email, or null.
     *
     * Distinct from getByEmail, which throws, because the login path must treat "no such hunter"
     * and "wrong password" identically -- an exception here would be the first step towards
     * answering those two cases differently, which is an account-existence oracle.
     */
    @Transactional(readOnly = true)
    fun findByEmail(email: String): Hunter? = repository.findByEmail(normalizeEmail(email))

    /**
     * Verify a login, or throw InvalidCredentialsException.
     *
     * Every failure path does the same amount of work. When there is no hunter, or the hunter has
     * no password, we still run the encoder against a throwaway hash before refusing -- otherwise
     * an unknown email would answer in a millisecond and a known one in the ~100ms BCrypt costs,
     * and that difference is enough to enumerate which addresses have accounts.
     *
     * The `active` check comes *after* the password check for the same reason: it must not be
     * possible to learn that an address exists but is deactivated by how quickly we say no.
     */
    @Transactional(readOnly = true)
    fun authenticate(request: LoginRequest): Hunter {
        val hunter = findByEmail(request.email)
        val storedHash = hunter?.passwordHash
        if (storedHash == null) {
            passwordEncoder.matches(request.password, absentHunterHash)
            throw InvalidCredentialsException()
        }
        if (!passwordEncoder.matches(request.password, storedHash)) {
            throw InvalidCredentialsException()
        }
        if (!hunter.active) {
            throw InvalidCredentialsException()
        }
        return hunter
    }

    /**
     * A real encoded hash to compare against when there is nothing to compare against, computed
     * once. Encoded rather than hardcoded so it stays valid if the encoder's algorithm changes.
     */
    private val absentHunterHash: String by lazy { passwordEncoder.encode("no-such-hunter") }

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

    /**
     * Change a signed-in hunter's password, after re-checking the current one.
     *
     * **The current password is required even though the caller already holds a valid session**,
     * and that is not belt-and-braces. A session may be an unattended browser, and this endpoint
     * hands over permanent control of the account: without the check, anyone who sits down at an
     * open laptop can lock the owner out. It is also the reason a wrong answer here is the same
     * `InvalidCredentialsException` login uses -- the caller already knows the account exists, so
     * all that is left to avoid saying is which value was wrong.
     *
     * Ending the other sessions is the caller's job, not this method's: sessions belong to
     * SessionService, and doing it here would put the session table behind two owners.
     */
    @Transactional
    fun changePassword(id: UUID, request: ChangePasswordRequest): Hunter {
        val hunter = getById(id)
        val storedHash = hunter.passwordHash ?: throw InvalidCredentialsException()
        if (!passwordEncoder.matches(request.currentPassword, storedHash)) {
            throw InvalidCredentialsException()
        }
        hunter.setPassword(passwordEncoder.encode(request.newPassword))
        return hunter
    }

    @Transactional
    fun update(id: UUID, request: UpdateHunterRequest): Hunter {
        val hunter = getById(id)
        // Only overwrite fields the caller actually provided.
        request.phone?.let { hunter.phone = it }
        request.active?.let { hunter.active = it }
        // Inside a transaction, JPA "dirty checking" flushes these changes on commit —
        // no explicit save() needed.
        return hunter
    }
}
