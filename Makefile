PORT    := 34715
PIDFILE := .nrepl-pid
DEPCP   := .depcp

# Exploded classpath: compiled classes + Clojure sources + Maven deps.
# Used by the dev REPL so that `make restart` only needs `mvn compile`,
# not the slower `mvn package` / uber-jar assembly.
CP = target/classes:src/main/clojure:$(shell cat $(DEPCP) 2>/dev/null)

.PHONY: compile restart start stop package

# Regenerate dependency classpath whenever pom.xml changes.
$(DEPCP): pom.xml
	mvn dependency:build-classpath -q -Dmdep.outputFile=$(DEPCP)

# Fast recompile (Java only, no assembly).
compile:
	mvn compile -q

# Full uber-jar build (distribution / first-time setup only).
package:
	mvn package -q -DskipTests

start: compile $(DEPCP)
	java -cp "$(CP)" launch.Repl --port $(PORT) & echo $$! > $(PIDFILE)
	@echo "nREPL started on port $(PORT) — PID $$(cat $(PIDFILE))"

stop:
	@if [ -f $(PIDFILE) ]; then \
	  kill $$(cat $(PIDFILE)) 2>/dev/null && echo "nREPL stopped"; \
	  rm -f $(PIDFILE); \
	else \
	  echo "No $(PIDFILE) found — nothing to stop"; \
	fi

# Primary dev cycle after any Java change: recompile and bounce.
restart: compile stop start
