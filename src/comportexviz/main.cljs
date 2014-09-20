(ns comportexviz.main
  (:require [c2.dom :as dom :refer [->dom]]
            [org.nfrac.comportex.core :as core]
            [comportexviz.controls-ui :as cui]
            [comportexviz.viz-canvas :as viz]
            [comportexviz.plots :as plots]
            [goog.ui.TabPane]
            [goog.ui.TabPane.TabPage]
            [cljs.core.async :as async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(defn- tap-c
  [mult]
  (let [c (chan)]
    (async/tap mult c)
    c))

;; ## DATA

(def model (atom nil))

(def steps-c (chan))
(def steps-mult (async/mult steps-c))

(def freqs-c (async/map< (comp core/column-state-freqs first core/region-seq)
                         (tap-c steps-mult)))
(def freqs-mult (async/mult freqs-c))
(def agg-freqs-ts (plots/aggregated-ts-ref (tap-c freqs-mult) 200))

(def sim-go? (atom false))
(def main-options
  (atom {:sim-step-ms 500
         :anim-go? true
         :anim-every 1}))

(def selection (atom {:region 0 :dt 0 :cid nil}))

;; ## ENTRY POINTS

(defn sim-step!
  []
  (->>
   (swap! model core/feed-forward-step)
   (put! steps-c)))

(defn draw!
  []
  (dom/request-animation-frame
   #(viz/draw! @selection)))

;; ## HELPERS

(defn update-ts-plot
  [agg-ts]
  (plots/bind-ts-plot "#comportex-plots" agg-ts 400 180
                      [:active :active-predicted :predicted]
                      viz/state-colors))

(defn now [] (.getTime (js/Date.)))

(defn run-sim
  []
  (swap! model assoc
         :run-start {:time (now)
                     :timestep (:timestep (:region @model))})
  (go
   (while @sim-go?
     (let [tc (async/timeout (:sim-step-ms @main-options))]
       (sim-step!)
       (<! tc)))))

;; ## TRIGGERS

(add-watch sim-go? :run-sim
           (fn [_ _ _ v]
             (when v (run-sim))))

(add-watch agg-freqs-ts :ts-plot
           (fn [_ _ _ v]
             (update-ts-plot v)))

(add-watch viz/viz-options :redraw
           (fn [_ _ _ _]
             (draw!)))

(add-watch selection :redraw
           (fn [_ _ _ _]
             (draw!)))

;; animation loop
(go (loop [c (tap-c steps-mult)]
      (when-let [state (<! c)]
        (let [t (:timestep (:region state))
              n (:anim-every @main-options)]
          (when (and (:anim-go? @main-options)
                     (zero? (mod t n)))
            (draw!)))
        (recur c))))

(defn- init-ui!
  [init-model]
  (goog.ui.TabPane. (->dom "#comportex-tabs"))
  (viz/init! init-model (tap-c steps-mult) selection sim-step!)
  (cui/handle-controls! model sim-go? main-options sim-step! draw!)
  (cui/handle-options! model viz/keep-steps viz/viz-options)
  (cui/handle-parameters! model selection))

(defn ^:export set-model
  {:pre (nil? @model)} ; currently
  [x]
  (->> (reset! model x)
       (put! steps-c))
  (init-ui! x))
