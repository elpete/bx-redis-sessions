package dev.elpete.boxlang.redis;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.cache.ICacheEntry;
import ortus.boxlang.runtime.cache.filters.ICacheKeyFilter;
import ortus.boxlang.runtime.cache.providers.ICacheProvider;
import ortus.boxlang.runtime.cache.store.AbstractStore;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.*;
import ortus.boxlang.runtime.util.conversion.ObjectMarshaller;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/** Each entry and its expiry are committed atomically on one Redis hash slot. */
public final class RedisStore extends AbstractStore {
    private static final String WRITE = """
        if ARGV[5]=='nx' and redis.call('exists',KEYS[1])==1 then return 0 end
        local t=redis.call('time'); local now=t[1]*1000+math.floor(t[2]/1000)
        local absolute=tonumber(ARGV[3]); local idle=tonumber(ARGV[4]); local ttl=absolute
        if ttl==0 or (idle>0 and idle<ttl) then ttl=idle end
        local deadline=0; if absolute>0 then deadline=now+absolute end
        redis.call('hset',KEYS[1],'payload',ARGV[1],'name',ARGV[2],'deadline',deadline,'absolute',absolute,'idle',idle,'hits',0)
        if ttl>0 then redis.call('pexpire',KEYS[1],ttl) else redis.call('persist',KEYS[1]) end
        return 1
        """;
    private static final String READ = """
        local payload=redis.call('hget',KEYS[1],'payload'); if not payload then return {} end
        local hits=tonumber(redis.call('hget',KEYS[1],'hits') or '0')
        if ARGV[1]=='touch' then
          hits=redis.call('hincrby',KEYS[1],'hits',1)
          local idle=tonumber(redis.call('hget',KEYS[1],'idle') or '0')
          if idle>0 or ARGV[2]=='reset' then
            local deadline=tonumber(redis.call('hget',KEYS[1],'deadline') or '0'); local ttl=idle
            if ARGV[2]=='reset' then
              local absolute=tonumber(redis.call('hget',KEYS[1],'absolute') or '0')
              if absolute>0 then
                local t=redis.call('time'); deadline=t[1]*1000+math.floor(t[2]/1000)+absolute
                redis.call('hset',KEYS[1],'deadline',deadline)
                if ttl==0 or absolute<ttl then ttl=absolute end
              end
            end
            if deadline>0 then
              local t=redis.call('time'); local now=t[1]*1000+math.floor(t[2]/1000)
              if ttl==0 then ttl=deadline-now else ttl=math.min(ttl,deadline-now) end
            end
            if ttl>0 then redis.call('pexpire',KEYS[1],ttl)
            elseif deadline>0 then redis.call('del',KEYS[1]); return {} end
          end
        end
        return {payload,tostring(hits)}
        """;
    private UnifiedJedis client;
    private String prefix;
    private boolean caseSensitive;

    public RedisStore() { this.distributed = true; }

    @Override public RedisStore init(ICacheProvider provider, IStruct config) {
        this.provider = provider;
        this.config = config;
        String namespace = Connections.text(config, "keyprefix", "boxlang-community");
        if (!namespace.matches("[A-Za-z0-9:_-]{1,100}")) {
            throw new IllegalArgumentException("keyprefix must contain 1–100 letters, digits, colons, underscores or hyphens");
        }
        this.prefix = namespace + ":" + encode(provider.getName().getName()) + ":";
        this.caseSensitive = Connections.flag(config, "cacheKeyCaseSensitivity", false);
        client = Connections.open(config);
        try { client.ping(); } catch (RuntimeException failure) { client.close(); throw failure; }
        return this;
    }

    public UnifiedJedis client() { return client; }
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private String redisKey(Key key) {
        String name = caseSensitive ? key.getName() : key.getName().toLowerCase(Locale.ROOT);
        return prefix + encode(name);
    }
    private String serialize(ICacheEntry entry) {
        return Base64.getEncoder().encodeToString(ObjectMarshaller.serialize(context(), entry));
    }
    private ortus.boxlang.runtime.context.IBoxContext context() {
        return RequestBoxContext.getCurrent(BoxRuntime.getInstance().getRuntimeContext());
    }

