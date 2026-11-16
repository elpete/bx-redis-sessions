package dev.elpete.boxlang.redis;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.*;
import ortus.boxlang.runtime.scopes.*;
import ortus.boxlang.runtime.types.Struct;

/** Run with runtime + servlet API + this class only; the module loads its own JAR. */
public final class ModuleSmoke {
    public static void main(String[] args) {
        var runtime = BoxRuntime.getInstance(false, null, args[0]);
        try {
            var provider = runtime.getCacheService().createCache("sessions", "SessionRedis",
                Struct.of("host", "127.0.0.1", "port", Integer.parseInt(args[1]), "keyprefix", "module-smoke"));
            if (provider.getClass().getClassLoader() == ModuleSmoke.class.getClassLoader()) {
                throw new AssertionError("Provider did not load through the module classloader");
            }
            var context = new ScriptingRequestBoxContext(runtime.getRuntimeContext());
            runtime.executeSource("bx:application name='module-smoke' sessionManagement=true sessionStorage='sessions' sessionCluster=true; session.member='smoke-user';", context);
            var session = context.getParentOfType(SessionBoxContext.class);
            session.persistSession(context);
            String oldKey = session.getSession().getCacheKey();
            context.getApplicationListener().rotateSession();
            if (provider.get(oldKey).isPresent()) { throw new AssertionError("Old native session was not removed"); }
            if (!"smoke-user".equals(context.getScope(SessionScope.name).get("member"))) {
                throw new AssertionError("Native session rotation did not preserve member");
            }
            provider.clearAll();
            System.out.println("MODULE_SMOKE:passed");
        } finally { runtime.shutdown(); }
    }
}
