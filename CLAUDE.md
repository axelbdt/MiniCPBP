# MiniCPBP — dev guide

## Project layout

| Path | Contents |
|---|---|
| `src/main/java/` | Java engine + constraints |
| `src/main/clojure/` | Clojure prototype layer (server, tracer, examples) |
| `src/main/cljs/` | ClojureScript UI (shadow-cljs) |
| `target/classes/` | Compiled Java + Clojure bytecode |
| `target/minicpbp-1.0.jar` | Uber-jar (distribution only) |

## Running the dev REPL

```bash
make start       # compile Java, start nREPL on port 34715
make stop        # kill the nREPL
make restart     # recompile Java and bounce — use after any Java change
```

`make start` uses the **exploded classpath** (`target/classes` + `src/main/clojure` + Maven deps) so you never need to rebuild the uber-jar during development.  The `make package` target (which assembles the uber-jar) is only needed for distribution.

The nREPL PID is stored in `.nrepl-pid`; the port in `.nrepl-port`.

## After a Java change

```
make restart
```

That's it. Under the hood: `mvn compile -q` → kill old process → start new one.

Do **not** try to hot-reload Java classes with `require :reload` — the JVM cannot unload already-loaded classes.  You must restart.

## After a Clojure-only change

No restart needed.  In the connected REPL:

```clojure
(require '[prototype.examples.queens :as q] :reload)
```

## Classpath resolution

`.depcp` caches the Maven dependency classpath.  It is regenerated automatically by `make` when `pom.xml` changes.  Delete it to force regeneration:

```bash
rm .depcp && make start
```

## Stale-jar trap (historical)

Before `Makefile` was added, the nREPL ran from the uber-jar (`java -cp target/minicpbp-1.0.jar launch.Repl`).  Because the JVM opens the JAR once and caches it, updating the JAR on disk without restarting the process had no effect.  The exploded-classpath approach eliminates this entirely: `target/classes/` is re-read from disk on every class load.

## Constraint graph visualiser

- Start the nREPL (`make start`), then open `http://localhost:3000`.
- Click **Solve** to run the queens solver, then switch to the **Constraint Graph** tab.
- The `/api/model-graph` endpoint calls `ModelGraphExporter/export` on the live solver.
- `AllDifferentDC` constraints over offset views are wired correctly: `ModelGraphExporter` resolves views to their base variable via `IntVar.getBaseVar()`.
