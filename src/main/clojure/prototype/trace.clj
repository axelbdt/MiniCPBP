(ns prototype.trace
  "Prototype tracer for MiniCPBP search. Subscribes to the listener mixin
  on Search (DFSearch / LDSearch) and records branching/backtrack events
  into an in-memory store so we can iterate on what the right frame
  shapes are.

  Throwaway by design: the schema discovered here ports to Java per
  plan-prototype-workflow.md §7, then this namespace goes away."
  (:import [minicpbp.search Search]
           [minicpbp.util Procedure]
           [java.util.concurrent.atomic AtomicLong]))

(set! *warn-on-reflection* true)

;; ----------------------------------------------------------------------------
;; Store

(defn make-store
  "Fresh trace store. One per search run."
  []
  {:frames (atom [])
   :seq    (AtomicLong. 0)
   :t0     (System/nanoTime)})

(defn frames
  "Snapshot of the recorded frames as a vector."
  [store]
  @(:frames store))

(defn clear!
  "Reset frames and sequence counter; t0 stays so timestamps remain comparable
  across clears within one session if desired."
  [store]
  (reset! (:frames store) [])
  (.set ^AtomicLong (:seq store) 0)
  store)

;; ----------------------------------------------------------------------------
;; Emission

(defn- emit!
  [store frame]
  (let [s  (.getAndIncrement ^AtomicLong (:seq store))
        ts (- (System/nanoTime) ^long (:t0 store))]
    (swap! (:frames store) conj (assoc frame :seq s :ts-ns ts))))

(defn- node-ctx
  [^Search s]
  {:node   (.currentNodeId s)
   :parent (.currentParentNodeId s)
   :depth  (.currentDepth s)})

(defn- branch-ctx
  [^Search s]
  (assoc (node-ctx s)
         :index (.currentBranchIndex s)
         :total (.currentBranchTotal s)))

;; ----------------------------------------------------------------------------
;; Listener installation
;;
;; Note: install-listeners! adds, never replaces. Calling it twice on the same
;; Search will double up emissions. Build a fresh search per run, or call
;; clear! on the store between runs and accept the redundant listeners.

(defn install-listeners!
  "Wire all six Search listeners to record events into `store`. Returns store."
  [^Search search store]
  (doto search
    (.onNodeEnter
     (reify Procedure
       (call [_] (emit! store (assoc (node-ctx search)   :t :node-enter)))))
    (.onBranch
     (reify Procedure
       (call [_] (emit! store (assoc (branch-ctx search) :t :branch-taken)))))
    (.onBranchReturn
     (reify Procedure
       (call [_] (emit! store (assoc (branch-ctx search) :t :branch-return)))))
    (.onFailure
     (reify Procedure
       (call [_] (emit! store (assoc (branch-ctx search) :t :failure)))))
    (.onSolution
     (reify Procedure
       (call [_] (emit! store (assoc (node-ctx search)   :t :solution)))))
    (.onNodeExit
     (reify Procedure
       (call [_] (emit! store (assoc (node-ctx search)   :t :node-exit))))))
  store)

;; ----------------------------------------------------------------------------
;; Inspection

(defn summary
  "Map of {event-type -> count}."
  [store]
  (frequencies (map :t (frames store))))

(defn inspect-frames
  "Pretty-print frames. Options:
     :types <set>  -- include only these :t values
     :head <n>     -- first n
     :tail <n>     -- last n"
  ([store] (inspect-frames store nil))
  ([store {:keys [types head tail]}]
   (let [fs (cond->> (frames store)
              types (filter (comp types :t))
              head  (take head)
              tail  (take-last tail))]
     (doseq [f fs]
       (println (pr-str f))))))
