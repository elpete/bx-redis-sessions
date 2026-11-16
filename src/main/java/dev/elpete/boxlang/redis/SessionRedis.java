package dev.elpete.boxlang.redis;

import java.time.Duration;
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
    private static final Key WRITEBACK = Key.of("communityRedisSessionWriteback");

    /** Serialized session scopes need one writeback after the native request completes. */
    private Object withSessionWriteback(Object value) {
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
        ICacheEntry entry = store.get(Key.of(key));
        if (entry == null) { stats.recordMiss(); return Attempt.empty(); }
        stats.recordHit();
        return entry.value().map(this::withSessionWriteback);
    }
    @Override public Attempt<Object> getQuiet(String key) {
        ICacheEntry entry = store.getQuiet(Key.of(key));
        return entry == null ? Attempt.empty() : entry.value();
    }
    @Override public IStruct get(String... keys) {
        IStruct result = new Struct();
        for (String key : keys) { result.put(key, get(key).orElse(null)); }
        return result;
    }
    @Override public IStruct get(ICacheKeyFilter filter) { return get(getKeysStream(filter).toArray(String[]::new)); }
    @Override public void setQuiet(Key key, ICacheEntry entry) { store.set(key, entry); }

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
        store.set(Key.of(key), entry(key, value, timeout, idle, metadata));
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
        if (store.write(Key.of(key), entry(key, value, timeout, idle, metadata), true)) { return withSessionWriteback(value); }
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