    public boolean write(Key key, ICacheEntry entry, boolean onlyAbsent) {
        long absolute = Math.multiplyExact(entry.timeout(), 1000L);
        long idle = Connections.flag(config, "useLastAccessTimeouts", true)
            ? Math.multiplyExact(entry.lastAccessTimeout(), 1000L) : 0;
        if (absolute < 0 || idle < 0) { throw new IllegalArgumentException("Cache timeouts cannot be negative"); }
        return ((Number)client.eval(WRITE, List.of(redisKey(key)), List.of(serialize(entry), key.getName(),
            Long.toString(absolute), Long.toString(idle), onlyAbsent ? "nx" : "set"))).longValue() == 1;
    }

    private ICacheEntry read(Key key, boolean touch) {
        boolean reset = Connections.flag(config, "resetTimeoutOnAccess", false);
        List<?> result = (List<?>)client.eval(READ, List.of(redisKey(key)),
            List.of(touch ? "touch" : "quiet", reset ? "reset" : "fixed"));
        if (result.isEmpty()) { return null; }
        Object decoded = ObjectMarshaller.deserialize(context(), Base64.getDecoder().decode((String)result.getFirst()));
        if (!(decoded instanceof ICacheEntry entry)) { throw new IllegalStateException("Invalid Redis cache entry type"); }
        if (touch) { entry.touchLastAccessed(); }
        if (touch && reset) { entry.resetCreated(); }
        if (entry instanceof ortus.boxlang.runtime.cache.BoxCacheEntry concrete) {
            concrete.setHits(Long.parseLong((String)result.get(1)));
        }
        return entry;
    }

    private Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        ScanParams params = new ScanParams().match(prefix + "*").count(250);
        scan(cursor -> client.scan(cursor, params), names);
        return names;
    }

    private void scan(Function<String, ScanResult<String>> scanner, Set<String> names) {
        String cursor = "0";
        do {
            ScanResult<String> page = scanner.apply(cursor);
            for (String key : page.getResult()) {
                String name = client.hget(key, "name");
                if (name != null) { names.add(name); }
            }
            cursor = page.getCursor();
        } while (!cursor.equals("0"));
    }

    @Override public void shutdown() { client.close(); }
    @Override public int flush() { return 0; }
    @Override public void evict() { /* Redis owns TTL expiration; no JVM-wide eviction. */ }
    @Override public int getSize() { return names().size(); }
    @Override public void clearAll() { clearAll(key -> true); }
    @Override public boolean clearAll(ICacheKeyFilter filter) {
        boolean changed = false;
        for (Key key : getKeys(filter)) { changed = clear(key) || changed; }
        return changed;
    }
    @Override public boolean clear(Key key) { return client.del(redisKey(key)) > 0; }
    @Override public IStruct clear(Key... keys) { return collect(keys, this::clear); }
    @Override public Key[] getKeys() { return getKeysStream().toArray(Key[]::new); }
    @Override public Key[] getKeys(ICacheKeyFilter filter) { return getKeysStream(filter).toArray(Key[]::new); }
    @Override public Stream<Key> getKeysStream() { return names().stream().map(Key::of); }
    @Override public Stream<Key> getKeysStream(ICacheKeyFilter filter) { return getKeysStream().filter(filter); }
    @Override public boolean lookup(Key key) { return client.exists(redisKey(key)); }
    @Override public IStruct lookup(Key... keys) { return collect(keys, this::lookup); }
    @Override public IStruct lookup(ICacheKeyFilter filter) { return lookup(getKeys(filter)); }
    @Override public ICacheEntry get(Key key) { return read(key, true); }
    @Override public IStruct get(Key... keys) { return collect(keys, this::get); }
    @Override public IStruct get(ICacheKeyFilter filter) { return get(getKeys(filter)); }
    @Override public ICacheEntry getQuiet(Key key) { return read(key, false); }
    @Override public IStruct getQuiet(Key... keys) { return collect(keys, this::getQuiet); }
    @Override public IStruct getQuiet(ICacheKeyFilter filter) { return getQuiet(getKeys(filter)); }
    @Override public void set(Key key, ICacheEntry entry) { write(key, entry, false); }
    @Override public void set(IStruct entries) {
        entries.forEach((key, value) -> set(key, (ICacheEntry)value));
    }
    private IStruct collect(Key[] keys, Function<Key, ?> operation) {
        IStruct result = new Struct();
        for (Key key : keys) { result.put(key, operation.apply(key)); }
        return result;
    }
}
