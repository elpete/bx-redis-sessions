# Redis sessions for BoxLang

An independent, session-focused implementation of BoxLang's native `ICacheProvider`
interface. Redis or Valkey holds the runtime's serialized `Session` objects.
BoxLang owns session IDs, cookies, scope access, rotation, and invalidation. The provider registers one native request-shutdown writeback for serialized session changes; applications do not need persistence hooks.

There are no browser/request leases, custom authentication, application handler
hooks, Cluster/Sentinel providers, or Pub/Sub helpers. Jedis provides pooled Redis
connections. This implementation contains no proprietary bx-redis source or binaries.

## Configuration

Install the module directory in the BoxLang runtime's `modules` directory. The
module registers the native provider name `SessionRedis`. Define the cache in
`Application.bx` so the module is loaded before the application creates its cache:

```boxlang
this.sessionManagement = true
this.sessionCluster = true
this.sessionTimeout = createTimespan( 0, 1, 0, 0 )
this.sessionStorage = "redisSessions"
this.caches = {
    "redisSessions": {
        "provider": "SessionRedis",
        "properties": {
            "host": getSystemSetting( "REDIS_HOST" ),
            "port": getSystemSetting( "REDIS_PORT", "6379" ),
            "username": getSystemSetting( "REDIS_USER", "" ),
            "password": getSystemSetting( "REDIS_PASSWORD", "" ),
            "useSSL": true,
            "keyprefix": "communiarts-production-sessions",
            "resetTimeoutOnAccess": true
        }
    }
}
```

The same cache name and key prefix must be used on every replica. Local tests can
set `useSSL` to `false`. TLS uses the JVM trust store with hostname verification;
there is no trust-all mode. Connections, socket reads, and pool waits have finite
timeouts. Configure private certificate authorities through the JVM trust store.

cbStorages can use its ordinary `SessionStorage` and normal `session` scope.
This module does not configure ColdBox/CacheBox or change CommuniArts automatically.

## Runtime prerequisite discovered during testing

The installed BoxLang Servlet **1.19.0+63** fails native rotation/invalidation with
serialized session storage. `BaseApplicationListener.invalidateSession()` clears
the bare session ID, while `Session.buildCacheKey()` and persistence use the
application-qualified key. The old serialized session remains readable.

The one-line correction is in
[`patches/boxlang-session-invalidation.patch`](patches/boxlang-session-invalidation.patch).
It belongs in BoxLang, not in this storage provider. No installed or production
runtime has been changed. The full tests intentionally fail on the uncorrected
runtime; do not use it for production session storage until this is resolved.

For controlled validation, a copy of `BaseApplicationListener.java` from the local
open-source BoxLang checkout was compiled against the installed runtime with that
one-line correction, and inserted into a disposable copy of the servlet JAR.
This validates the correction locally; it is not an official fixed BoxLang release.

## Build and focused tests

Requires Java 21, Maven, Node, `zip`, and a disposable Redis/Valkey server. All
dependency versions are pinned in `pom.xml`. The native BoxLang JAR is a build/test
input, not bundled into this module.

```sh
BOXLANG_JAR=/absolute/path/to/boxlang.jar REDIS_TEST_PORT=6379 node scripts/build.mjs
```

The build runs the complete suite, then creates
`target/bx-redis-sessions-0.1.1.zip`. Extract it into a directory named
`bx-redis-sessions` under the runtime's `modules` directory. Neither the build nor
the tests install into an existing server.

To inspect the provider-only checks against an uncorrected runtime while keeping
the upstream lifecycle failure separate:

```sh
mvn -Dboxlang.jar=/absolute/path/to/boxlang.jar -Dredis.port=6379 \
    -DexcludedGroups=runtime-regression test
```

That focused pass does not resolve the runtime prerequisite.

After building, `node scripts/smoke.mjs` boots a fresh runtime home with only the
packaged module installed and verifies native provider discovery and session
rotation. Use the same `BOXLANG_JAR` and `REDIS_TEST_PORT`; for a servlet JAR, also
set `SERVLET_API_JAR` to `jakarta.servlet-api-6.0.0.jar`. The smoke test has no module
classes or Jedis on its parent classpath, so it verifies actual module loading.

## Scope and concurrency

Individual cache entries are written atomically, with Redis-owned expiration.
Reads touch idle TTL without rewriting serialized values or holding ownership
after the cache operation returns. Enumeration and clearing use namespace-scoped
`SCAN`, never `KEYS`, `FLUSHDB`, or `FLUSHALL`. `getSize()` scans live entries;
it does not depend on Redis keyspace notification permissions.

The cache contract includes atomic publication for `getOrSet`. Concurrent producers
may run more than once, but return the winning published value. Do not put external
side effects in a cache value producer.

This preserves native BoxLang session semantics. Concurrent requests can load
separate snapshots and later writes can replace earlier snapshots. A session
snapshot carries the Redis generation it read. Writeback atomically
requires that same generation still exist, so a snapshot cannot recreate a cleared
or expired session, or overwrite an anonymous replacement with the same ID. Reads
and ordinary same-generation writes remain nonexclusive and last-writer-wins;
there is no field merging or browser-wide request lease.

All participating processes, including warm rollback images, must use 0.1.1 or
newer. An older writer can bypass this guard; mixed-version operation is unsafe.
Existing entries acquire a generation atomically when read. Readiness can verify
`getSessionWritebackProtocol()` returns `generation-v1`.

Verified locally: native session object round-trip, separate JVMs sharing a session,
rotation and invalidation with the runtime correction, expiration, namespace
isolation, atomic cache initialization, and parallel reads with a held consumer.
Seven local CommuniArts HTTP checks also pass, including cross-process login,
logout, old-cookie replay with an overlapping request, tenant-origin CSRF and idle
expiry. Managed-provider TLS, hardware capacity and the final pinned application
image remain unverified. This module has not been deployed to production.
