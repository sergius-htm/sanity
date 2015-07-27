(ns comportexviz.details
  (:require [clojure.string :as str]
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.protocols :as p]))

(defn to-fixed
  [n digits]
  #?(:cljs (.toFixed n digits)
     :clj (format (str "%." digits "f") n)))

(defn detail-text
  [htm
   prior-htm
   {dt :dt
    rgn-id :region
    lyr-id :layer
    col :col
    :as selection}]
  (let [rgn (get-in htm [:regions rgn-id])
        lyr (get rgn lyr-id)
        depth (p/layer-depth lyr)
        inp (first (core/input-seq htm))
        in (:value inp)
        bits (p/bits-value inp)]
    (->>
     ["__Selection__"
      (str "* timestep " (p/timestep rgn)
           " (delay " dt ")")
      (str "* column " (or col "nil"))
      ""
      "__Input__"
      (str in " (" (count bits) " bits)")
      ""
      "__Input bits__"
      (str (sort bits))
      ""
      "__Active columns__"
      (str (sort (p/active-columns lyr)))
      ""
      "__Bursting columns__"
      (str (sort (p/bursting-columns lyr)))
      ""
      "__Learnable cells__"
      (str (sort (p/learnable-cells lyr)))
      ""
      "__Proximal learning__"
      (for [seg-up (sort-by :target-id (vals (:proximal-learning (:state lyr))))]
        (str (:target-id seg-up) " " (dissoc seg-up :target-id :operation)))
      ""
      "__Distal learning__"
      (for [seg-up (sort-by :target-id (vals (:distal-learning (:state lyr))))]
        (str (:target-id seg-up) " " (dissoc seg-up :target-id :operation)))
      ""
      "__TP excitation__"
      (str (sort (:temporal-pooling-exc (:state lyr))))
      ""
      (if col
        (let [p-lyr (get-in prior-htm [:regions rgn-id lyr-id])
              p-prox-sg (:proximal-sg p-lyr)
              p-distal-sg (:distal-sg p-lyr)
              d-pcon (:distal-perm-connected (p/params p-lyr))
              ff-pcon (:ff-perm-connected (p/params p-lyr))
              bits (:in-ff-bits (:state lyr))
              sig-bits (:in-stable-ff-bits (:state lyr))
              d-bits (:distal-bits (:prior-distal-state lyr))
              d-lc-bits (:distal-lc-bits (:prior-distal-state lyr))
              ]
          ["__Column overlap__"
           (str (get (:col-overlaps (:state lyr)) [col 0]))
           ""
           "__Selected column__"
           "__Connected ff-synapses__"
           (for [[si syns] (map-indexed vector (p/cell-segments p-prox-sg [col 0]))
                 :when (seq syns)]
             [(str "FF segment " si)
              (for [[id p] (sort syns)]
                (str "  " id
                     (if (>= p ff-pcon) " :=> " " :.: ")
                     (to-fixed p 2)
                     (if (contains? sig-bits id) " S")
                     (if (contains? bits id)
                       (str " A "
                            (let [[src-k src-i] (core/source-of-incoming-bit htm rgn-id id)
                                  src-rgn (get-in htm [:regions src-k])]
                              (if src-rgn
                                (let [src-cell (p/source-of-bit src-rgn src-i)]
                                  (str src-k " " src-cell))
                                (str src-k " " src-i)))))))])
           "__Cells and their Dendrite segments__"
           (for [ci (range (p/layer-depth lyr))
                 :let [segs (p/cell-segments p-distal-sg [col ci])]]
             [(str "CELL " ci)
              (str (count segs) " = " (map count segs))
              #_(str "Distal excitation from this cell: "
                   (p/targets-connected-from p-distal-sg (+ ci (* depth col)))) ;; TODO cell->id
              (for [[si syns] (map-indexed vector segs)]
                [(str "  SEGMENT " si)
                 (for [[id p] (sort syns)]
                   (str "  " id
                        (if (>= p d-pcon) " :=> " " :.: ")
                        (to-fixed p 2)
                        (if (contains? d-lc-bits id) " L"
                            (if (contains? d-bits id) " A"))))])
              ])
           ]))
      ""
      "__spec__"
      (map str (sort (p/params rgn)))]
     (flatten)
     (interpose \newline)
     (apply str))))