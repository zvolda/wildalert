package com.wildalert.useraccount.password

/**
 * Thrown when a reset token is unknown, expired, already spent, or belongs to a hunter who can no
 * longer log in → mapped to HTTP 400.
 *
 * One exception for all four, in the same spirit as `InvalidCredentialsException`: the caller
 * learns that the link did not work and nothing else. Telling a replayed link apart from an
 * expired one is of no use to a hunter -- they ask for a new link either way -- and of real use to
 * someone probing with guessed tokens.
 */
class InvalidResetTokenException :
    RuntimeException("This password reset link is no longer valid. Please request a new one.")
