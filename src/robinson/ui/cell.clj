;; Functions for rendering state to screen
(ns robinson.ui.cell
  (:require 
            [taoensso.timbre :as log]
            [robinson.random :as rr]
            [robinson.color :as rcolor]
            [robinson.renderutil :as rutil]
            [robinson.noise :as rnoise]
            [robinson.traps :as rt]
            [zaffre.color :as zcolor]
            [clojure.core.match :refer [match]]))

(set! *warn-on-reflection* true)

(def cell-type-palette
  {:fire                   [:red :orange]
   :water                  [:blue :dark-blue]
   :surf                   [:white :sea-foam :blue-green]
   :shallow-water          [:white :sea-foam :blue-green :blue-green]
   :swamp                  [:white :sea-foam :blue-green]
   :lava                   [:red :orange :yellow]
   :bamboo-water-collector [:blue :light-blue :dark-blue]
   :solar-still            [:blue :light-blue :dark-blue]
   :freshwater-hole        [:blue :light-blue :dark-blue]
   :saltwater-hole         [:blue :light-blue :dark-blue]})

(def palette-noise (rnoise/create-noise))
(defn cell-type->color
  [wx wy cell-type]
  (let [r (rnoise/noise3d palette-noise wx wy (mod (/ (System/currentTimeMillis) 4000) 10000))
        palette (get cell-type-palette cell-type)
        start-idx (int (* r (count palette)))
        end-idx (mod (inc start-idx) (count palette))
        start (nth palette start-idx)
        end (nth palette end-idx)]
    (rand-nth palette)
    start))

(defn has-palette?
  [cell-type]
  (contains? #{:fire :water 
               :surf :shallow-water 
               :swamp :lava 
               :bamboo-water-collector :solar-still 
               :freshwater-hole :saltwater-hole} cell-type))

