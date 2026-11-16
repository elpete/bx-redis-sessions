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
