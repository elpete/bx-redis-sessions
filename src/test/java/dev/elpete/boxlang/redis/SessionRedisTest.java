package dev.elpete.boxlang.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.application.*;
import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.config.segments.CacheConfig;
import ortus.boxlang.runtime.context.*;
import ortus.boxlang.runtime.scopes.*;
import ortus.boxlang.runtime.types.*;

class SessionRedisTest {
    static BoxRuntime runtime;
    SessionRedis first;
    SessionRedis second;
    String namespace;

    @BeforeAll static void startRuntime() throws Exception {
        runtime = BoxRuntime.getInstance(false, null, Files.createTempDirectory("bx-redis-sessions-test-").toString());
    }
    @AfterAll static void stopRuntime() { if (runtime != null) { runtime.shutdown(); } }
    @BeforeEach void connect() {
        namespace = "test_" + UUID.randomUUID().toString().replace("-", "");
        first = provider("sessions", namespace);
        second = provider("sessions", namespace);
    }
    @AfterEach void close() {
        try { first.clearAll(); } finally {
            if (runtime.getCacheService().hasCache("sessions") && runtime.getCacheService().getCache("sessions") == first) {
                runtime.getCacheService().shutdownCache(Key.of("sessions"));
            } else { first.shutdown(); }
            second.shutdown();
        }
    }
    static SessionRedis provider(String name, String prefix) {
        SessionRedis provider = new SessionRedis();
        provider.configure(runtime.getCacheService(), new CacheConfig(Key.of(name), Key.of("SessionRedis"),
            Struct.of("port", Integer.parseInt(System.getProperty("redis.port", "6379")), "keyprefix", prefix)));
        return provider;
    }

    @Test void discoversProviderThroughNativeServiceInterface() {
        assertTrue(ServiceLoader.load(ICacheProvider.class).stream().anyMatch(p -> p.type() == SessionRedis.class));
        assertTrue(first.getObjectStore().isDistributed());
    }
    @Test void roundTripsSessionObjectAcrossIndependentConnections() {
        Application application = new Application(Key.of("session-test"));
        var context = new ScriptingRequestBoxContext(runtime.getRuntimeContext());
        application.start(context);
        Session original = new Session(Key.of(UUID.randomUUID().toString()), application, Duration.ofMinutes(5));
        original.getSessionScope().put("userId", "member-123");
        original.getSessionScope().put("roles", Array.of("staff", "performer"));
        first.set(original.getCacheKey(), original, Duration.ofMinutes(5));
        Session restored = (Session)second.get(original.getCacheKey()).get();
        assertNotSame(original, restored);
        assertEquals("member-123", restored.getSessionScope().get("userId"));
        assertEquals(original.getID(), restored.getID());
        assertInstanceOf(Array.class, restored.getSessionScope().get("roles"));
    }
    @Test @Tag("runtime-regression") void nativeRuntimePersistsLoadsRotatesAndInvalidatesSession() {
        runtime.getCacheService().registerCache(first);
        String app = "native_" + namespace;
        Key id = Key.of(UUID.randomUUID().toString());
        var request = nativeRequest(app, id);
        runtime.executeSource("session.userId='member-123'; session.flash={notice:'Saved'}; session.csrf='token';", request);
        var sessionContext = request.getParentOfType(SessionBoxContext.class);
        request.shutdown();
        String oldKey = sessionContext.getSession().getCacheKey();
        assertInstanceOf(Session.class, second.get(oldKey).get());

        var next = nativeRequest(app, id);
        var restored = next.getScope(SessionScope.name);
        assertEquals("member-123", restored.get("userId"));
        assertEquals("token", restored.get("csrf"));
        next.getApplicationListener().rotateSession();
        String rotatedKey = next.getParentOfType(SessionBoxContext.class).getSession().getCacheKey();
        assertNotEquals(oldKey, rotatedKey);
        assertTrue(second.get(oldKey).isEmpty());
        assertEquals("member-123", ((Session)second.get(rotatedKey).get()).getSessionScope().get("userId"));
        next.resetSession();
        assertTrue(second.get(rotatedKey).isEmpty());
    }
    private ScriptingRequestBoxContext nativeRequest(String app, Key id) {
        var context = new ScriptingRequestBoxContext(runtime.getRuntimeContext());
        context.setSessionID(id);
        RequestBoxContext.runInContext(context, ignored -> {
            runtime.executeSource("bx:application name='" + app + "' sessionManagement=true sessionStorage='sessions' sessionCluster=true;", context);
            return null;
        });
        return context;
    }

