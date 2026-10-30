package com.wildalert.useraccount.hunter

import java.util.UUID

/** Thrown when a hunter id doesn't exist → mapped to HTTP 404 by the exception handler. */
class HunterNotFoundException(id: UUID) :
    RuntimeException("Hunter not found: $id")

/** Thrown when no hunter has the given email → mapped to HTTP 404 by the exception handler. */
class HunterNotFoundByEmailException(email: String) :
    RuntimeException("Hunter not found for email: $email")

/** Thrown when no hunter owns the given inbound address → mapped to HTTP 404. */
class HunterNotFoundByInboundAddressException(address: String) :
    RuntimeException("Hunter not found for inbound address: $address")

/** Thrown when registering an email that's already taken → mapped to HTTP 409. */
class DuplicateEmailException(email: String) :
    RuntimeException("A hunter with email '$email' already exists")

/**
 * Thrown when a login fails, for *any* reason → mapped to HTTP 401.
 *
 * It carries no detail, and that is the point. Unknown email, wrong password, a hunter who
 * predates passwords and has none, a deactivated account -- all four throw this one exception
 * with this one message. Distinguishing them in the response would turn the login form into a
 * tool for discovering which email addresses have accounts.
 */
class InvalidCredentialsException :
    RuntimeException("Email or password is incorrect")
