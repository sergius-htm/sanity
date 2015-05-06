(ns comportexviz.demos.sensorimotor-1d
  (:require [org.nfrac.comportex.demos.sensorimotor-1d :as demo]
            [org.nfrac.comportex.core :as core]
            [comportexviz.main :as main]
            [comportexviz.viz-canvas :as viz]
            [comportexviz.plots-canvas :as plt]
            [monet.canvas :as c]
            [reagent.core :as reagent :refer [atom]]
            [reagent-forms.core :refer [bind-fields]]
            [goog.dom :as dom]
            [goog.dom.forms :as forms]
            [cljs.core.async :as async])
  (:require-macros [comportexviz.macros :refer [with-ui-loading-message]]))

(def config
  (atom {:n-regions 1
         :field :abcdefghij
         :n-steps 100
         :world-buffer-count 0}))

(def world-buffer (async/buffer 5000))
(def world-c (async/chan world-buffer))

(add-watch main/model ::count-world-buffer
           (fn [_ _ _ _]
             (swap! config assoc :world-buffer-count (count world-buffer))))

(def item-colors
  (zipmap demo/items
          (for [i (range 10)
                :let [hue (* i 36)
                      lig (if (even? i) 70 30)]]
            (str "hsl(" hue ",100%," lig "%)"))))

(def item-text-colors
  (zipmap demo/items
          (for [i (range 10)]
            (if (even? i) "black" "white"))))

(defn draw-eye
  [ctx {:keys [x y angle radius]}]
  (c/save ctx)
  (let [pi2 (/ Math/PI 2)]
    (c/begin-path ctx)
    (c/arc ctx {:x x :y y :r radius
                :start-angle (- pi2) :end-angle pi2
                :counter-clockwise? true})
    (c/close-path ctx)
    (c/fill-style ctx "white")
    (c/fill ctx)
    (c/stroke-style ctx "black")
    (c/stroke ctx)
    (c/clip ctx)
    (let [pupil-x (+ x (* radius (Math/cos angle)))
          pupil-y (+ y (* radius (Math/sin angle)))]
      (c/circle ctx {:x pupil-x :y pupil-y
                     :r (quot radius 2)})
      (c/fill-style ctx "rgb(128,128,255)")
      (c/fill ctx)
      (c/circle ctx {:x pupil-x :y pupil-y
                     :r (quot radius 5)})
      (c/fill-style ctx "black")
      (c/fill ctx)))
  (c/restore ctx))

(defn draw-world
  [ctx in-value]
  (let [{:keys [field position next-saccade]} in-value
        item-w 20
        x-lim [0 1]
        y-lim [0 (count field)]
        width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        plot-size {:w width-px
                   :h (* (count field) item-w)}
        plot (plt/xy-plot ctx plot-size x-lim y-lim)
        x-scale (plt/scale-fn x-lim (:w plot-size))
        y-scale (plt/scale-fn y-lim (:h plot-size))]
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (plt/frame! plot)
    (c/stroke-style ctx "black")
    (c/font-style ctx "bold 14px monospace")
    (c/text-baseline ctx :middle)
    (doseq [[y item] (map-indexed vector field)
            :let [rect {:x 0 :y (y-scale y)
                        :w item-w :h (y-scale 1)}]]
      (doto ctx
        (c/fill-style (item-colors item))
        (c/fill-rect rect)
        (c/stroke-rect rect)
        (c/fill-style (item-text-colors item))
        (c/text {:x 5 :y (y-scale (+ y 0.5)) :text (name item)})))
    (let [focus-x 10
          focus-y (y-scale (+ 0.5 position))
          next-focus-y (y-scale (+ 0.5 position next-saccade))
          eye-x (:w plot-size)
          eye-y (quot (:h plot-size) 2)]
      (doto ctx
        ;; draw line of next position (after next saccade)
        (c/begin-path)
        (c/move-to eye-x eye-y)
        (c/line-to focus-x next-focus-y)
        (c/stroke-style "lightgrey")
        (c/stroke)
        ;; draw line of current position
        (c/begin-path)
        (c/move-to eye-x eye-y)
        (c/line-to focus-x focus-y)
        (c/stroke-style "black")
        (c/stroke)
        ;; draw eye
        (draw-eye {:x eye-x
                   :y eye-y
                   :angle (Math/atan2 (- focus-y eye-y)
                                      (- focus-x eye-x))
                   :radius 30})))))

