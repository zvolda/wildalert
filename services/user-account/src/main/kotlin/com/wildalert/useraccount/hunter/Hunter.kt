package com.wildalert.useraccount.hunter

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * A hunter who forwards trail-camera emails to us and receives SMS alerts.
 * Maps to the `hunter` table created by Flyway (V1__create_hunter_table.sql).
 */
@Entity
@Table(name = "hunter")
class Hunter(
    @Column(nullable = false, unique = true)
    var email: String,

    @Column(nullable = false)
    var phone: String,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    // Identifies the hunter's personal inbound address (<token>@<inbound domain>). Generated for new
    // hunters and never changed, so an address set up in a forwarding rule or camera keeps working.
    @Column(name = "inbound_token", nullable = false, unique = true, updatable = false)
    var inboundToken: String = InboundAddresses.newToken()
        protected set

    // The hunter's login credential, encoded by Spring Security's DelegatingPasswordEncoder (so
    // the stored value carries its own algorithm prefix, e.g. "{bcrypt}$2a$10$...").
    //
    // Nullable because hunters registered before logins existed have no password. Null means
    // "cannot log in" and is answered with the same generic 401 as a wrong password -- see
    // SessionService. The setter is protected so a hash is only ever set through setPassword(),
    // which makes "where does a password get stored?" a question with one answer.
    @Column(name = "password_hash")
    var passwordHash: String? = null
        protected set

    /** Store an already-encoded password hash. Encoding happens in the service, never here. */
    fun setPassword(encoded: String) {
        passwordHash = encoded
    }

    // Hibernate generates the UUID on insert; null until the row is first saved.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set
}
