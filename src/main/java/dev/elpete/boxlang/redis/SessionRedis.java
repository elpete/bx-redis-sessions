package dev.elpete.boxlang.redis;

import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import ortus.boxlang.runtime.cache.*;
import ortus.boxlang.runtime.cache.filters.ICacheKeyFilter;
import ortus.boxlang.runtime.cache.providers.*;
import ortus.boxlang.runtime.config.segments.CacheConfig;
import ortus.boxlang.runtime.application.Session;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.context.SessionBoxContext;
import ortus.boxlang.runtime.dynamic.Attempt;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.services.CacheService;
import ortus.boxlang.runtime.types.*;
import redis.clients.jedis.*;

/** Native BoxLang provider. There is deliberately no request/session lease. */
public final class SessionRedis extends AbstractCacheProvider {
    protected RedisStore store;
    /** Readiness admission must exclude writers that can resurrect revoked snapshots. */
    public String getSessionWritebackProtocol() { return "generation-v1"; }
    // Keys are weak: tracking a snapshot must never keep its Session alive.
    private final Map<Session, Map<String, String>> sessionGenerations = Collections.synchronizedMap(new WeakHashMap<>());
    private String bindingKey(String key) {
        return Connections.flag(config.properties, "cacheKeyCaseSensitivity", false) ? key : key.toLowerCase(Locale.ROOT);
    }
    private String observedGeneration(String key, Object value) {
        if (!(value instanceof Session session)) { return null; }
        Map<String, String> observed = sessionGenerations.get(session);
        return observed == null ? null : observed.get(bindingKey(key));
    }
    private Object rememberGeneration(String key, Object value, String generation) {
        if (value instanceof Session session) {
            sessionGenerations.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>()).put(bindingKey(key), generation);
        }
        return value;
    }
    private static final Key WRITEBACK = Key.of("communityRedisSessionWriteback");

    /** Serialized session scopes need one writeback after the native request completes. */
    private Object withSessionWriteback(String key, Object value, String generation) {
        rememberGeneration(key, value, generation);
        var current = RequestBoxContext.getCurrent();
        if (!(value instanceof Session) || current == null) { return value; }
        var requestContext = current.getRequestContext();
        if (requestContext == null || requestContext.getAttachment(WRITEBACK) != null) { return value; }
        requestContext.putAttachment(WRITEBACK, true);
        requestContext.registerShutdownListener(ignored -> {
            var sessionContext = requestContext.getParentOfType(SessionBoxContext.class);
            if (sessionContext != null && !sessionContext.getSession().isShutdown()
                && requestContext.getApplicationContext().getApplication().getSessionsCache() == this) {
                sessionContext.persistSession(requestContext);
            }
        });
        return value;
    }

    @Override public synchronized ICacheProvider configure(CacheService service, CacheConfig config) {
        if (enabled.get()) { throw new IllegalStateException("Cache provider is already configured"); }
        super.configure(service, config);
        store = new RedisStore().init(this, config.properties);
        enabled.set(true);
        reportingEnabled = true;
        return this;
    }

    @Override public void shutdown() {
        if (enabled.getAndSet(false)) { store.shutdown(); }
    }
    @Override public RedisStore getObjectStore() { return store; }
    @Override public void reap() { stats.recordReap(); }
    @Override public int getSize() { return store.getSize(); }
    @Override public void clearAll() { store.clearAll(); }
    @Override public boolean clearAll(ICacheKeyFilter filter) { return store.clearAll(filter); }
    @Override public boolean clearQuiet(String key) { return store.clear(Key.of(key)); }
    @Override public boolean clear(String key) { return clearQuiet(key); }
    @Override public IStruct clear(String... keys) { return store.clear(keys(keys)); }
    @Override public Array getKeys() { return Array.fromList(getKeysStream().toList()); }
    @Override public Array getKeys(ICacheKeyFilter filter) { return Array.fromList(getKeysStream(filter).toList()); }
    @Override public Stream<String> getKeysStream() { return store.getKeysStream().map(Key::getName); }
    @Override public Stream<String> getKeysStream(ICacheKeyFilter filter) { return store.getKeysStream(filter).map(Key::getName); }
    @Override public boolean lookup(String key) { return lookupQuiet(key); }
    @Override public boolean lookupQuiet(String key) { return store.lookup(Key.of(key)); }
    @Override public IStruct lookup(String... keys) { return store.lookup(keys(keys)); }
    @Override public IStruct lookup(ICacheKeyFilter filter) { return store.lookup(filter); }
    @Override public Attempt<Object> get(String key) {
        RedisStore.VersionedEntry observed = store.readVersioned(Key.of(key), true);
        if (observed == null) { stats.recordMiss(); return Attempt.empty(); }
        stats.recordHit();
        return observed.entry().value().map(value -> withSessionWriteback(key, value, observed.generation()));
    }
    @Override public Attempt<Object> getQuiet(String key) {
        RedisStore.VersionedEntry observed = store.readVersioned(Key.of(key), false);
        return observed == null ? Attempt.empty() : observed.entry().value().map(value -> rememberGeneration(key, value, observed.generation()));
    }
    @Override public IStruct get(String... keys) {
        IStruct result = new Struct();
        for (String key : keys) { result.put(key, get(key).orElse(null)); }
        return result;
    }
    @Override public IStruct get(ICacheKeyFilter filter) { return get(getKeysStream(filter).toArray(String[]::new)); }
    @Override public void setQuiet(Key key, ICacheEntry entry) { writeEntry(key.getName(), entry, false); }
    private String writeEntry(String key, ICacheEntry entry, boolean onlyAbsent) {
        Object value = entry.value().orElse(null);
        String generation = store.writeVersioned(Key.of(key), entry, onlyAbsent, observedGeneration(key, value));
        if (generation != null) { rememberGeneration(key, value, generation); }
        return generation;
    }

    private ICacheEntry entry(String key, Object value, Object timeout, Object idle, IStruct metadata) {
        long seconds = duration(timeout, "defaultTimeout", 3600);
        long idleSeconds = duration(idle, "defaultLastAccessTimeout", 0);
        return new BoxCacheEntry(name, seconds, idleSeconds, Key.of(key), value, metadata);
    }
    private long duration(Object value, String setting, int fallback) {
        Object effective = value == null ? config.properties.getOrDefault(Key.of(setting), fallback) : value;
        Duration duration = toDuration(effective);
        if (duration.isNegative()) { throw new IllegalArgumentException("Cache timeout cannot be negative"); }
        return duration.isZero() ? 0 : Math.max(1, duration.getSeconds());
    }
    @Override public void set(String key, Object value, Object timeout, Object idle, IStruct metadata) {
        writeEntry(key, entry(key, value, timeout, idle, metadata), false);
    }
    @Override public void set(String key, Object value, Object timeout, Object idle) { set(key, value, timeout, idle, new Struct()); }
    @Override public void set(String key, Object value, Object timeout) { set(key, value, timeout, null); }
    @Override public void set(String key, Object value) { set(key, value, null, null); }
    @Override public void set(IStruct entries) { set(entries, null, null); }
    @Override public void set(IStruct entries, Object timeout, Object idle) {
        entries.forEach((key, value) -> set(key.getName(), value, timeout, idle));
    }
    @Override public Object getOrSet(String key, Supplier<Object> producer, Object timeout, Object idle, IStruct metadata) {
        Attempt<Object> existing = get(key);
        if (existing.isPresent()) { return existing.get(); }
        Object value = producer.get();
        String generation = writeEntry(key, entry(key, value, timeout, idle, metadata), true);
        if (generation != null) { return withSessionWriteback(key, value, generation); }
        // Another writer won. Never overwrite its value with our stale result.
        return get(key).orElse(value);
    }
    @Override public Object getOrSet(String key, Supplier<Object> producer, Object timeout, Object idle) { return getOrSet(key, producer, timeout, idle, new Struct()); }
    @Override public Object getOrSet(String key, Supplier<Object> producer, Object timeout) { return getOrSet(key, producer, timeout, null); }
    @Override public Object getOrSet(String key, Supplier<Object> producer) { return getOrSet(key, producer, null, null); }
    @Override public IStruct getCachedObjectMetadata(String key) {
        ICacheEntry entry = store.getQuiet(Key.of(key));
        return entry == null ? new Struct() : entry.toStruct();
    }
    @Override public IStruct getStoreMetadataReport() { return getStoreMetadataReport(Integer.MAX_VALUE); }
    @Override public IStruct getStoreMetadataReport(int limit) {
        IStruct report = new Struct();
        getKeysStream().limit(Math.max(0, limit)).forEach(key -> report.put(key, getCachedObjectMetadata(key)));
        return report;
    }
    @Override public IStruct getStoreMetadataKeyMap() {
        return Struct.of("hits", "hits", "timeout", "timeout", "lastAccessTimeout", "lastAccessTimeout",
            "created", "created", "lastAccessed", "lastAccessed");
    }
    private Key[] keys(String[] names) { return Stream.of(names).map(Key::of).toArray(Key[]::new); }
}