(defn fill-put-string-color-style-defaults
  ([string]
    (fill-put-string-color-style-defaults 0 0 string :white :black #{}))
  ([wx wy string]
    (fill-put-string-color-style-defaults wx wy string :white :black #{}))
  ([wx wy string fg]
    (fill-put-string-color-style-defaults wx wy string fg :black #{}))
  ([wx wy string fg bg]
    (fill-put-string-color-style-defaults wx wy string fg bg #{}))
  ([wx wy string fg bg styles]
   {:pre [(clojure.set/superset? #{:underline :bold} styles)]}
   (let [new-fg (rcolor/color->rgb (if (has-palette? fg)
                                     (cell-type->color wx wy fg)
                                     fg))
         bg     (rcolor/color->rgb bg)]
     {:c string :fg new-fg :bg bg})))

(def cell-type->cp437-character {:locker \▌
                                 :fire \≀
                                 :hammock-h \-
                                 :shallow-water \~
                                 :horizontal-wall-alt \°
                                 :bottom-right-1 \╝
                                 :bulkhead \◘
                                 :bottom-left-2 \◙
                                 :white-horizontal-wall-alt \°
                                 :upper-right-1 \╗
                                 :dune \∩
                                 :upper-left-2 \◙
                                 :corridor \#
                                 :locker2 \▐
                                 :tree \T
                                 :moss-bottom-left-2 \◙
                                 :white-upper-left-1 \╔
                                 :table \╤
                                 :sand \.
                                 :canon-truck-2 \▀
                                 :white-corridor \#
                                 :campfire \^
                                 :rocky-shore \∩
                                 :horizontal-wall \═
                                 :canon \║
                                 :moss-upper-right-2 \◙
                                 :close-door \+
                                 :upper-right-2 \◙
                                 :bulkhead2 \◘
                                 :white-upper-right-2 \◙
                                 :white-bottom-left-1 \╚
                                 :deck \·
                                 :lava \~
                                 :canon-truck-1 \▄
                                 :moss-bottom-right-2 \◙
                                 :moss-bottom-left-1 \╚
                                 :open-door \-
                                 :chair \╥
                                 :moss-upper-left-2 \◙
                                 :white-horizontal-wall \═
                                 :tarp-shelter \#
                                 :vertical-wall \║
                                 :moss-bottom-right-1 \╝
                                 :white-bottom-left-2 \◙
                                 :short-grass \·
                                 :empty \space
                                 :white-upper-left-2 \◙
                                 :moss-corridor \#
                                 :tall-grass \"
                                 :moss-vertical-wall-alt \°
                                 :mast \╨
                                 :ramada \#
                                 :gravel \·
                                 :chest \■
                                 :white-vertical-wall \║
                                 :down-stairs \>
                                 :dry-hole \O
                                 :white-upper-right-1 \╗
                                 :up-stairs \<
                                 :vertical-wall-alt \°
                                 :lean-to \#
                                 :railing \#
                                 :fruit-tree \♣
                                 :moss-horizontal-wall-alt \°
                                 :upper-left-1 \╔
                                 :solar-still \O
                                 :bamboo \┐
                                 :ships-wheel \Φ
                                 :hammock-v \)
                                 :vine \⌠
                                 :bottom-right-2 \◙
                                 :white-vertical-wall-alt \°
                                 :surf \~
                                 :white-bottom-right-2 \◙
                                 :bottom-left-1 \╚
                                 :palm-tree \7
                                 :canon-breach \║
                                 :ladder \≡
                                 :moss-vertical-wall \║
                                 :porthole \°
                                 :wooden-wall \#
                                 :artifact-chest \■
                                 :beam \═
                                 :moss-horizontal-wall \═
                                 :wheel \○
                                 :moss-upper-right-1 \╗
                                 :water \≈
                                 :moss-upper-left-1 \╔
                                 :palisade \#
                                 :grate \╬
                                 :altar \┬
                                 :tackle \º
                                 :mountain \▲
                                 :bamboo-water-collector \O
                                 :floor \.
                                 :dirt \.
                                 :swamp \~
                                 :white-bottom-right-1 \╝})

(def trap-type? #{:crushing-wall-trigger
                  :wall-darts-trigger
                  :poisonous-gas-trigger
                  :spike-pit
                  :snakes-trigger})


(defn cell->cp437-character
  [{:keys [type water trap-found]
    :or {water 0}}]
  {:post [(char? %)]}
  (or (and (#{:freshwater-hole :saltwater-hole} type) (if (< water 10) \0 \~))
      (and (trap-type? type) (if trap-found \^ \.))
      (get cell-type->cp437-character type \?)
      \?))


(defn cell->unicode-character
  [cell]
  (case (get cell :type)
     :mountain    \u2206 ;; ∆
     :fruit-tree  \u2648 ;; ♈
     :dune        \u1d16 ;; ᴖ
     :rocky-shore \u1d16 ;; ᴖ
     :bamboo      \u01c1 ;; ∥ 
     (cell->cp437-character cell)))


(defn cell->color
  [cell current-time]
  (case (get cell :type)
     :open-door       :brown
     :close-door      :brown
     :corridor        :light-gray
     :fire            (if (= (cell :discovered) current-time)
                         :fire
                         :red)
     :water           (if (= (cell :discovered) current-time)
                         :water
                         :blue)
     :surf            (if (= (cell :discovered) current-time)
                         :surf
                         :light-blue)
     :shallow-water   (if (= (cell :discovered) current-time)
                         :shallow-water
                         :light-blue)
     :swamp           (if (= (cell :discovered) current-time)
                         :swamp
                         :light-blue)
     :lava            (if (= (cell :discovered) current-time)
                         :lava
                         :light-blue)
     :mountain        :gray
     :sand            :beige
     :dirt            :brown
     :dune            :light-brown
     :rocky-shore     :dark-gray
     :gravel          :gray
     :short-grass     :green
     :tall-grass      :dark-green
     :tree            :dark-green
     :bamboo          :light-green
     :palisade        :brown
     :ramada          :beige
     :tarp-shelter    :blue
     :lean-to         :light-green
     :campfire        :brown
     :bamboo-water-collector
                      (if (< 10 (get cell :water 0))
                        :bamboo-water-collector
                        :white)
     :solar-still
                      (if (< 10 (get cell :water 0))
                        :solar-still
                        :white)
     :palm-tree       :dark-green
     :fruit-tree      :light-green
     :freshwater-hole (if (< 10 (get cell :water 0))
                        :freshwater-hole
                        :white)
     :saltwater-hole  (if (< 10 (get cell :water 0))
                        :saltwater-hole
                        :white)
     :dry-hole        :white
     ;; pirate ship cell types
     :bulkhead        :brown
     :wheel           :dark-brown
     :bulkhead2       :brown
     :wooden-wall     :ship-brown
     :railing         :ship-brown
     :hammock-v       :brown
     :hammock-h       :brown
     :deck            :dark-brown
     :canon-breach    :gray
     :tackle          :brown
     :canon           :gray
     :grate           :dark-beige
     :table           :ship-light-brown
     :chair           :ship-light-brown
     :mast            :ship-light-brown
     :beam            :brown
     :canon-truck-1   :dark-brown
     :locker          :brown
     :locker2         :brown
     :canon-truck-2   :dark-brown
     :ships-wheel     :brown
     :ladder          :dark-beige
     :porthole        :brown
     :chest           :ship-dark-brown
     :artifact-chest  :dark-beige
     ;; ruined temple cell types
     :vertical-wall   :temple-beige
     :horizontal-wall :temple-beige
     :vertical-wall-alt :white
     :horizontal-wall-alt :white
     :upper-left-1    :temple-beige
     :upper-right-1   :temple-beige
     :bottom-left-1   :temple-beige
     :bottom-right-1  :temple-beige
     :upper-left-2    :temple-beige
     :upper-right-2   :temple-beige
     :bottom-left-2   :temple-beige
     :bottom-right-2  :temple-beige
     :altar           :white
     :vine            :moss-green
     :moss-corridor   :moss-green
     :moss-vertical-wall :moss-green
     :moss-horizontal-wall :moss-green
     :moss-vertical-wall-alt :white
     :moss-horizontal-wall-alt :white
     :moss-upper-left-1 :moss-green
     :moss-upper-right-1 :moss-green
     :moss-bottom-left-1 :moss-green
     :moss-bottom-right-1 :moss-green
     :moss-upper-left-2 :moss-green
     :moss-upper-right-2 :moss-green
     :moss-bottom-left-2 :moss-green
     :moss-bottom-right-2 :moss-green
     :white-corridor :light-gray
     :white-vertical-wall   :white
     :white-horizontal-wall :white
     :white-vertical-wall-alt :white
     :white-horizontal-wall-alt :white
     :white-upper-left-1 :white
     :white-upper-right-1 :white
     :white-bottom-left-1 :white
     :white-bottom-right-1 :white
     :white-upper-left-2 :white
     :white-upper-right-2 :white
     :white-bottom-left-2 :white
     :white-bottom-right-2 :white
     :empty                :black
     :crushing-wall-trigger :white
     :wall-darts-trigger :white
     :poisonous-gas-trigger :white
     :spike-pit :white
     :snakes-trigger :white
     :white))

(defn render-cell [cell wx wy current-time font-type]
  {:post [(fn [c] (log/info c) (char? (get c :c)))
          (integer? (get % :fg))
          (integer? (get % :bg))]}
  (let [cell-items (get cell :items)
        in-view? (= current-time (get cell :discovered 0))
        has-been-discovered? (> (get cell :discovered 0) 1)
        harvestable? (contains? cell :harvestable)
        apply? (fn [pred f]
                 (if pred
                   f
                   identity))]
    (if (or in-view? has-been-discovered?)
      (->
        (apply fill-put-string-color-style-defaults
          wx wy
          (rcolor/color-bloodied-char 
            (< current-time (get cell :bloodied 0))
            (if (and cell-items
                     (seq cell-items)
                     (= (cell :discovered) current-time))
              (cond
                (contains? #{:chest :artifact-chest} (get cell :type))
                  [\■ :dark-beige :black]
                (some (fn [item] (= (get item :id) :raft)) cell-items)
                  [\░ :beige :brown]
                :default
                  [(rutil/item->char (first cell-items))
                   (rutil/item->fg   (first cell-items))
                   :black])
              [((case font-type
                  :ttf   cell->unicode-character
                  :cp437 cell->cp437-character) cell)
               (cell->color cell current-time) :black])))
        ((apply? (not in-view?)
           (fn [{:keys [c fg bg]}] {:c c :fg (rcolor/rgb->mono fg) :bg (rcolor/rgb->mono bg)})))
        ((apply? (and in-view? harvestable?)
           (fn [{:keys [c fg bg]}] {:c c :fg bg :bg fg}))))
      {:c \  :fg (zcolor/color 0 0 0 0) :bg (zcolor/color 0 0 0 0)})))
