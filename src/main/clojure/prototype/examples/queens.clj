(ns prototype.examples.queens
  "4-queens smoke test for prototype.trace. Small enough that the full
  event stream fits on one screen — return here when iterating on frame
  shapes to eyeball the trace by hand.

  Workflow:
    (require '[prototype.examples.queens :as q] :reload)
    (def store (q/run!))"
  (:refer-clojure :exclude [run!])
  (:require [prototype.trace :as t])
  (:import [minicpbp.cp BranchingScheme Factory]
           [minicpbp.engine.core IntVar Solver]
           [minicpbp.search DFSearch]))

(set! *warn-on-reflection* true)

(defn build-search
  "Build a fresh n-queens DFSearch using firstFail branching.
  Returns {:solver, :search, :vars}."
  ([] (build-search 4))
  ([n]
   (let [^Solver cp (Factory/makeSolver false)
         q          (Factory/makeIntVarArray cp (int n) (int n))]
     (doseq [i (range n)
             j (range (inc i) n)]
       (let [^IntVar qi (aget ^objects q i)
             ^IntVar qj (aget ^objects q j)
             pos        (int (- j i))
             neg        (int (- i j))]
         (.post cp (Factory/notEqual qi qj))
         (.post cp (Factory/notEqual qi qj pos))
         (.post cp (Factory/notEqual qi qj neg))))
     {:solver cp
      :search (Factory/makeDfs cp (BranchingScheme/firstFail q))
      :vars   q})))

(defn run!
  "Build a fresh n-queens search, install the tracer, solve all
  solutions, return the store. Defaults to n=4."
  ([] (run! 4))
  ([n]
   (let [{:keys [^DFSearch search]} (build-search n)
         store                       (t/make-store)]
     (t/install-listeners! search store)
     (.solve search)
     store)))

(comment

  ;; one-shot demo
  (def store (run!))

  (t/summary store)
  ;; expected for n=4:
  ;;   2 solutions, ~17 nodes, ~13 failures (firstFail order).
  ;;   exact counts will jiggle as we evolve the listener mixin,
  ;;   re-baseline by running once.

  ;; full trace, all events
  (t/inspect-frames store)

  ;; just the decisions and the failures that follow
  (t/inspect-frames store {:types #{:branch-taken :failure}})

  ;; only the leaves
  (t/inspect-frames store {:types #{:solution}})

  ;; iterate: edit prototype/trace.clj, then
  (require 'prototype.trace :reload)
  (require 'prototype.examples.queens :reload)
  (def store (run!))

  )
