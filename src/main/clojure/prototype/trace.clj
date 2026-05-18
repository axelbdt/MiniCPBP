(ns prototype.trace
  "Reader over minicpbp.util.TraceLog: enables in-memory trace recording on
  the Java side, snapshots the frames, and adapts them to the idiomatic
  Clojure shape the visualization expects (keywordized keys, keyword :t
  values).

  TraceLog is a static singleton — there's no per-run store to thread
  through callers. Sequence:

    (clear!)     ; reset frames
    (enable!)    ; start recording
    (.solve search)
    (frames)     ; read"
  (:require [clojure.walk :as walk])
  (:import [minicpbp.util TraceLog]))

(set! *warn-on-reflection* true)

;; ----------------------------------------------------------------------------
;; Lifecycle

(defn enable!  [] (TraceLog/enable))
(defn disable! [] (TraceLog/disable))
(defn enabled? [] (TraceLog/isEnabled))
(defn clear!   [] (TraceLog/clear))

;; ----------------------------------------------------------------------------
;; Read

(defn frames
  "Snapshot of TraceLog's frames. Java emits string keys and string :t values
  (wire convention from plan-observability.md §4.2); we keywordize keys and
  turn the :t value into a keyword so existing consumers can pattern-match
  with `#{:solution :failure}` etc."
  []
  (->> (TraceLog/snapshot)
       (mapv #(into {} %))
       walk/keywordize-keys
       (mapv #(update % :t keyword))))

;; ----------------------------------------------------------------------------
;; Inspection

(defn summary
  "Map of {event-type -> count} over the current TraceLog snapshot."
  []
  (frequencies (map :t (frames))))

(defn inspect-frames
  "Pretty-print the current TraceLog snapshot. Options:
     :types <set>  -- include only these :t values
     :head <n>     -- first n
     :tail <n>     -- last n"
  ([] (inspect-frames nil))
  ([{:keys [types head tail]}]
   (let [fs (cond->> (frames)
              types (filter (comp types :t))
              head  (take head)
              tail  (take-last tail))]
     (doseq [f fs]
       (println (pr-str f))))))
