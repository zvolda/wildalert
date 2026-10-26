package com.wildalert.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The front door. One public service that serves the built React app and forwards the handful of
 * API calls it is allowed to make to the private services behind it.
 *
 * It exists because `user-account` is deployed on Cloud Run with **Require authentication** — it
 * serves hunters' phone numbers and must not be reachable from a browser. A browser has no
 * Google ID token and cannot get one, so something has to sit in front and present one. Doing
 * that here, rather than opening `user-account` up, keeps exactly one service public and keeps
 * the decision about *which* endpoints the internet can reach in one readable place
 * ([ApiRoutes]).
 *
 * Serving the UI from the same service is what removes CORS from the picture entirely: the
 * browser sees one origin for both the page and the API calls it makes, which is the same
 * arrangement Vite's dev proxy fakes in development.
 */
@SpringBootApplication
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
