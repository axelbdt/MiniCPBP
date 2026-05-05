(ns minicpbp.repl
  "REPL helpers for inspecting MiniCPBP solver state.

  Typical session:
    (require '[minicpbp.repl :as r])
    (def cp (r/make-solver))
    (def x  (r/make-int-var cp 0 9))
    (r/summary x)        ;; => \"x ∈ [0..9] (size=10)\"
    (r/domain x)         ;; => [0 1 2 3 4 5 6 7 8 9]"
  (:import [minicpbp.engine.core IntVar Solver]
           [minicpbp.cp Factory]))

(set! *warn-on-reflection* true)

(defn domain
  "Vector of values currently in the domain of an IntVar."
  [^IntVar v]
  (let [n (.size v)
        a (int-array n)]
    (.fillArray v a)
    (vec (sort (take n a)))))

(defn summary
  "Compact one-line summary of an IntVar."
  [^IntVar v]
  (let [n (.size v)
        nm (or (.getName v) "_")]
    (cond
      (= n 1) (format "%s = %d" nm (.min v))
      (= n (inc (- (.max v) (.min v))))
      (format "%s ∈ [%d..%d] (size=%d)" nm (.min v) (.max v) n)
      :else
      (format "%s ∈ %s (size=%d)" nm (pr-str (domain v)) n))))

(defn print-vars
  "Print summary of a collection of IntVars, one per line."
  [vars]
  (doseq [v vars] (println (summary v))))

(defn domains
  "Map of var-name -> current domain vector."
  [vars]
  (into {} (map (fn [^IntVar v] [(.getName v) (domain v)]) vars)))

(defn bound?
  "True if the variable is fixed to a single value."
  [^IntVar v]
  (.isBound v))

(defn make-solver
  "Fresh CP solver via Factory/makeSolver."
  ([] (Factory/makeSolver))
  ([by-copy?] (Factory/makeSolver (boolean by-copy?))))

(defn make-int-var
  "Create an IntVar with domain [min..max] (or of given size)."
  ([^Solver cp size] (Factory/makeIntVar cp (int size)))
  ([^Solver cp lo hi] (Factory/makeIntVar cp (int lo) (int hi))))
