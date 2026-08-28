package com.wildalert.useraccount.hunter

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data generates the implementation at runtime. Method names like
 * `findByEmail` are translated into queries automatically.
 */
interface HunterRepository : JpaRepository<Hunter, UUID> {
    fun findByEmail(email: String): Hunter?
    fun existsByEmail(email: String): Boolean
}
