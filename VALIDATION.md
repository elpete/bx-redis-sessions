# Session generation qualification — November 16, 2026

Candidate: 0.1.1. Runtime: official BoxLang servlet 1.19 with the narrow BL-2801
invalidation hotfix. Storage: disposable loopback Valkey 8.1; Java 21.

- Three new regressions fail against 0.1.0: a stale snapshot recreates a cleared
  session, overwrites an anonymous replacement, and native request shutdown can
  undo logout. Each fails its assertion; there are no setup errors.
- The fixed provider suite passes all 18 tests, with no failures, errors or skips.
  This includes expired-snapshot rejection, lazy generation assignment for older
  entries, quiet writes and case aliases, and nonexclusive same-generation writes.
- The packaged module passes fresh-runtime provider discovery and native rotation
  with the module isolated from the parent classpath.
- Seven real CommuniArts HTTP contracts pass across two isolated application
  processes with TLS Valkey and synthetic PostgreSQL: native readiness, browser
  isolation/shared CSRF, real password login/rotation and logout, tenant-origin
  CSRF isolation, concurrent logout plus same-ID anonymous replacement, idle
  expiry, and direct rotation/invalidation. This used an explicit module/readiness
  overlay over the prior application image, not a final production image.
  The retained 0.1.0 campaign passed six checks and failed concurrent logout.
  All owned application fixture resources were removed.

No managed-provider, 4 GB hardware capacity, ingress or production release claim
is established by these checks. Mixed operation with 0.1.0 writers is unsafe.
Every participating process and warm rollback image must use the guarded provider.
No production adapter or Redis server was changed.

---

# Focused validation — November 15, 2026

Runtime: BoxLang Servlet 1.19.0+63. Java: Temurin 21.0.10. Storage: disposable
Valkey 8.1 on loopback. No existing runtime or application configuration changed.

| Check | Result |
| --- | --- |
| Provider-only suite, unchanged installed runtime | 10 passed before automatic shutdown writeback was added; latest provider-only run pending |
| Full suite, disposable runtime with the one-line invalidation correction | 12 passed, no failures/errors/skips |
| Packaged module loaded by a fresh runtime, isolated module classloader | Passed |
| Automatic native request-shutdown writeback, exactly once | Passed |
| Native session persisted by one JVM and read by a second JVM | Passed |
| 24 parallel reads while another consumer remains held | Passed; functional deadlines, not a production benchmark |
| Module descriptor formatting and Node script syntax | Passed |
| Application/vendor code packaged into the module | Only the three independent module classes and Jedis dependencies; no BoxLang runtime or proprietary module classes |

The original runtime's native rotation regression fails because invalidation
clears an unqualified session ID instead of the application's storage key.
The correction is provided as a source patch. Tests used a disposable JAR with
`BaseApplicationListener.java` compiled from the local open-source BoxLang checkout
and that one-line correction. This is not an officially released fixed runtime.

Managed TLS, real browser cookies, overlapping authentication mutations, and
CommuniArts integration remain unverified. The module is not deployed or installed
in the user's running server.
