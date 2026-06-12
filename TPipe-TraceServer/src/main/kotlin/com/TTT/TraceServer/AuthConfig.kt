package com.TTT.TraceServer

import com.TTT.TraceServer.auth.HashedPassword
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * v2 dashboard authentication configuration.
 *
 * @property passwordHasherEnabled when `true` the `/api/auth/login` route
 *  verifies the supplied `key` against [expectedHash] using
 *  [com.TTT.TraceServer.auth.Pbkdf2PasswordHasher]. When `false` the legacy
 *  `clientAuthMechanism` lambda path is used (plain-text comparison).
 *  Default `true` so v2 deployments are secure out of the box; operators
 *  that want to keep the v1 lambda can opt out explicitly.
 * @property accessTokenTtl how long an access token remains valid.
 *  Defaults to 15 minutes.
 * @property refreshTokenTtl how long a refresh token remains valid.
 *  Defaults to 7 days.
 * @property expectedHash the PBKDF2-hashed password that the dashboard
 *  must present. When `null` and [passwordHasherEnabled] is `true`, the
 *  server still accepts logins through the legacy
 *  [TraceServerRegistry.clientAuthMechanism] lambda. This keeps backward
 *  compatibility with v1 wiring.
 */
data class AuthConfig(
    val passwordHasherEnabled: Boolean = true,
    val accessTokenTtl: Duration = 15.minutes,
    val refreshTokenTtl: Duration = 7.days,
    val expectedHash: HashedPassword? = null
)
