(ns prototype.ui.tree
  (:require [reagent.core :as r]
            ["d3" :as d3]))

;; ----------------------------------------------------------------------------
;; Theme – mirrors prototype.ui.graph

(def ^:private bg       "#06090f")
(def ^:private edge-col "#1DA27E")
(def ^:private text-col "#e0eded")

(def ^:private outcome-color
  {"solution" "#22c55e"   ; green
   "failure"  "#e05252"   ; red
   "internal" "#06b6d4"}) ; cyan

(def ^:private node-r 6)

(defn- fill-of [d]
  (get outcome-color (-> d .-data .-outcome) "#06b6d4"))

;; ----------------------------------------------------------------------------
;; Layout sizing — keep tree's natural size; zoom/pan handles the viewport

(defn- tree-width  [root] (* 220 (inc (.-height root))))
(defn- tree-height [root] (max 400 (* 26 (count (.descendants root)))))

(defn- render! [container data on-select]
  (let [margin  {:top 24 :right 140 :bottom 24 :left 60}
        root    (-> (d3/hierarchy (clj->js data))
                    (.sort (fn [a b] (- (-> a .-data .-id) (-> b .-data .-id)))))
        w       (tree-width  root)
        h       (tree-height root)
        tree-fn (-> (d3/tree) (.size #js [h w]))
        _       (tree-fn root)]

    (-> (d3/select container) (.selectAll "*") .remove)

    (let [svg  (-> (d3/select container)
                   (.append "svg")
                   (.attr "width"  "100%")
                   (.attr "height" "100%")
                   (.style "background" bg)
                   (.style "display" "block"))

          ;; pan/zoom layer; the inner translate matches the margin
          base-tx (str "translate(" (:left margin) "," (:top margin) ")")
          g    (-> svg
                   (.append "g")
                   (.attr "transform" base-tx))

          zoom (-> (d3/zoom)
                   (.scaleExtent #js [0.1 8])
                   (.on "zoom"
                        (fn [ev]
                          (.attr g "transform"
                                 (str base-tx " " (.-transform ev))))))
          _    (.call svg zoom)

          ;; deselect on background click
          _    (.on svg "click" (fn [_] (on-select nil)))

          ;; ── links ───────────────────────────────────────────────────────
          _    (-> g
                   (.append "g")
                   (.attr "fill" "none")
                   (.attr "stroke" edge-col)
                   (.attr "stroke-opacity" "0.35")
                   (.attr "stroke-width" "1.5")
                   (.selectAll "path")
                   (.data (.links root))
                   .enter
                   (.append "path")
                   (.attr "d" (-> (d3/linkHorizontal)
                                  (.x (fn [d] (.-y d)))
                                  (.y (fn [d] (.-x d))))))

          ;; ── nodes ───────────────────────────────────────────────────────
          node (-> g
                   (.append "g")
                   (.selectAll "g")
                   (.data (.descendants root))
                   .enter
                   (.append "g")
                   (.attr "transform"
                          (fn [d] (str "translate(" (.-y d) "," (.-x d) ")")))
                   (.style "cursor" "pointer")
                   (.on "click"
                        (fn [ev d]
                          (.stopPropagation ev)
                          (on-select (js->clj (.-data d) :keywordize-keys true)))))

          ;; glow halo (low-opacity, larger radius)
          _ (-> node
                (.append "circle")
                (.attr "r"            (* node-r 1.6))
                (.attr "fill"         fill-of)
                (.attr "fill-opacity" "0.12")
                (.attr "stroke"       "none"))

          ;; solid fill
          _ (-> node
                (.append "circle")
                (.attr "r"              node-r)
                (.attr "fill"           fill-of)
                (.attr "fill-opacity"   "0.82")
                (.attr "stroke"         fill-of)
                (.attr "stroke-width"   "1.5")
                (.attr "stroke-opacity" "0.55"))

          ;; label
          _ (-> node
                (.append "text")
                (.attr "dy" "0.31em")
                (.attr "x" (fn [d]
                             (if (pos? (alength (or (.-children d) #js [])))
                               (- (- node-r) 6)
                               (+ node-r 6))))
                (.attr "text-anchor" (fn [d]
                                       (if (pos? (alength (or (.-children d) #js [])))
                                         "end" "start")))
                (.attr "fill" text-col)
                (.attr "font-size" "10px")
                (.attr "font-family" "JetBrains Mono, ui-monospace, monospace")
                (.attr "pointer-events" "none")
                (.text (fn [d] (-> d .-data .-id))))])))

(defn tree-view [data on-select]
  (let [ref (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (when (and @ref data)
          (render! @ref data on-select)))

      :component-did-update
      (fn [this [_ old-data]]
        (let [[_ new-data] (r/argv this)]
          (when (and @ref (not= old-data new-data))
            (render! @ref new-data on-select))))

      :reagent-render
      (fn [_ _]
        [:div {:ref   #(reset! ref %)
               :style {:flex 1 :overflow "hidden" :background bg}}])})))
