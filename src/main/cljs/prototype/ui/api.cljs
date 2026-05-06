(ns prototype.ui.api)

(defn- get-json! [path cb]
  (-> (js/fetch path)
      (.then #(.json %))
      (.then #(cb (js->clj % :keywordize-keys true)))
      (.catch #(js/console.error "fetch error" %))))

(defn fetch-tree! [cb]
  (get-json! "/api/tree" cb))

(defn fetch-frames! [cb]
  (get-json! "/api/frames" cb))

(defn run-queens! [n cb]
  (-> (js/fetch (str "/api/run-queens?n=" n) #js {:method "GET"})
      (.then #(.json %))
      (.then #(cb (js->clj % :keywordize-keys true)))
      (.catch (fn [err]
                (js/console.error "run-queens error" err)
                (cb {:error (str err)})))))
