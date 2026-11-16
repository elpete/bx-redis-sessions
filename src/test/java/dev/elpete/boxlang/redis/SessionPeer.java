package dev.elpete.boxlang.redis;

import java.nio.file.Files;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.config.segments.CacheConfig;
import ortus.boxlang.runtime.context.*;
import ortus.boxlang.runtime.scopes.*;
import ortus.boxlang.runtime.types.Struct;

/** A separate JVM, with its own BoxLang runtime and no shared in-process state. */
public final class SessionPeer {
    public static void main(String[] args) throws Exception {
        var runtime = BoxRuntime.getInstance(false, null, Files.createTempDirectory("bx-redis-peer-").toString());
        try {
            var provider = new SessionRedis();
            provider.configure(runtime.getCacheService(), new CacheConfig(Key.of("sessions"), Key.of("SessionRedis"),
                Struct.of("port", Integer.parseInt(args[0]), "keyprefix", args[1])));
            runtime.getCacheService().registerCache(provider);
            var context = new ScriptingRequestBoxContext(runtime.getRuntimeContext());
            context.setSessionID(Key.of(args[2]));
            RequestBoxContext.runInContext(context, ignored -> {
                runtime.executeSource("bx:application name='separate-runtime' sessionManagement=true sessionStorage='sessions' sessionCluster=true;", context);
                return null;
            });
            if (args[3].equals("write")) {
                runtime.executeSource("session.userId='member-123'; session.csrf='csrf-value';", context);
                context.shutdown();
                System.out.println("RESULT:written");
            } else {
                var session = context.getScope(SessionScope.name);
                System.out.println("RESULT:" + session.get("userId") + ":" + session.get("csrf"));
            }
        } finally { runtime.shutdown(); }
    }
}
