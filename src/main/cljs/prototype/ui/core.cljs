(ns prototype.ui.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [prototype.ui.api :as api]
            [prototype.ui.tree :as tree]))

(defonce state (r/atom {:tree nil
                        :loading false
                        :solving false
                        :queens-n 4
                        :selected nil}))

(defn reload! []
  (swap! state assoc :loading true :selected nil)
  (api/fetch-tree!
   (fn [data]
     (swap! state assoc :tree data :loading false))))

;; ----------------------------------------------------------------------------
;; Detail panel

(defn- detail-panel []
  (let [{:keys [selected]} @state]
    [:div {:style {:width "220px" :min-width "220px" :padding "12px"
                   :border-left "1px solid #333" :overflow-y "auto"}}
     (if selected
       [:<>
        [:div {:style {:color "#aaa" :margin-bottom "8px"}} (str "node " (:id selected))]
        [:div {:style {:margin-bottom "4px"}}
         [:span {:style {:color (get {"solution" "#27ae60" "failure" "#c0392b" "internal" "#2980b9"}
                                     (:outcome selected) "#2980b9")}}
          (:outcome selected)]]
        [:div {:style {:color "#888" :margin-bottom "8px"}} (str "depth " (:depth selected))]
        [:ul {:style {:list-style "none" :color "#ccc"}}
         (for [ev (:events selected)]
           ^{:key ev} [:li {:style {:padding "2px 0" :font-size "11px"}} ev])]]
       [:div {:style {:color "#555"}} "click a node"])]))

;; ----------------------------------------------------------------------------
;; Toolbar

(defn- solve-queens! []
  (let [n (:queens-n @state)]
    (swap! state assoc :solving true)
    (api/run-queens! n
                     (fn [_]
                       (swap! state assoc :solving false)
                       (reload!)))))

(defn- toolbar []
  (let [{:keys [tree loading solving queens-n]} @state
        n (when tree (count (tree-seq :children :children tree)))]
    [:div {:style {:display "flex" :align-items "center" :gap "12px"
                   :padding "8px 12px" :border-bottom "1px solid #333"
                   :background "#111"}}
     [:strong {:style {:color "#e0e0e0"}} "MiniCPBP Trace"]
     [:label {:style {:color "#aaa" :display "flex" :align-items "center" :gap "6px"}}
      "N-Queens"
      [:input {:type "number" :min 1 :max 20 :value queens-n
               :on-change #(swap! state assoc :queens-n (js/parseInt (.. % -target -value)))
               :style {:width "48px" :padding "2px 4px" :background "#222"
                       :color "#e0e0e0" :border "1px solid #555" :border-radius "3px"}}]]
     [:button {:on-click solve-queens!
               :disabled (or solving loading)
               :style {:padding "3px 10px" :cursor "pointer"
                       :background "#1a4a1a" :color "#e0e0e0"
                       :border "1px solid #3a7a3a" :border-radius "3px"}}
      (if solving "solving…" "Solve")]
     [:button {:on-click reload!
               :disabled (or loading solving)
               :style {:padding "3px 10px" :cursor "pointer"
                       :background "#333" :color "#e0e0e0"
                       :border "1px solid #555" :border-radius "3px"}}
      (if loading "loading…" "reload")]
     (when n [:span {:style {:color "#888"}} (str n " nodes")])]))

;; ----------------------------------------------------------------------------
;; Root

(defn app []
  (let [{:keys [tree]} @state]
    [:div {:style {:display "flex" :flex-direction "column" :height "100vh"}}
     [toolbar]
     [:div {:style {:display "flex" :flex 1 :overflow "hidden"}}
      (if tree
        [tree/tree-view tree (fn [node] (swap! state assoc :selected node))]
        [:div {:style {:flex 1 :display "flex" :align-items "center"
                       :justify-content "center" :color "#555"}}
         "no trace loaded — run a solver and click reload"])
      [detail-panel]]]))

(defn init! []
  (rdom/render [app] (js/document.getElementById "app")))
