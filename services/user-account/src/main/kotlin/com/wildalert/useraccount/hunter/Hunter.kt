package com.wildalert.useraccount.hunter

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var plan: Plan,

    @Column(nullable = false)
    var active: Boolean = true,
) {
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
