(ns prototype.ui.graph
  "2D constraint-graph visualisation using D3 force simulation.
   Adopts the dark teal theme from codebase-memory-mcp."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            ["d3" :as d3]))

;; ----------------------------------------------------------------------------
;; Theme – mirrors codebase-memory-mcp colours

(def ^:private bg           "#06090f")
(def ^:private edge-col     "#1DA27E")
(def ^:private var-col      "#06b6d4")   ; cyan  – unbound variable
(def ^:private var-bound    "#22c55e")   ; green – bound variable
(def ^:private cst-col      "#f97316")   ; orange – active constraint
(def ^:private cst-inactive "#64748b")   ; gray  – inactive constraint
(def ^:private text-col     "#e0eded")
(def ^:private muted-col    "#6a9e9e")

(defn- node-fill [{:keys [type bound active]}]
  (cond
    (= type "variable") (if bound var-bound var-col)
    (false? active)     cst-inactive
    :else               cst-col))

(defn- node-r [{:keys [type domain_size arity]}]
  (cond
    (= type "variable") (max 6  (min 22 (* (or domain_size 1) 2.2)))
    :else               (max 8  (min 26 (* (or arity 1) 3.2)))))

(defn- short-label [{:keys [name type]}]
  (if (= type "constraint")
    ;; strip common package prefixes for readability
    (let [simple (str/replace name #"^.*\." "")]
      (subs simple 0 (min 12 (count simple))))
    name))

;; ----------------------------------------------------------------------------
;; D3 render

(defn- render! [container {:keys [nodes edges]} on-select !sim]
  (when-let [old @!sim] (.stop old))
  (-> (d3/select container) (.selectAll "*") .remove)

  (let [w     (.-clientWidth  container)
        h     (.-clientHeight container)

        d3n   (clj->js (mapv (fn [nd]
                               (assoc nd
                                      :_r     (node-r nd)
                                      :_fill  (node-fill nd)
                                      :_label (short-label nd)))
                             nodes))
        ;; d3 link format: integer indices into d3n
        idx   (into {} (map-indexed (fn [i nd] [(:id nd) i]) nodes))
        d3e   (clj->js (mapv (fn [{:keys [source target]}]
                               {:source (idx source) :target (idx target)})
                             edges))

        svg   (-> (d3/select container)
                  (.append "svg")
                  (.attr "width"  "100%")
                  (.attr "height" "100%")
                  (.style "background" bg)
                  (.style "display" "block"))

        ;; overall zoom/pan layer
        g     (.append svg "g")

        zoom  (-> (d3/zoom)
                  (.scaleExtent #js [0.05 8])
                  (.on "zoom" (fn [ev] (.attr g "transform" (.-transform ev)))))
        _     (.call svg zoom)

        ;; ── edge layer ──────────────────────────────────────────────────────
        links (-> g
                  (.append "g")
                  (.attr "stroke" edge-col)
                  (.attr "stroke-opacity" "0.35")
                  (.attr "stroke-width"   "1.5")
                  (.selectAll "line")
                  (.data d3e)
                  .enter
                  (.append "line"))

        ;; ── node layer ──────────────────────────────────────────────────────
        node-sel (-> g
                     (.append "g")
                     (.selectAll "g")
                     (.data d3n)
                     .enter
                     (.append "g")
                     (.style "cursor" "pointer")
                     (.on "click" (fn [ev d]
                                    (.stopPropagation ev)
                                    (on-select (js->clj d :keywordize-keys true)))))

        drag-fn  (-> (d3/drag)
                     (.on "start" (fn [ev d]
                                    (when (zero? (.-active ev))
                                      (.alphaTarget @!sim 0.3)
                                      (.restart @!sim))
                                    (set! (.-fx d) (.-x d))
                                    (set! (.-fy d) (.-y d))))
                     (.on "drag"  (fn [ev d]
                                    (set! (.-fx d) (.-x ev))
                                    (set! (.-fy d) (.-y ev))))
                     (.on "end"   (fn [ev d]
                                    (when (zero? (.-active ev))
                                      (.alphaTarget @!sim 0))
                                    (set! (.-fx d) nil)
                                    (set! (.-fy d) nil))))
        _        (.call node-sel drag-fn)

        ;; glow halo (same colour, low opacity, slightly larger)
        _ (-> node-sel
              (.append "circle")
              (.attr "r"            (fn [d] (* (.-_r d) 1.6)))
              (.attr "fill"         (fn [d] (.-_fill d)))
              (.attr "fill-opacity" "0.12")
              (.attr "stroke"       "none"))

        ;; solid fill circle
        _ (-> node-sel
              (.append "circle")
              (.attr "r"              (fn [d] (.-_r d)))
              (.attr "fill"           (fn [d] (.-_fill d)))
              (.attr "fill-opacity"   "0.82")
              (.attr "stroke"         (fn [d] (.-_fill d)))
              (.attr "stroke-width"   "1.5")
              (.attr "stroke-opacity" "0.55"))

        ;; text label
        _ (-> node-sel
              (.append "text")
              (.attr "text-anchor"   "middle")
              (.attr "dy"            "0.35em")
              (.attr "font-size"     "10px")
              (.attr "font-family"   "JetBrains Mono, ui-monospace, monospace")
              (.attr "fill"          text-col)
              (.attr "pointer-events" "none")
              (.text (fn [d] (.-_label d))))

        ;; ── force simulation ────────────────────────────────────────────────
        sim  (-> (d3/forceSimulation d3n)
                 (.force "link"   (-> (d3/forceLink d3e)
                                      (.distance 90)
                                      (.strength 0.55)))
                 (.force "charge" (-> (d3/forceManyBody)
                                      (.strength -350)))
                 (.force "center" (d3/forceCenter (/ w 2) (/ h 2)))
                 (.force "collide" (-> (d3/forceCollide)
                                       (.radius (fn [d] (+ (.-_r d) 6)))))
                 (.on "tick"
                      (fn []
                        (.attr links "x1" (fn [d] (-> d .-source .-x)))
                        (.attr links "y1" (fn [d] (-> d .-source .-y)))
                        (.attr links "x2" (fn [d] (-> d .-target .-x)))
                        (.attr links "y2" (fn [d] (-> d .-target .-y)))
                        (.attr node-sel "transform"
                               (fn [d] (str "translate(" (.-x d) "," (.-y d) ")"))))))

        ;; deselect on background click
        _ (.on svg "click" (fn [_] (on-select nil)))]

    (reset! !sim sim)))

;; ----------------------------------------------------------------------------
;; Reagent component

(defn graph-view [data on-select]
  (let [!sim (atom nil)
        ref  (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (when (and @ref data)
          (render! @ref data on-select !sim)))

      :component-did-update
      (fn [this [_ old-data]]
        (let [[_ new-data] (r/argv this)]
          (when (and @ref (not= old-data new-data))
            (render! @ref new-data on-select !sim))))

      :component-will-unmount
      (fn [_]
        (when-let [sim @!sim] (.stop sim)))

      :reagent-render
      (fn [_ _]
        [:div {:ref   #(reset! ref %)
               :style {:flex 1 :overflow "hidden" :background bg}}])})))

;; ----------------------------------------------------------------------------
;; Detail panel for a selected constraint-graph node

(defn- prop-row [label value & [{:keys [highlight]}]]
  [:div {:style {:display "flex" :justify-content "space-between"
                 :margin-bottom "5px"}}
   [:span {:style {:font-size "11px" :color muted-col}} label]
   [:span {:style {:font-size "12px" :font-family "JetBrains Mono, monospace"
                   :color (if highlight "#22c55e" text-col)}} value]])

(defn detail-panel [selected all-nodes all-edges]
  [:div {:style {:width "250px" :min-width "250px"
                 :background "#0e2028"
                 :border-left "1px solid rgba(29,162,126,0.18)"
                 :display "flex" :flex-direction "column"
                 :overflow "hidden"}}
   (if-not selected
     [:div {:style {:flex 1 :display "flex" :align-items "center"
                    :justify-content "center"
                    :color muted-col :font-size "12px"}}
      "click a node"]
     (let [{:keys [type name bound domain_min domain_max domain_size arity active id]}
           selected
           is-var  (= type "variable")
           node-map (into {} (map (fn [n] [(:id n) n]) all-nodes))
           neighbours (->> all-edges
                           (filter #(or (= (:source %) id) (= (:target %) id)))
                           (map #(node-map (if (= (:source %) id) (:target %) (:source %))))
                           (remove nil?))]
       [:<>
        ;; header
        [:div {:style {:padding "14px 16px 12px"
                       :border-bottom "1px solid rgba(29,162,126,0.12)"
                       :display "flex" :align-items "flex-start" :gap "8px"}}
         [:div {:style {:width "11px" :height "11px" :border-radius "50%"
                        :background (node-fill selected) :margin-top "3px" :flex-shrink 0}}]
         [:div {:style {:flex 1}}
          [:div {:style {:font-size "13px" :font-weight 600 :color text-col
                         :word-break "break-all"
                         :font-family "JetBrains Mono, monospace"}} name]
          [:div {:style {:font-size "11px" :color muted-col :margin-top "2px"}} type]]]
        ;; properties
        [:div {:style {:padding "12px 16px"
                       :border-bottom "1px solid rgba(29,162,126,0.08)"}}
         (if is-var
           [:<>
            [prop-row "Domain" (if bound (str domain_min)
                                   (str "[" domain_min ".." domain_max "]"))]
            [prop-row "Size"   (str domain_size)]
            [prop-row "Bound"  (if bound "yes" "no") {:highlight bound}]]
           [:<>
            [prop-row "Arity"  (str arity)]
            [prop-row "Active" (if (false? active) "no" "yes")
             {:highlight (not (false? active))}]])]
        ;; neighbours
        [:div {:style {:flex 1 :overflow-y "auto" :padding "8px 0"}}
         [:div {:style {:padding "0 16px 6px" :font-size "11px" :color muted-col
                        :text-transform "uppercase" :letter-spacing "0.06em"}}
          (str (if is-var "Constraints" "Variables") " · " (count neighbours))]
         (for [nb neighbours]
           ^{:key (:id nb)}
           [:div {:style {:padding "5px 16px" :display "flex" :align-items "center"
                          :gap "8px" :cursor "default"}}
            [:div {:style {:width "8px" :height "8px" :border-radius "50%"
                           :background (node-fill nb) :flex-shrink 0}}]
            [:span {:style {:font-size "12px" :color text-col
                            :font-family "JetBrains Mono, monospace"
                            :overflow "hidden" :text-overflow "ellipsis"
                            :white-space "nowrap"}}
             (:name nb)]])]]))])
