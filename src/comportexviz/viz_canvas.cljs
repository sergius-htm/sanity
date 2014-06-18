(ns comportexviz.viz-canvas
  (:require [c2.dom :as dom :refer [->dom]]
            [c2.event]
            [goog.events.EventType]
            [goog.events :as gevents]
            [goog.string :as gstring]
            [goog.string.format]
            [clojure.core.rrb-vector :as fv]
            [monet.canvas :as c]
            [monet.core]
            [org.nfrac.comportex.sequence-memory :as sm]
            [clojure.set :as set]
            [comportexviz.controls-ui :refer [display-options
                                              simulation-gate
                                              animation-gate]]
            [comportexviz.plots :as plots]
            [cljs.core.async :refer [chan put! <! timeout]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def steps (atom []))
;; keep how many recent time steps
(def keep-steps (atom 25))

(defn i->dt
  [i]
  (- (count @steps) i 1))

(def dt->i i->dt)

(defn wrap
  [x]
  (let [pcr (delay (sm/predictive-cells (:region x)))]
    (assoc-in x [:region :predictive-cells-ref] pcr)))

(defn ^:export sim-loop
  [step-c]
  (go (loop []
        (when (<! simulation-gate)
          (when-let [x (<! step-c)]
            (swap! steps conj (wrap x))
            (recur))))))

(declare animation-step!)

(defn animation-loop
  []
  (go (loop []
        (when (<! animation-gate)
          (monet.core/animation-frame animation-step!)
          (recur)))))

(defn- take-last-v
  [n v]
  (fv/subvec v (max 0 (- (count v) n))))

(add-watch steps :keep-steps
           (fn [_ _ _ v]
             (when (> (count v) @keep-steps)
               (swap! steps #(take-last-v @keep-steps %)))))

;; stats
(def !raw-freqs (atom []))
(def !agg-freqs (plots/aggregated-ts-ref !raw-freqs :agg 200))

(defn update-stats!
  [state prev-state]
  (when prev-state
    (let [rgn (:region state)
          prev-rgn (:region prev-state)
          pm (zipmap (keys (deref (:predictive-cells-ref prev-rgn)))
                     (repeat :predicted))
          am (zipmap (:active-columns rgn) (repeat :active))
          bm (zipmap (:bursting-columns rgn) (repeat :unpredicted))
          m (merge pm am bm)
          x (-> (frequencies (vals m))
                (assoc :timestep (:timestep rgn)
                       :ncol (count (:columns rgn))))]
      (swap! !raw-freqs conj x))))

(def selected-cid (atom nil))
(def selected-dt (atom 0))



;; ## Graphic Display

(enable-console-print!)

(def width-px 800)
(def height-px 1600)
(def bit-w-px 5)
(def bit-h-px 2)
(def col-grid-px 5)
(def col-r-px (* col-grid-px 0.5))
(def seg-r-px 15)
(def head-px 10)
(def h-spacing-px 80)

(def canvas-dom (->dom "#viz"))
;; need to set canvas size in js not CSS, the latter delayed so
;; get-context would see the wrong resolution here.
(set! (.-width canvas-dom) width-px)
(set! (.-height canvas-dom) height-px)

(def canvas-ctx (c/get-context canvas-dom "2d"))

(defn hsl
  ([h s l] (hsl h s l 1.0))
  ([h s l a]
   (let [h2 (if (keyword? h)
              (case h
                :red 0
                :orange 30
                :yellow 60
                :yellow-green 90
                :green 120
                :blue 210
                :purple 270
                :pink 300)
              ;; otherwise angle
              h)]
     (str "hsla(" h2 ","
          (long (* s 100)) "%,"
          (long (* l 100)) "%,"
          a ")"))))

(defn grey
  [z]
  (let [v (long (* z 255))]
    (str "rgb(" v "," v "," v ")")))

(def state-colors
  {:inactive "white"
   :active (hsl :blue 1.0 0.5) ;; can be active+predicted
   :predicted (hsl :green 1.0 0.4) ;; inactive+predicted
   :unpredicted (hsl :red 1.0 0.5)  ;; active+unpredicted
   })

(defn rgn-px-offset
  []
  (+ (* @keep-steps bit-w-px)
     h-spacing-px))

(defn segs-px-offset
  []
  (+ (rgn-px-offset)
     (* @keep-steps col-grid-px)
     h-spacing-px))

(defn column->px
  "Returns pixel coordinates on the canvas `[x-px y-px]` for the
   center of a column `cid` at time delay `dt`."
  [cid dt]
  (let [left (rgn-px-offset)
        width (* @keep-steps col-grid-px)
        right (+ left width)
        off-x-px (* (+ dt 0.5) col-grid-px)
        x-px (- right off-x-px)
        off-y-px (* (+ cid 0.5) col-grid-px)
        y-px (+ head-px off-y-px)]
    [x-px y-px]))

(defn px->column
  "Returns column id and time delay `[cid dt]` located by given pixel
   coordinates on the canvas. Otherwise nil."
  [x-px y-px]
  (let [left (rgn-px-offset)
        width (* @keep-steps col-grid-px)
        right (+ left width)
        cid (Math/floor (/ (- y-px head-px) col-grid-px))
        dt (Math/floor (/ (- right x-px) col-grid-px))]
    (when (and (<= 0 dt (count @steps))
               (<= 0 cid))
      [cid dt])))

(defn inbit->px
  "Returns pixel coordinates on the canvas `[x-px y-px]` for the
   center of an input bit `id` at time delay `dt`."
  [id dt]
  (let [width (* @keep-steps bit-w-px)
        right width
        off-x-px (* (+ dt 0.5) bit-w-px)
        x-px (- right off-x-px)
        off-y-px (* (+ id 0.5) bit-h-px)
        y-px (+ head-px off-y-px)]
    [x-px y-px]))

(defn px->inbit
  "Returns input bit id and time delay `[id dt]` located by given
   pixel coordinates on the canvas. Otherwise nil."
  [x-px y-px]
  (let [width (* @keep-steps bit-w-px)
        right width
        id (Math/floor (/ (- y-px head-px) bit-h-px))
        dt (Math/floor (/ (- right x-px) bit-w-px))]
    (when (and (<= 0 dt (count @steps))
               (<= 0 id))
      [id dt])))

(defn centred-rect
  [cx cy w h]
  {:x (- cx (quot w 2))
   :y (- cy (quot h 2))
   :w w
   :h h})

(defn highlight-rect
  [ctx rect]
  (doto ctx
    (c/stroke-style (hsl :yellow 1 0.75 0.5))
    (c/stroke-width 3)
    (c/stroke-rect rect)
    (c/stroke-style "black")
    (c/stroke-width 1)
    (c/stroke-rect rect)))

(defn draw-inbits
  [ctx data bit-width sel-dt]
  (c/save ctx)
  (c/stroke-width ctx (* bit-h-px 0.15))
  (c/stroke-style ctx (grey 0.75))
  (doseq [dt (range (count data))
          :let [bits (data (dt->i dt))]]
    (doseq [b (range bit-width)
            :let [[x-px y-px] (inbit->px b dt)
                  color (if-let [bit-state (bits b)]
                          (state-colors bit-state)
                          "white")]]
      (c/fill-style ctx color)
      (c/fill-rect ctx (centred-rect x-px y-px bit-w-px bit-h-px))
      (c/stroke-rect ctx (centred-rect x-px y-px bit-w-px bit-h-px))))
  ;; draw axis on selection: vertical dt
  (let [[x y1] (inbit->px 0 sel-dt)
        [_ y2] (inbit->px (dec bit-width) sel-dt)
        y (/ (+ y1 y2) 2)
        w (+ 1 bit-w-px)
        h (+ 10 bit-h-px (- y2 y1))]
    (highlight-rect ctx (centred-rect x y w h)))
  (c/restore ctx)
  ctx)

(defn draw-rgn
  [ctx data ncol sel-cid sel-dt]
  ;; Originally this used translate and scale to draw grid on integer
  ;; coordinates. But better to use px lookup functions so that we can
  ;; draw between frames of reference: inbits & columns.
  (c/save ctx)
  (c/stroke-width ctx (* col-grid-px 0.15))
  (c/stroke-style ctx (grey 0.75))
  (doseq [dt (range (count data))
          :let [m (data (dt->i dt))]]
    (doseq [cid (range ncol)
            :let [[x-px y-px] (column->px cid dt)
                  cval (m cid :inactive)
                  color (or (state-colors cval)
                            (->> (/ cval 10)
                                 (min 1.0)
                                 (- 1)
                                 (grey)))]]
      (c/fill-style ctx color)
      (c/circle ctx {:x x-px :y y-px :r col-r-px})
      (c/stroke ctx)))
  ;; draw axis on selection: vertical dt and horizonal cid
  (let [[x y1] (column->px 0 sel-dt)
        [_ y2] (column->px (dec ncol) sel-dt)
        y (/ (+ y1 y2) 2)
        w (+ 1 col-grid-px)
        h (+ 10 col-grid-px (- y2 y1))]
    (highlight-rect ctx (centred-rect x y w h)))
  (when sel-cid
    (let [[x1 y] (column->px sel-cid 0)
          [x2 _] (column->px sel-cid (dec (count data)))
          x (/ (+ x1 x2) 2)
          w (+ 10 col-grid-px (Math/abs (- x2 x1)))
          h (+ 1 col-grid-px)]
      (highlight-rect ctx (centred-rect x y w h))))
  (c/restore ctx)
  ctx)

(defn draw-insynapses
  [ctx data]
  (c/save ctx)
  (c/stroke-width ctx 1)
  (doseq [[cid dt m] data
          :let [[cx cy] (column->px cid dt)
                perms? (:insyns-permanences @display-options)
                draw (fn [syns active?]
                       (c/stroke-style ctx (if active? "red" "black"))
                       (doseq [[id perm] syns
                               :let [[ix iy] (inbit->px id dt)]]
                         (doto ctx
                           (c/alpha (if perms? perm 1))
                           (c/begin-path)
                           (c/move-to (- cx 1) cy) ;; -1 avoid obscuring colour
                           (c/line-to ix iy)
                           (c/stroke)))
                       )]]
    (draw (:inactive m) false)
    (draw (:active m) true))
  (c/restore ctx)
  ctx)

(defn draw-matching-segments
  [ctx data]
  (c/save ctx)
  (c/stroke-width ctx 1)
  (doseq [[cid dt m] data
          :let [[cx cy] (column->px cid dt)
                perms? (:dendrite-permanences @display-options)
                draw (fn [syns active?]
                       (c/stroke-style ctx (if active? "red" "black"))
                       (doseq [[[id _] perm] syns
                               :let [[dcx dcy] (column->px id (inc dt))
                                     mid-y (quot (+ cy dcy) 2)
                                     diff-y (Math/abs (- cy dcy))]]
                         (doto ctx
                           (c/alpha (if perms? perm 1))
                           (c/begin-path)
                           (c/move-to (+ cx 1) cy) ;; avoid obscuring colour
                           (c/line-to (+ cx 10) cy)
                           (c/quadratic-curve-to (+ cx (quot diff-y 2))
                                                 mid-y
                                                 dcx
                                                 dcy)
                           (c/stroke)))
                       )]]
    (draw (:inactive m) false)
    (draw (:active m) true))
  (c/restore ctx)
  ctx)

(defn draw-all-segments
  [ctx data cid dt ncol th]
  (c/save ctx)
  (let [perms? false ;; TODO
        ncells (count data)
        ns-bycell (mapv count data)
        ns-bycell-pad (map inc ns-bycell)
        n-pad (apply + 1 ns-bycell-pad)
        our-left (segs-px-offset)
        our-height (* 0.9 (.-innerHeight js/window))
        seg->px (fn [cell-idx idx]
                  (let [i-all (apply + 1 idx (take cell-idx ns-bycell-pad))
                        frac (/ i-all n-pad)]
                    [(+ our-left seg-r-px)
                     (+ head-px (* frac our-height))]))]
    ;; draw diagram line from selected cell to the segments
    (let [[cidx cidy] (column->px cid 0)]
      (c/stroke-style ctx "white")
      (c/stroke-width ctx col-grid-px)
      (c/move-to ctx (+ cidx col-r-px 1) cidy) ;; avoid obscuring colour
      (c/line-to ctx our-left cidy)
      (c/line-to ctx our-left head-px)
      (c/line-to ctx our-left (+ head-px our-height))
      (c/stroke ctx))
    (doseq [[ci segs] (map-indexed vector data)
            [si m] (map-indexed vector segs)
            :let [[sx sy] (seg->px ci si)
                  conn-act (count (m [:connected :active]))
                  conn-tot (+ conn-act (count (m [:connected :inactive])))
                  disc-act (count (m [:disconnected :active]))
                  disc-tot (+ disc-act (count (m [:disconnected :inactive])))
                  z (-> (- 1 (/ conn-act th))
                        (min 1.0))]]
      ;; draw segment as a rectangle
      (c/alpha ctx 1.0)
      (c/stroke-style ctx "black")
      (c/stroke-width ctx 1)
      (c/fill-style ctx (hsl :red 1 z))
      (let [s (centred-rect sx sy (* 2 seg-r-px) seg-r-px)]
        (c/fill-rect ctx s)
        (c/stroke-rect ctx s))
      (c/fill-style ctx "black")
      (when (zero? si)
        (c/text ctx {:text (str "cell " ci " segments:")
                     :x sx :y (- sy seg-r-px 5)}))
      (c/text ctx {:text (str "#" si ", active / connected = " conn-act
                              " / " conn-tot)
                   :x (+ sx (* 2 seg-r-px)) :y (- sy 5)})
      (c/text ctx {:text (str "  active / disconnected = " disc-act
                              " / " disc-tot)
                   :x (+ sx (* 2 seg-r-px)) :y (+ sy 5)})
      ;; synapses
      (let [draw (fn [syns conn? active?]
                   (c/stroke-style ctx
                                   (cond
                                    (and conn? active?) "red"
                                    (and conn? (not active?)) "black"
                                    active? (hsl :red 0.5 0.5)
                                    (not active?) (grey 0.5)))
                   (doseq [[[to-cid _] perm] syns
                           :let [[cx cy] (column->px to-cid (inc dt))]]
                     (doto ctx
                       (c/alpha (if perms? perm 1))
                       (c/begin-path)
                       (c/move-to sx sy)
                       (c/line-to (+ cx 1) cy) ;; +1 avoid obscuring colour
                       (c/stroke))))]
        (draw (m [:disconnected :inactive]) false false)
        (draw (m [:disconnected :active]) false true)
        (draw (m [:connected :inactive]) true false)
        (draw (m [:connected :active]) true true))))
  (c/restore ctx)
  ctx)

(defn detail-text
  []
  (let [dt @selected-dt
        cid @selected-cid
        state (@steps (dt->i dt))
        in (:input state)
        bits (:inbits state)
        rgn (:region state)]
    (->>
     ["__Selection__"
      (str "* timestep " (:timestep rgn)
           " (delay " dt ")")
      (str "* column " (or cid "nil"))
      ""
      "__Input__"
      (str in " (" (count bits) " bits)")
      ""
      "__Active columns__"
      (str (sort (:active-columns rgn)))
      ""
      "__Active cells__"
      (str (sort (:active-cells rgn)))
      ""
      "__Learn cells__"
      (str (sort (:learn-cells rgn)))
      ""
      "__Predicted cells__"
      (str (sort (deref (:predictive-cells-ref rgn))))
      ""
      (if cid
        (let [dtp (inc dt)
              pstate (@steps (dt->i dtp))
              pcol (get-in pstate [:region :columns cid])
              prgn (:region pstate)
              pcells (:active-cells prgn)
              best (sm/best-matching-segment-and-cell pcol
                                                      (:learn-cells prgn)
                                                      (:spec rgn))
              col (get-in rgn [:columns cid])]
          ["__Active cells prev__"
           (str (sort (:active-cells prgn)))
           ""
           "__Learn cells prev__"
           (str (sort (:learn-cells prgn)))
           ""
           "__Selected column__"
           "__Best matching segment and cell_"
           (str best)
           "__Cells and their Dendrite segments__"
           (->> (:cells pcol)
                (map-indexed
                 (fn [i cell]
                   (let [ds (:segments cell)
                         ac (:active-cells prgn)
                         lc (:learn-cells prgn)]
                     [(str "CELL " i)
                      (str (count ds) " = "
                           (sort (map (comp count :synapses) ds)))
                      (for [i (range (count ds))
                            :let [syns (:synapses (ds i))]]
                        [(str "  SEGMENT " i)
                         (for [[id p] (sort syns)]
                           (str "  " id " :=> "
                                (gstring/format "%.2f" p)
                                (if (lc id) " L"
                                    (if (ac id) " A"))))])
                      ]))))
           ]))]
     (flatten)
     (interpose \newline)
     (apply str))))

(defn update-text-display
  []
  (dom/val "#detail-text" (detail-text)))

(defn update-timestep
  [state]
  (let [curr-t (:timestep (:region state))]
    (dom/text "#sim-timestep" curr-t)))

(defn rgn-column-states
  [rgn prev-rgn]
  (let [opts @display-options
        om (:overlaps rgn)
        pm (delay (when prev-rgn
                    (zipmap (keys (deref (:predictive-cells-ref prev-rgn)))
                            (repeat :predicted))))
        am (zipmap (:active-columns rgn) (repeat :active))
        bm (zipmap (:bursting-columns rgn) (repeat :unpredicted))]
    (cond-> (if (:overlap-columns opts) om {})
            (:predictive-columns opts) (merge @pm)
            (:active-columns opts) (merge am)
            (:bursting-columns opts) (merge bm))))

(defn inbits-display-data
  [state prev-state]
  (let [opts @display-options
        inbits (:inbits state)
        data (zipmap inbits (repeat :active))]
    (if (and (:predicted-bits opts)
             prev-state)
      (let [rgn (:region prev-state)
            pc @(:predictive-cells-ref rgn)
            all-pred-bits (sm/predicted-bits rgn pc 3)
            unpred-bits (set/difference inbits all-pred-bits)]
        (merge (zipmap all-pred-bits (repeat :predicted))
               data
               (zipmap unpred-bits (repeat :unpredicted))))
      ;; else just show active bits
      data)))

(defn in-synapse-display-data
  [cid dt]
  (let [opts @display-options]
    (when (:active-insyns opts)
      (let [x (@steps (dt->i dt))
            col (get-in x [:region :columns cid])
            on-bits (:inbits x)
            syns (-> col :in-synapses :connected)
            m {:active (select-keys syns on-bits)
               :inactive (when (:inactive-insyns opts)
                           (apply dissoc syns on-bits))}]
        [cid dt m]))))

(defn matching-segments-display-data
  [cid dt]
  (let [opts @display-options]
    (when (:active-dendrites opts)
      ;; logically need to use prev region, since it is prior to
      ;; learning that occurred at time dt.
      ;; also need its current active cells that are the
      ;; PREVIOUSLY active cells at dt -- i.e. that define the
      ;; predictive states for dt.
      (let [dtp (inc dt)
            pstate (@steps (dt->i dtp))
            pcol (get-in pstate [:region :columns cid])
            prgn (:region pstate)
            pac (:active-cells prgn)
            best (sm/best-matching-segment-and-cell pcol
                                                    (:learn-cells prgn)
                                                    (:spec prgn))
            pcon (-> prgn :spec :connected-perm)
            m (if-let [sid (:segment-idx best)]
                (let [[_ cell-idx] (:cell-id best)
                      allsyns (get-in pcol [:cells cell-idx :segments sid :synapses])
                      syns (into {} (filter (fn [[id p]] (>= p pcon)) allsyns))]
                  {:active (select-keys syns pac)
                   :inactive (when (:dendrites-inactive opts)
                               (apply dissoc syns pac))})
                {})]
        [cid dt m]))))

(defn all-segments-display-data
  "Returns a data structure with all synapses in dendrite segments in
   cells in the column. Like:

   `[ [ { [:connected :active] [synapses ...],
          [:connected :inactive] [synapses ...],
          [:disconnected :active] [synapses ...],
          [:disconnected :inactive] [synapses ...] }
        ...] ;; more segments
      ...] ;; more cells`"
  [cid dt]
  (let [dtp (inc dt)
        x (@steps (dt->i dtp))
        col (get-in x [:region :columns cid])
        rgn (:region x)
        pcon (-> rgn :spec :connected-perm)
        ac (:active-cells rgn)]
    (->> (:cells col)
         (mapv (fn [cell]
                 (->> (:segments cell)
                      (map :synapses)
                      (mapv (fn [syns]
                              (group-by (fn [[id p]]
                                          [(if (>= p pcon)
                                             :connected :disconnected)
                                           (if (ac id)
                                             :active :inactive)])
                                        syns)))))))))

(defn animation-step!
  []
  (let [x (peek @steps)
        curr (:region x)
        bit-width (:input-size (:spec curr))
        ncol (count (:columns curr))
        dt @selected-dt
        view-r (get-in @steps [(dt->i dt) :region])
        rs (map :region @steps)
        inb-data (mapv inbits-display-data @steps (cons nil @steps))
        rgn-data (mapv rgn-column-states rs (cons nil rs))]
    (update-text-display)
    (c/clear-rect canvas-ctx {:x 0 :y 0 :w width-px :h height-px})
    (draw-inbits canvas-ctx inb-data bit-width dt)
    (draw-rgn canvas-ctx rgn-data ncol @selected-cid dt)
    (if @selected-cid
      (let [isd1 (in-synapse-display-data [@selected-cid] dt)
            asd (all-segments-display-data @selected-cid dt)]
        (draw-insynapses canvas-ctx [isd1])
        (draw-all-segments canvas-ctx asd @selected-cid dt ncol
                           (:activation-threshold (:spec view-r))))
      (let [view-cids (:active-columns view-r)
            isd (map in-synapse-display-data view-cids (repeat dt))
            msd (map matching-segments-display-data view-cids (repeat dt))]
        (draw-insynapses canvas-ctx isd)
        (draw-matching-segments canvas-ctx msd)))))

;; ## Event stream processing

(defn listen [el type]
  (let [out (chan)]
    (gevents/listen el type
                    (fn [e] (put! out e)))
    out))

(defn handle-canvas-clicks
  []
  (let [clicks (listen canvas-dom "click")]
    (go
     (while true
       (let [e (<! clicks)
             x (.-offsetX e)
             y (.-offsetY e)
             ;; we need to assume there is a previous step, so:
             max-dt (- (count @steps) 2)]
         (if-let [[cid dt] (px->column x y)]
           ;; column clicked
           (do
             (reset! selected-cid cid)
             (reset! selected-dt (min dt max-dt)))
           (if-let [[id dt] (px->inbit x y)]
             ;; in-bit clicked
             (do
               (reset! selected-cid nil)
               (reset! selected-dt (min dt max-dt)))
             ;; nothing clicked
             (do
               (reset! selected-cid nil)
               (reset! selected-dt 0)))))))))

(def code-key
  {32 :space
   33 :page-up
   34 :page-down
   37 :left
   38 :up
   39 :right
   40 :down})

(defn handle-canvas-keys
  []
  (let [presses (listen js/document goog.events.EventType.KEYDOWN)]
    (go
     (while true
       (let [e (<! presses)
             kc (.-keyCode e)
             k (code-key kc)
             ;; we need to assume there is a previous step, so:
             max-dt (- (count @steps) 2)]
         (when k
           (case k
             :left (swap! selected-dt
                          (fn [x] (min (inc x) max-dt)))
             :right (if (pos? @selected-dt)
                      (swap! selected-dt
                             (fn [x] (max (dec x) 0)))
                      (do
                        (put! simulation-gate true)
                        (put! animation-gate true)))
             :up (swap! selected-cid
                        (fn [x] (if x (dec x) 0)))
             :down (swap! selected-cid
                          (fn [x] (if x (inc x) 0)))
             :page-up (swap! selected-cid
                             (fn [x] (max 0 (- x 10))))
             :page-down (swap! selected-cid
                               (fn [x] (max 0 (+ x 10))))
             )))))))

(add-watch selected-dt :redraw
           (fn [_ _ _ _]
             (put! animation-gate true)))

(add-watch selected-cid :redraw
           (fn [_ _ _ _]
             (put! animation-gate true)))

(defn init-plots
  []
  (plots/bind-time-series-plot "#plots" !agg-freqs 400 240
                               [:unpredicted :active :predicted]
                               state-colors))

(defn init-ui-all
  []
  (comportexviz.controls-ui/init-ui)
  (handle-canvas-clicks)
  (handle-canvas-keys)
  (init-plots)
  (add-watch steps :stats (fn [_ _ _ s]
                            (update-stats! (peek s) (peek (pop s)))))
  (add-watch steps :timestep (fn [_ _ _ s]
                               (update-timestep (peek s))))
  (animation-loop))

(c2.event/on-load init-ui-all)
