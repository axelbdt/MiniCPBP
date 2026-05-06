(ns prototype.server
  (:require [org.httpkit.server :as hk]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [prototype.trace :as t]
            [prototype.examples.queens :as queens]))

(set! *warn-on-reflection* true)

;; ----------------------------------------------------------------------------
;; State

(defonce current-store (atom nil))
(defonce stop-fn (atom nil))

(defn set-store! [store]
  (reset! current-store store))

;; ----------------------------------------------------------------------------
;; Tree reconstruction

(defn- frames->tree [frames]
  (when (seq frames)
    (let [nodes (reduce (fn [acc {:keys [node parent depth t] :as frame}]
                          (-> acc
                              (update-in [node :events] (fnil conj []) t)
                              (assoc-in [node :parent] parent)
                              (assoc-in [node :depth] depth)
                              (cond->
                               (#{:solution :failure} t)
                                (assoc-in [node :outcome] t))))
                        {}
                        frames)
          with-children (reduce (fn [acc [nid {:keys [parent]}]]
                                  (if (>= (int parent) 0)
                                    (update-in acc [parent :children] (fnil conj []) nid)
                                    acc))
                                nodes
                                nodes)
          root-id (ffirst (filter (fn [[_ {:keys [parent]}]] (= -1 (int parent)))
                                  with-children))]
      (letfn [(build [nid]
                (let [{:keys [parent depth outcome events children]} (get with-children nid)]
                  {:id nid
                   :parent parent
                   :depth depth
                   :outcome (name (or outcome :internal))
                   :events (mapv name events)
                   :children (mapv build (sort (or children [])))}))]
        (build root-id)))))

;; ----------------------------------------------------------------------------
;; Static file serving

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "js" "application/javascript"
   "css" "text/css"
   "map" "application/json"})

(defn- ext [path]
  (last (clojure.string/split path #"\.")))

(defn- serve-static [path]
  (let [clean (if (= path "/") "/index.html" path)
        file (io/file "public" (subs clean 1))]
    (if (.exists file)
      {:status 200
       :headers {"Content-Type" (get mime-types (ext clean) "application/octet-stream")}
       :body file}
      {:status 404 :body "Not found"})))

;; ----------------------------------------------------------------------------
;; Handler

(defn- parse-qs
  "Parse a query string like \"n=4&foo=bar\" into {\"n\" \"4\"}."
  [qs]
  (when (seq qs)
    (into {}
          (for [pair (clojure.string/split qs #"&")
                :let [[k v] (clojure.string/split pair #"=" 2)]
                :when (seq k)]
            [k (or v "")]))))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- handler [req]
  (case (:uri req)
    "/api/frames"
    (json-response (if @current-store (t/frames @current-store) []))

    "/api/tree"
    (json-response (if @current-store (frames->tree (t/frames @current-store)) nil))

    "/api/run-queens"
    (let [n (or (some-> (get (parse-qs (:query-string req)) "n") parse-long) 4)]
      (try
        (reset! current-store (queens/run! n))
        (json-response {:status "ok" :n n})
        (catch Exception e
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:error (.getMessage e)})})))

    (serve-static (:uri req))))

;; ----------------------------------------------------------------------------
;; Lifecycle

(defn start!
  "Start the companion API server on port. Replaces any running instance."
  ([] (start! 3000))
  ([port]
   (when @stop-fn (@stop-fn))
   (reset! stop-fn (hk/run-server handler {:port port}))
   (println (str "UI server started → http://localhost:" port))))

(defn stop! []
  (when @stop-fn
    (@stop-fn)
    (reset! stop-fn nil)
    (println "UI server stopped.")))
