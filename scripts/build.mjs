import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, rmSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const runtimeJar = process.env.BOXLANG_JAR;
if (!runtimeJar) { throw new Error("Set BOXLANG_JAR to the BoxLang 1.19+ runtime JAR"); }
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: "inherit" });
  if (result.error) { throw result.error; }
  if (result.status !== 0) { process.exit(result.status ?? 1); }
}
run("mvn", ["-q", `-Dboxlang.jar=${resolve(runtimeJar)}`, `-Dredis.port=${process.env.REDIS_TEST_PORT ?? "6379"}`, "package"]);
const stage = resolve(root, "target/package");
rmSync(stage, { recursive: true, force: true });
mkdirSync(resolve(stage, "libs"), { recursive: true });
for (const file of ["ModuleConfig.bx", "box.json", "README.md", "VALIDATION.md"]) {
  copyFileSync(resolve(root, file), resolve(stage, file));
}
copyFileSync(resolve(root, "target/bx-redis-sessions-0.1.0.jar"), resolve(stage, "libs/bx-redis-sessions-0.1.0.jar"));
const archive = resolve(root, "target/bx-redis-sessions-0.1.0.zip");
rmSync(archive, { force: true });
run("zip", ["-qr", archive, "."], stage);
console.log(`Module archive: ${archive}`);