    @Test void nativeRequestWritesBackOnceAtShutdownWithoutApplicationHooks() {
        runtime.getCacheService().registerCache(first);
        var request = nativeRequest("writeback_" + namespace, Key.of(UUID.randomUUID().toString()));
        runtime.executeSource("session.userId='member-123';", request);
        String key = request.getParentOfType(SessionBoxContext.class).getSession().getCacheKey();
        request.shutdown();
        Session restored = (Session)second.get(key).get();
        assertEquals("member-123", restored.getSessionScope().get("userId"));
        restored.getSessionScope().put("userId", "later-write");
        second.set(key, restored);
        request.shutdown();
        assertEquals("later-write", ((Session)second.get(key).get()).getSessionScope().get("userId"));
    }
    @Test void staleSessionSnapshotCannotRecreateAClearedSession() {
        Session original = seededSession();
        String key = original.getCacheKey();
        first.set(key, original, Duration.ofMinutes(5));
        Session stale = (Session)second.get(key).get();
        first.clear(key);
        second.set(key, stale, Duration.ofMinutes(5));
        assertTrue(first.get(key).isEmpty(), "A stale snapshot resurrected the cleared session");
    }
    @Test void staleSessionSnapshotCannotOverwriteAReplacementGeneration() {
        Session original = seededSession();
        String key = original.getCacheKey();
        first.set(key, original, Duration.ofMinutes(5));
        Session stale = (Session)second.get(key).get();
        first.clear(key);
        Session replacement = new Session(original.getID(), new Application(Key.of("generation-session-test")), Duration.ofMinutes(5));
        first.set(key, replacement, Duration.ofMinutes(5));
        second.set(key, stale, Duration.ofMinutes(5));
        Session retained = (Session)first.get(key).get();
        assertFalse(retained.getSessionScope().containsKey("userId"), "A stale snapshot overwrote the anonymous replacement");
    }
    @Test void expiredSessionSnapshotCannotExtendItsLifetime() throws Exception {
        Session original = seededSession();
        String key = original.getCacheKey();
        first.set(key, original, Duration.ofSeconds(1));
        Session stale = (Session)second.get(key).get();
        Thread.sleep(1150);
        second.set(key, stale, Duration.ofMinutes(5));
        assertTrue(first.get(key).isEmpty(), "An expired snapshot renewed its own authentication");
    }
    @Test void legacyEntryGetsOneSharedGenerationBeforeAnyWriteback() {
        Session original = seededSession();
        String key = original.getCacheKey();
        first.set(key, original, Duration.ofMinutes(5));
        var redisKeys = first.store.client().scan("0", new redis.clients.jedis.params.ScanParams().match(namespace + ":*").count(100)).getResult();
        assertEquals(1, redisKeys.size());
        first.store.client().hdel(redisKeys.iterator().next(), "generation");
        Session one = (Session)first.getQuiet(key).get();
        Session two = (Session)second.get(key).get();
        one.getSessionScope().put("notice", "one");
        first.set(key, one, Duration.ofMinutes(5));
        two.getSessionScope().put("notice", "two");
        second.set(key, two, Duration.ofMinutes(5));
        assertEquals("two", ((Session)first.get(key).get()).getSessionScope().get("notice"));
        first.clear(key);
        first.setQuiet(Key.of(key.toUpperCase(Locale.ROOT)), new ortus.boxlang.runtime.cache.BoxCacheEntry(
            first.getName(), 300, 300, Key.of(key), one, new Struct()));
        assertTrue(second.get(key).isEmpty(), "Quiet writes and case aliases must retain the revocation guard");
    }
    @Test void updatesWithinOneSessionGenerationRemainNonexclusiveAndLastWriterWins() {
        Session original = seededSession();
        String key = original.getCacheKey();
        first.set(key, original, Duration.ofMinutes(5));
        Session one = (Session)first.get(key).get();
        Session two = (Session)second.getQuiet(key).get();
        one.getSessionScope().put("notice", "one");
        first.set(key, one, Duration.ofMinutes(5));
        two.getSessionScope().put("notice", "two");
        second.set(key, two, Duration.ofMinutes(5));
        assertEquals("two", ((Session)first.get(key).get()).getSessionScope().get("notice"));
    }
    @Test @Tag("runtime-regression") void overlappingNativeRequestShutdownCannotUndoLogout() {
        runtime.getCacheService().registerCache(first);
        String app = "overlapping_" + namespace;
        Key id = Key.of(UUID.randomUUID().toString());
        var seed = nativeRequest(app, id);
        runtime.executeSource("session.userId='member-123';", seed);
        String key = seed.getParentOfType(SessionBoxContext.class).getSession().getCacheKey();
        seed.shutdown();
        var admitted = nativeRequest(app, id);
        assertEquals("member-123", admitted.getScope(SessionScope.name).get("userId"));
        var logout = nativeRequest(app, id);
        logout.resetSession();
        logout.shutdown();
        assertTrue(second.get(key).isEmpty());
        admitted.shutdown();
        assertTrue(second.get(key).isEmpty(), "A request admitted before logout republished the authenticated session");
    }
    private Session seededSession() {
        Application application = new Application(Key.of("generation-session-test"));
        application.start(new ScriptingRequestBoxContext(runtime.getRuntimeContext()));
        Session session = new Session(Key.of(UUID.randomUUID().toString()), application, Duration.ofMinutes(5));
        session.getSessionScope().put("userId", "member-123");
        return session;
    }
    @Test void expiresInRedisWithoutAReaper() throws Exception {
        first.set("expiring", "value", Duration.ofSeconds(1));
        assertEquals("value", second.get("expiring").get());
        Thread.sleep(1150);
        assertTrue(second.get("expiring").isEmpty());
        assertEquals(0, first.getSize());
    }
    @Test void idleAccessExtendsIdleExpiryButNotAbsoluteExpiry() throws Exception {
        first.set("sliding", "value", Duration.ofSeconds(3), Duration.ofSeconds(1));
        for (int count = 0; count < 3; count++) {
            Thread.sleep(650);
            assertEquals("value", second.get("sliding").get());
        }
        Thread.sleep(1150);
        assertTrue(second.get("sliding").isEmpty());
    }
    @Test void clearsOnlyItsNamespaceAndFiltersOriginalKeys() {
        SessionRedis other = provider("other", namespace);
        try {
            other.set("keep", "unrelated");
            first.set("Session:One", "one");
            first.set("session:two", "two");
            assertEquals("one", second.get("SESSION:ONE").get());
            first.clearAll(key -> key.getName().equals("session:two"));
            assertTrue(second.get("session:two").isEmpty());
            first.clearAll();
            assertEquals("unrelated", other.get("keep").get());
        } finally { other.clearAll(); other.shutdown(); }
    }
    @Test void resetOnAccessKeepsAnActiveSessionCacheEntryAlive() throws Exception {
        first.getConfig().properties.put("resetTimeoutOnAccess", true);
        first.set("active", "value", Duration.ofSeconds(1));
        for (int index = 0; index < 3; index++) {
            Thread.sleep(650);
            assertEquals("value", first.get("active").get());
        }
        Thread.sleep(1150);
        assertTrue(second.get("active").isEmpty());
    }
    @Test void concurrentReadersDoNotWaitForAnotherRequestConsumer() throws Exception {
        first.set("browser", Struct.of("userId", "member-123"));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> heldRequest = executor.submit(() -> {
                first.get("browser").get();
                loaded.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(loaded.await(2, TimeUnit.SECONDS));
                List<Future<Object>> reads = new ArrayList<>();
                for (int index = 0; index < 24; index++) { reads.add(executor.submit(() -> second.get("browser").get())); }
                for (Future<Object> read : reads) { assertInstanceOf(IStruct.class, read.get(2, TimeUnit.SECONDS)); }
                assertFalse(heldRequest.isDone());
            } finally { release.countDown(); }
            heldRequest.get(2, TimeUnit.SECONDS);
        }
    }
    @Test void getOrSetUsesOneWinningValueAcrossConnections() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Object>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                SessionRedis provider = index % 2 == 0 ? first : second;
                results.add(executor.submit(() -> { start.await(); return provider.getOrSet("winner", () -> UUID.randomUUID().toString()); }));
            }
            start.countDown();
            Set<Object> values = new HashSet<>();
            for (var result : results) { values.add(result.get(3, TimeUnit.SECONDS)); }
            assertEquals(1, values.size());
        }
    }
    @Test void rejectsInvalidConfigurationAndNegativeExpiry() {
        assertThrows(IllegalArgumentException.class, () -> provider("sessions", "unsafe*"));
        assertThrows(IllegalArgumentException.class, () -> first.set("bad", "value", Duration.ofSeconds(-1)));
    }

    @Test void sharesNativeSessionBetweenSeparateJvms() throws Exception {
        String id = UUID.randomUUID().toString();
        assertTrue(peer(id, "write").contains("RESULT:written"));
        assertTrue(peer(id, "read").contains("RESULT:member-123:csrf-value"));
    }

    private String peer(String id, String action) throws Exception {
        var output = Files.createTempFile("bx-redis-peer-output-", ".log");
        try {
            Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), SessionPeer.class.getName(), System.getProperty("redis.port", "6379"),
                namespace, id, action).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Peer JVM did not finish"); }
            String text = Files.readString(output);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally { Files.deleteIfExists(output); }
    }
}
