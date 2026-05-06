(ns prototype.ui.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [prototype.ui.api :as api]
            [prototype.ui.tree :as tree]
            [prototype.ui.graph :as graph]))

(defonce state (r/atom {:tab          :tree
                        :tree         nil
                        :graph        nil
                        :loading      false
                        :solving      false
                        :queens-n     4
                        :tree-sel     nil
                        :graph-sel    nil}))

;; ----------------------------------------------------------------------------
;; Data loading

(defn- reload-tree! []
  (swap! state assoc :loading true :tree-sel nil)
  (api/fetch-tree!
   (fn [data]
     (swap! state assoc :tree data :loading false))))

(defn- reload-graph! []
  (api/fetch-model-graph!
   (fn [data]
     (swap! state assoc :graph data))))

(defn- solve-queens! []
  (let [n (:queens-n @state)]
    (swap! state assoc :solving true :tree nil :graph nil)
    (api/run-queens!
     n
     (fn [_]
       (swap! state assoc :solving false)
       (reload-tree!)
       (reload-graph!)))))

;; ----------------------------------------------------------------------------
;; Toolbar

(defn- toolbar []
  (let [{:keys [loading solving queens-n tab tree]} @state
        node-count (when tree (count (tree-seq :children :children tree)))]
    [:div {:style {:display "flex" :align-items "center" :gap "12px"
                   :padding "8px 14px"
                   :background "#0a161a"
                   :border-bottom "1px solid rgba(29,162,126,0.2)"}}
     [:strong {:style {:color "#e0eded" :font-family "Inter, sans-serif"
                       :font-size "13px" :letter-spacing "0.02em"}}
      "MiniCPBP"]

     [:label {:style {:color "#6a9e9e" :display "flex" :align-items "center"
                      :gap "6px" :font-size "12px"}}
      "N-Queens"
      [:input {:type "number" :min 1 :max 20 :value queens-n
               :on-change #(swap! state assoc :queens-n
                                  (js/parseInt (.. % -target -value)))
               :style {:width "46px" :padding "2px 6px"
                       :background "#0c1c22"
                       :color "#e0eded"
                       :border "1px solid rgba(29,162,126,0.3)"
                       :border-radius "4px"
                       :font-size "12px"}}]]

     [:button {:on-click solve-queens!
               :disabled (or solving loading)
               :style {:padding "4px 12px" :cursor "pointer"
                       :background "rgba(29,162,126,0.15)"
                       :color "#1DA27E"
                       :border "1px solid rgba(29,162,126,0.4)"
                       :border-radius "5px"
                       :font-size "12px"}}
      (if solving "solving…" "Solve")]

     (when (= tab :tree)
       [:button {:on-click reload-tree!
                 :disabled (or loading solving)
                 :style {:padding "4px 12px" :cursor "pointer"
                         :background "transparent"
                         :color "#6a9e9e"
                         :border "1px solid rgba(255,255,255,0.1)"
                         :border-radius "5px"
                         :font-size "12px"}}
        (if loading "loading…" "reload tree")])

     (when (= tab :graph)
       [:button {:on-click reload-graph!
                 :disabled (or loading solving)
                 :style {:padding "4px 12px" :cursor "pointer"
                         :background "transparent"
                         :color "#6a9e9e"
                         :border "1px solid rgba(255,255,255,0.1)"
                         :border-radius "5px"
                         :font-size "12px"}}
        "reload graph"])

     (when (and (= tab :tree) node-count)
       [:span {:style {:color "#6a9e9e" :font-size "11px"}}
        (str node-count " nodes")])]))

;; ----------------------------------------------------------------------------
;; Tab bar

(defn- tab-bar []
  (let [{:keys [tab]} @state]
    [:div {:style {:display "flex"
                   :background "#0a161a"
                   :border-bottom "1px solid rgba(29,162,126,0.15)"
                   :padding "0 14px"}}
     (for [[id label] [[:tree "Search Tree"] [:graph "Constraint Graph"]]]
       ^{:key id}
       [:div {:on-click #(swap! state assoc :tab id)
              :style {:padding "7px 14px"
                      :font-size "12px"
                      :cursor "pointer"
                      :border-bottom (if (= tab id)
                                       "2px solid #1DA27E"
                                       "2px solid transparent")
                      :color (if (= tab id) "#e0eded" "#6a9e9e")
                      :margin-bottom "-1px"
                      :user-select "none"}}
        label])]))

;; ----------------------------------------------------------------------------
;; Search tree detail panel (unchanged)

(defn- tree-detail []
  (let [{:keys [tree-sel]} @state]
    [:div {:style {:width "200px" :min-width "200px"
                   :padding "12px 14px"
                   :background "#0e2028"
                   :border-left "1px solid rgba(29,162,126,0.18)"
                   :overflow-y "auto"
                   :font-size "12px"}}
     (if tree-sel
       [:<>
        [:div {:style {:color "#6a9e9e" :margin-bottom "6px"}}
         (str "node " (:id tree-sel))]
        [:div {:style {:margin-bottom "4px"}}
         [:span {:style {:color (get {"solution" "#22c55e"
                                      "failure"  "#e05252"
                                      "internal" "#06b6d4"}
                                     (:outcome tree-sel) "#06b6d4")
                         :font-weight 600}}
          (:outcome tree-sel)]]
        [:div {:style {:color "#6a9e9e" :margin-bottom "8px"}}
         (str "depth " (:depth tree-sel))]
        [:ul {:style {:list-style "none" :padding 0 :margin 0 :color "#e0eded"}}
         (for [ev (:events tree-sel)]
           ^{:key ev} [:li {:style {:padding "2px 0"}} ev])]]
       [:div {:style {:color "#6a9e9e"}} "click a node"])]))

;; ----------------------------------------------------------------------------
;; Root

(defn app []
  (let [{:keys [tab tree graph graph-sel]} @state]
    [:div {:style {:display "flex" :flex-direction "column" :height "100vh"
                   :background "#06090f" :color "#e0eded"
                   :font-family "Inter, system-ui, sans-serif"}}
     [toolbar]
     [tab-bar]
     [:div {:style {:display "flex" :flex 1 :overflow "hidden"}}
      (case tab
        :tree
        [:<>
         (if tree
           [tree/tree-view tree #(swap! state assoc :tree-sel %)]
           [:div {:style {:flex 1 :display "flex" :align-items "center"
                          :justify-content "center" :color "#6a9e9e" :font-size "13px"}}
            "no trace loaded — run a solver"])
         [tree-detail]]

        :graph
        [:<>
         (if graph
           [graph/graph-view graph #(swap! state assoc :graph-sel %)]
           [:div {:style {:flex 1 :display "flex" :align-items "center"
                          :justify-content "center" :color "#6a9e9e" :font-size "13px"
                          :background "#06090f"}}
            "no model loaded — run a solver"])
         [graph/detail-panel graph-sel
          (or (:nodes graph) [])
          (or (:edges graph) [])]])]]))

(defn init! []
  (rdom/render [app] (js/document.getElementById "app")))
