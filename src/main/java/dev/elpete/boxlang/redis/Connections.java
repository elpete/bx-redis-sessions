package dev.elpete.boxlang.redis;

import java.time.Duration;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import redis.clients.jedis.*;

/** Connection construction only; no application, request, or browser ownership. */
public final class Connections {
    private Connections() {}

    static String text(IStruct options, String name, String fallback) {
        return String.valueOf(options.getOrDefault(Key.of(name), fallback));
    }

    static int number(IStruct options, String name, int fallback) {
        return Integer.parseInt(text(options, name, String.valueOf(fallback)));
    }

    static boolean flag(IStruct options, String name, boolean fallback) {
        String value = text(options, name, String.valueOf(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    static UnifiedJedis open(IStruct options) {
        int timeout = number(options, "timeout", 2000);
        int socketTimeout = number(options, "socketTimeout", number(options, "readTimeout", timeout));
        int poolWait = number(options, "poolWaitTimeout", 1000);
        int maxConnections = number(options, "maxConnections", 20);
        if (timeout <= 0 || socketTimeout <= 0 || poolWait <= 0 || maxConnections <= 0) {
            throw new IllegalArgumentException("Connection, socket and pool wait timeouts and maxConnections must be positive");
        }
        var configuration = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(timeout)
            .socketTimeoutMillis(socketTimeout)
            .database(number(options, "database", 0))
            .ssl(flag(options, "useSSL", false));
        String username = text(options, "username", "");
        String password = text(options, "password", "");
        if (!username.isEmpty()) { configuration.user(username); }
        if (!password.isEmpty()) { configuration.password(password); }
        var tls = new javax.net.ssl.SSLParameters();
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        configuration.sslParameters(tls);
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(maxConnections);
        pool.setMaxIdle(number(options, "maxIdleConnections", number(options, "idleConnections", 5)));
        pool.setMaxWait(Duration.ofMillis(poolWait));
        pool.setMinEvictableIdleDuration(Duration.ofMillis(number(options, "maxIdleTime", 30000)));
        pool.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        pool.setTestWhileIdle(true);
        return RedisClient.builder().hostAndPort(text(options, "host", "127.0.0.1"), number(options, "port", 6379))
            .clientConfig(configuration.build()).poolConfig(pool).build();
    }
}
