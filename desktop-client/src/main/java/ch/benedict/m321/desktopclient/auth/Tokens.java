package ch.benedict.m321.desktopclient.auth;

import java.time.Instant;

/**
 * Was Keycloak nach dem Login herausgibt.
 *
 * @param accessToken  das JWT, das bei jeder Anfrage ans Gateway mitgeht
 * @param refreshToken damit holt sich der Client ein neues Access-Token, ohne neuen Login
 * @param expiresAt    ab hier nimmt das Gateway das Access-Token nicht mehr an
 */
public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {
}
