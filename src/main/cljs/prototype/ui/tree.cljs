(ns prototype.ui.tree
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            ["d3" :as d3]))

(def ^:private outcome-color
  {"solution" "#27ae60"
   "failure"  "#c0392b"
   "internal" "#2980b9"})

(def ^:private node-r 5)

(defn- tree-width [root]
  (* 200 (inc (.-height root))))

(defn- tree-height [root]
  (max 400 (* 22 (count (.descendants root)))))

(defn- render! [container data on-select]
  (let [margin    {:top 20 :right 120 :bottom 20 :left 60}
        root      (-> (d3/hierarchy (clj->js data))
                      (.sort (fn [a b] (- (-> a .-data .-id) (-> b .-data .-id)))))
        w         (tree-width root)
        h         (tree-height root)
        tree-fn   (-> (d3/tree) (.size #js [h w]))
        _         (tree-fn root)]
    (-> (d3/select container) (.selectAll "*") .remove)
    (let [svg (-> (d3/select container)
                  (.append "svg")
                  (.attr "width"  (+ w (:left margin) (:right margin)))
                  (.attr "height" (+ h (:top margin) (:bottom margin)))
                  (.append "g")
                  (.attr "transform" (str "translate(" (:left margin) "," (:top margin) ")")))]
      ;; links
      (-> svg
          (.selectAll ".link")
          (.data (.links root))
          .enter
          (.append "path")
          (.attr "fill" "none")
          (.attr "stroke" "#555")
          (.attr "stroke-opacity" "0.4")
          (.attr "stroke-width" "1.5")
          (.attr "d" (-> (d3/linkHorizontal)
                         (.x (fn [d] (.-y d)))
                         (.y (fn [d] (.-x d))))))
      ;; nodes
      (let [node (-> svg
                     (.selectAll ".node")
                     (.data (.descendants root))
                     .enter
                     (.append "g")
                     (.attr "transform" (fn [d] (str "translate(" (.-y d) "," (.-x d) ")")))
                     (.style "cursor" "pointer")
                     (.on "click" (fn [_ d] (on-select (js->clj (.-data d) :keywordize-keys true)))))]
        (-> node
            (.append "circle")
            (.attr "r" node-r)
            (.attr "fill" (fn [d] (get outcome-color (-> d .-data .-outcome) "#2980b9")))
            (.attr "stroke" "#fff")
            (.attr "stroke-width" "1.5"))
        (-> node
            (.append "text")
            (.attr "dy" "0.31em")
            (.attr "x" (fn [d] (if (pos? (alength (or (.-children d) #js []))) (- node-r 8) (+ node-r 4))))
            (.attr "text-anchor" (fn [d] (if (pos? (alength (or (.-children d) #js []))) "end" "start")))
            (.attr "fill" "#ccc")
            (.attr "font-size" "11px")
            (.text (fn [d] (-> d .-data .-id))))))))

(defn tree-view [data on-select]
  (let [ref (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when (and @ref data)
          (render! @ref data on-select)))

      :component-did-update
      (fn [this [_ old-data]]
        (let [[_ new-data] (r/argv this)]
          (when (and @ref (not= old-data new-data))
            (render! @ref new-data on-select))))

      :reagent-render
      (fn [_ _]
        [:div {:ref       #(reset! ref %)
               :style     {:overflow "auto" :flex 1}}])})))
