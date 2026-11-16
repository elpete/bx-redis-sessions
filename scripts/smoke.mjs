import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve, dirname, delimiter } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const runtimeJar = process.env.BOXLANG_JAR;
if (!runtimeJar) { throw new Error("Set BOXLANG_JAR to the BoxLang runtime JAR"); }
// The servlet runtime JAR needs its container API on a standalone test classpath.
// Leave this unset when testing with a standalone BoxLang core JAR.
const servletApi = process.env.SERVLET_API_JAR;
const smokeClasses = resolve(root, "target/smoke-classes");
mkdirSync(smokeClasses, { recursive: true });
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, stdio: "inherit", timeout: 30000 });
  if (result.error) { throw result.error; }
  if (result.status !== 0) { throw new Error(`${command} exited with ${result.status}`); }
}
run("javac", ["-cp", resolve(runtimeJar), "-d", smokeClasses,
  resolve(root, "src/test/java/dev/elpete/boxlang/redis/ModuleSmoke.java")]);
const home = mkdtempSync(resolve(tmpdir(), "bx-redis-module-smoke-"));
try {
  cpSync(resolve(root, "target/package"), resolve(home, "modules/bx-redis-sessions"), { recursive: true });
  const classpath = [resolve(runtimeJar), smokeClasses];
  if (servletApi) { classpath.push(resolve(servletApi)); }
  run("java", ["-cp", classpath.join(delimiter), "dev.elpete.boxlang.redis.ModuleSmoke", home,
    process.env.REDIS_TEST_PORT ?? "6379"]);
} finally { rmSync(home, { recursive: true, force: true }); }