;; fluid canvas - resizes drawing area as element stretches

(def trigger-redraw (atom 0))

(defn on-resize
  [_]
  (when-let [el (dom/getElement "comportex-world")]
    (viz/set-canvas-pixels-from-element-size! el 160)
    (swap! trigger-redraw inc)))

(defn world-pane
  []
  (when-let [htm (viz/selected-model-step)]
    (let [in-value (:value (first (core/input-seq htm)))
          canvas (dom/getElement "comportex-world")]
      (when canvas
        (when (zero? @trigger-redraw)
          (on-resize nil))
        (let [ctx (c/get-context canvas "2d")]
          (draw-world ctx in-value)))
      (let [{:keys [field position next-saccade]} in-value]
        [:div
         [:p.muted [:small "Input on selected timestep."]]
         [:table.table
          [:tr [:th "val"]
           [:td (str (get field position))]]
          [:tr [:th "next"]
           [:td (if (neg? next-saccade) "" "+") next-saccade]]]
         [:canvas#comportex-world {:style {:width "100%"
                                           :height "300px"}}]]))))

(defn send-input-stream!
  []
  (let [field-key (:field @config)
        n-steps (:n-steps @config)
        field (demo/fields field-key)]
    (async/onto-chan world-c
                     (take n-steps (iterate demo/input-transform
                                            {:field field
                                             :position (quot (count field) 2)
                                             :next-saccade 1}))
                     false)
    (swap! config assoc :world-buffer-count (count world-buffer))))

(defn set-model!
  []
  (let [n-regions (:n-regions @config)]
    (with-ui-loading-message
      (main/set-model! (demo/n-region-model n-regions)))))

(def config-template
  [:div
   [:h3 "Input " [:small "Sensorimotor sequences"]]
   [:p.text-info {:field :label
                  :id :world-buffer-count
                  :postamble " queued input values."}]
   [:div.form-horizontal
    [:div.form-group
     [:label.col-sm-5 "Field of values (a world):"]
     [:div.col-sm-7
      [:select.form-control {:field :list
                             :id :field}
       (for [k (keys demo/fields)]
         ^{:key k} [:option {:key k} (name k)])]]]
    [:div.form-group
     [:label.col-sm-5 "Number of steps:"]
     [:div.col-sm-7
      [:input.form-control {:field :numeric
                            :id :n-steps}]]]
    [:div.form-group
     [:div.col-sm-offset-5.col-sm-7
      [:button.btn.btn-primary
       {:on-click (fn [e]
                    (send-input-stream!)
                    (.preventDefault e))}
       "Send input stream"]]]
    ]
   [:h3 "HTM model"]
   [:div.form-horizontal
    [:div.form-group
     [:label.col-sm-5 "Number of regions:"]
     [:div.col-sm-7
      [:input.form-control {:field :numeric
                            :id :n-regions}]]]
    [:div.form-group
     [:div.col-sm-offset-5.col-sm-7
      [:button.btn.btn-default
       {:on-click (fn [e]
                    (set-model!)
                    (.preventDefault e))}
       "Restart with new model"]
      [:p.text-danger "This resets all parameters."]]]
    ]])

(defn model-tab
  []
  [:div
   [:p "A simple example of sensorimotor input in 1D."]
   [bind-fields config-template config]
   ]
  )

(defn ^:export init
  []
  (reagent/render (main/comportexviz-app model-tab world-pane)
                  (dom/getElement "comportexviz-app"))
  (.addEventListener js/window "resize" on-resize)
  (reset! main/world world-c)
  (set-model!)
  (swap! main/main-options assoc :sim-go? true))
