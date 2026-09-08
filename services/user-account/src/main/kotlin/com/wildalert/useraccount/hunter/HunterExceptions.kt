package com.wildalert.useraccount.hunter

import java.util.UUID

/** Thrown when a hunter id doesn't exist → mapped to HTTP 404 by the exception handler. */
class HunterNotFoundException(id: UUID) :
    RuntimeException("Hunter not found: $id")

/** Thrown when no hunter has the given email → mapped to HTTP 404 by the exception handler. */
class HunterNotFoundByEmailException(email: String) :
    RuntimeException("Hunter not found for email: $email")

/** Thrown when registering an email that's already taken → mapped to HTTP 409. */
class DuplicateEmailException(email: String) :
    RuntimeException("A hunter with email '$email' already exists")
