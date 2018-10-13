;; Functions for rendering state to screen
(ns robinson.ui.components.robinson
  (:require 
            [taoensso.timbre :as log]
            [robinson.random :as rr]
            [robinson.math :as rmath]
            [robinson.color :as rcolor]
            [robinson.renderutil :as rutil]
            [robinson.scores :as rs]
            [robinson.startgame :as sg]
            [robinson.popover :as rpop]
            [robinson.itemgen :as ig]
            [robinson.common :as rc :refer [farther-than?
                                            wrap-line
                                            fill-missing
                                            xy->pos]]
                                            
            [robinson.world :as rw :refer [current-state
                                           get-time
                                           get-cell
                                           player-cellxy
                                           distance-from-player
                                           inventory-and-player-cell-items]]
            [robinson.viewport :as rv :refer [cells-in-viewport]]
            [robinson.player :as rp :refer [player-xy
                                            player-wounded?
                                            player-poisoned?
                                            player-infected?]]
            [robinson.describe :as rdesc]
            [robinson.endgame :as rendgame :refer [gen-end-madlib]]
            [robinson.fx :as rfx]
            [robinson.crafting :as rcrafting :refer [get-recipes]]
            [robinson.itemgen :refer [can-be-wielded?
                                      can-be-wielded-for-ranged-combat?
                                      id->name]]
            [robinson.lineofsight :as rlos :refer [visible?
                                                   cell-blocking?]]
            [robinson.dialog :as rdiag :refer [get-valid-input
                                               fsm-current-state]]
            [robinson.npc :as rnpc :refer [talking-npcs
                                           npcs-in-viewport]]
            [robinson.noise :as rnoise]
            [robinson.traps :as rt]
            [robinson.ui.updater :as ruu]
            [zaffre.terminal :as zat]
            [zaffre.animation.wrapper :as zaw]
            [zaffre.components :as zc]
            [zaffre.components.ui :as zcui]
            [zaffre.util :as zutil]
            [tinter.core :as tinter]
            clojure.set
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.pprint :as pprint]
            clojure.string))

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
  (let [r (rnoise/noise3d palette-noise wx wy (mod (/ (System/currentTimeMillis) 1000) 10000))
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


(defonce frame-count (atom 0))
(defonce frame-steps 1000000)

(defn size
  [^zaffre.terminal.Terminal screen]
  (juxt (zat/args screen) :screen-width :screen-height))

(defn is-menu-state? [state]
  (contains? #{:inventory :describe-inventory :pickup-selection :drop :eat} (get-in state [:world :current-state])))

(binding [zc/*updater* ruu/updater]
(zc/def-component Highlight
  [this]
  (let [{:keys [children]} (zc/props this)]
    (zc/csx
      [:text {:style {:color [229 155 8 255]}} children])))


(zc/def-component ActionPrompt
  [this]
  (let [{:keys [children]} (zc/props this)]
    (zc/csx
      [:view {:style {:position :fixed :top 0 :left 0 :hight 1}} children])))

(zc/def-component ItemList
  [this]
  (let [{:keys [items]} (zc/props this)]
    (zc/csx
      [:view {}
          (map (fn [{:keys [fg bg style s]}]
            [:view {:style {:fg fg :bg bg :display :block}} [
              [:text {} [s]]]])
            items)])))

(zc/def-component SelectItemListItem
  [this]
  (let [{:keys [hotkey selected text]} (zc/props this)]
    (zc/csx
      [:text {} [
        [Highlight {} [(str hotkey " ")]]
        [:text {} [(format "%s %s"
                     (if selected
                       "+"
                       "-")
                     text)]]]])))

(zc/def-component SelectItemList
  [this]
  (let [{:keys [title selected-hotkeys use-applicable items]} (zc/props this)]
    (zc/csx [:view {:style {:width "60%"}} [
              [:text {} [title]]
              [:view {:style {:top 2 :left 10}} 
                     (map (fn [item]
                            (zc/csx [SelectItemListItem {:hotkey 
                                                           (or (item :hotkey)
                                                               \ )
                                                         :selected
                                                           (contains? selected-hotkeys (get item :hotkey))
                                                         :text
                                                           (str (get item :name)
                                                             (if (contains? item :count)
                                                               (format " (%dx)" (int (get item :count)))
                                                               ""))}]))
                          items)]
              [:text {:style {:top 5 :left 10}} [
                [Highlight {} ["enter "]]
                [:text {} ["to continue"]]]]]])))

(zc/def-component Atmo
  [this]
  (let [{:keys [time atmo-data]} (zc/props this)
        frames (count atmo-data)
        t      (mod time frames)
        frame  (nth atmo-data t)
        indexed-colors (cons (repeat 7 [0 0 0]) (apply map vector (partition 3 frame)))]
    (zc/csx
      [:view {} [
        [zcui/ListImage {:data indexed-colors}]]])))

(zc/def-component StatusUI
  [this]
  (let [{:keys [wounded poisoned infected style]} (zc/props this)
        wounded-color (rcolor/color->rgb (if wounded :red :black))
        poisoned-color (rcolor/color->rgb (if poisoned :green :black))
        infected-color (rcolor/color->rgb (if infected :yellow :black))
        gray-color (rcolor/color->rgb :gray)]
    (zc/csx
      [:view {:style (merge {:width 7 :background-color gray-color} style)} [
      [:text {:style {:background-color gray-color}} [
        [:text {} [" "]]
        [:text {:style {:color wounded-color}} ["\u2665"]]
        [:text {} [" "]]
        [:text {:style {:color poisoned-color}} ["\u2665"]]
        [:text {} [" "]]
        [:text {:style {:color infected-color}} ["\u2665"]]
        [:text {} [" "]]]]]])))

    ;    (int (-> state :world :player :hp))
    ;    (-> state :world :player :max-hp)
    ;    (apply str (interpose " " (-> state :world :player :status)))
(zc/def-component DblBarChart
  [this]
  (let [{:keys [fg-1 fg-2 p1 p2 width direction style]} (zc/props this)
        root (* width (min p1 p2))
        padding (* width (- 1 (max p1 p2)))
        diff (- width root padding)
        black-color (rcolor/color->rgb :black)]
      (case direction
        :left
          (zc/csx
              [:text {:style style} [
                #_[:text {} [(format "%.2f %.2f" (float p1) (float p2))]]
                [:text {:style {:color black-color
                                :background-color black-color}} [(apply str (repeat padding " "))]]
                [:text {:style {:color            (if (< p2 p1) fg-1 black-color)
                                :background-color (if (> p2 p1) fg-2 black-color)}} [(apply str (repeat diff "\u2584"))]]
                [:text {:style {:color fg-1 :background-color fg-2}} [(apply str (repeat root "\u2584"))]]]])
        :right
          (zc/csx
              [:text {:style style} [
                #_[:text {} [(format "%.2f %.2f" (float p1) (float p2))]]
                [:text {:style {:color fg-1 :background-color fg-2}} [(apply str (repeat root "\u2584"))]]
                [:text {:style {:color            (if (< p2 p1) fg-1 black-color)
                                :background-color (if (> p2 p1) fg-2 black-color)}} [(apply str (repeat diff "\u2584"))]]
                [:text {:style {:color black-color
                                :background-color black-color}} [(apply str (repeat padding " "))]]]]))))

(zc/def-component HpWtlBars
  [this]
  (let [{:keys [game-state style]} (zc/props this)
        wtl        (get-in game-state [:world :player :will-to-live])
        max-wtl    (get-in game-state [:world :player :max-will-to-live])
        hp         (get-in game-state [:world :player :hp])
        max-hp     (get-in game-state [:world :player :max-hp])]
    (zc/csx
      [DblBarChart {:fg-1 (rcolor/color->rgb :red)
                    :fg-2 (rcolor/color->rgb :green)
                    :p1  (/ hp max-hp)
                    :p2  (/ wtl max-wtl)
                    :width 37
                    :direction :left
                    :style style}])))

(zc/def-component ThirstHungerBars
  [this]
  (let [{:keys [game-state style]} (zc/props this)
        thirst      (get-in game-state [:world :player :thirst])
        max-thirst  (get-in game-state [:world :player :max-thirst])
        hunger      (get-in game-state [:world :player :hunger])
        max-hunger  (get-in game-state [:world :player :max-hunger])]
    (zc/csx
      [DblBarChart {:fg-1 (rcolor/color->rgb :blue)
                    :fg-2 (rcolor/color->rgb :yellow)
                    :p1  (- 1 (/ thirst max-thirst))
                    :p2  (- 1 (/ hunger max-hunger))
                    :width 37
                    :direction :right
                    :style style}])))

(zc/def-component Hud
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx
      [:view {:style {:top 20
                      :left 0
                      :width 80
                      :display :flex
                      :align-items :center}} [
         [Atmo {:time (rw/get-time game-state)
                :atmo-data (get-in game-state [:data :atmo])}]
         [:view {} [
           [HpWtlBars {:game-state game-state :style {:left 1}}]
           [StatusUI {:game-state game-state :style {:left 37 :bottom 1}}]
           [ThirstHungerBars {:game-state game-state :style {:left (+ 37 7) :bottom 2}}]]]]])))

(defn translate-identified-items
  [state items]
  (let [identified (get-in state [:world :fruit :identified])
        poisonous  (get-in state [:world :fruit :poisonous])]
    (map (fn [item] (if (contains? identified (get item :id))
                      (if (contains? poisonous (get item :id))
                        (assoc item :name (format "%s (poisonous)" (get item :name))
                                    :name-plural (format "%s (poisonous)" (get item :name-plural)))
                        (assoc item :name (format "%s (safe)" (get item :name))
                                    :name-plural (format "%s (safe)" (get item :name-plural))))
                      item))
         items)))

#_(defn render-quests
  "Render the pickup item menu if the world state is `:pickup`."
  [state]
  (render-multi-select (state :screen)
                       "Quests"
                       []
                       (filter (fn [quest]
                                 (not (nil? (get-in state [:world  :quests (quest :id) :stage] nil))))
                               (:quests state))))

#_(defn render-craft-submenu
  "Render the craft submenu"
  [state recipe-type]
  (let [screen               (state :screen)
        selected-recipe-path (get-in state [:world :craft-recipe-path])
        hotkey               (when selected-recipe-path
                               (last selected-recipe-path))
        recipes              (get (get-recipes state) recipe-type)]
  (log/info "recipe-type" recipe-type)
  (log/info "recipes" (get-recipes state))
  (log/info "selected recipes" recipes)
  ;; render recipes
  (render-list screen :ui 11 6 29 15
    (concat
      [{:s (name recipe-type) :fg :black :bg :white :style #{:underline}}]
       (map (fn [recipe]
              {:s (format "%c-%s"
                    (get recipe :hotkey)
                    (get recipe :name))
               :fg (if (contains? recipe :applicable)
                     :black
                     :gray)
               :bg :white
               :style (if (= (get recipe :hotkey) hotkey)
                         #{:invert}
                         #{})})
            recipes)))
  ;; render recipe-info
  (if hotkey
    (let [matching-recipes   (filter (fn [recipe] (= (get recipe :hotkey) hotkey))
                                     recipes)
          recipe             (get (first matching-recipes) :recipe)
          exhaust            (get recipe :exhaust [])
          have               (get recipe :have-or [])
          inventory-id-freqs (rp/inventory-id-freqs state)]
      (log/info "exhaust" exhaust "have" have)
      (render-list screen :ui 41 6 29 15
      (concat
        [{:s "" :fg :black :bg :white :style #{}}
         {:s "Consumes" :fg :black :bg :white :style #{}}]
        (if (empty? exhaust)
          [{:s "N/A" :fg :black :bg :white :style #{}}]
          (let [idx-ids (mapcat (fn [ids]
                           (map-indexed vector ids))
                         (partition-by identity (sort exhaust)))]
            (println "idx-ids" idx-ids)
            (reduce (fn [lines [idx id]]
                      (conj lines
                            (if (< idx (get inventory-id-freqs id 0))
                              {:s (id->name id) :fg :black :bg :white :style #{}}
                              {:s (id->name id) :fg :gray :bg :white :style #{}})))
                   []
                   idx-ids)))
        [{:s "" :fg :black :bg :white :style #{}}
         {:s "Required tools" :fg :black :bg :white :style #{}}]
        (if (empty? have)
          [{:s "N/A" :fg :black :bg :white :style #{}}]
          (map (fn [id] {:s (id->name id) :fg :black :bg :white :style #{}}) have)))))
    (render-list screen :ui 41 6 29 15
        [{:s "Select a recipe" :fg :black :bg :white :style #{}}]))
  (render-rect-single-border screen 10 5 60 15 :black :white)
  (render-vertical-border screen 40 6 15 :black :white)
  (put-string screen :ui 40 20 "\u2534" :black :white)
  (put-string screen :ui 37 5 "Craft" :black :white)))
          
#_(defn render-craft-weapon
  "Render the craft weapon menu if the world state is `:craft-weapon`."
  [state]
  (render-craft-submenu state :weapons))

#_(defn render-craft-survival
  "Render the craft menu if the world state is `:craft-survival`."
  [state]
  (render-craft-submenu state :survival))

#_(defn render-craft-shelter
  "Render the craft menu if the world state is `:craft-shelter`."
  [state]
  (render-craft-submenu state :shelter))

#_(defn render-craft-transportation
  "Render the craft menu if the world state is `:craft-transportation`."
  [state]
  (render-craft-submenu state :transportation))

#_(defn render-wield
  "Render the wield item menu if the world state is `:wield`."
  [state]
  (render-multi-select (state :screen) "Wield" [] (filter can-be-wielded? (-> state :world :player :inventory))))

#_(defn render-wield-ranged
  "Render the wield ranged  item menu if the world state is `:wield-ranged`."
  [state]
  (render-multi-select (state :screen) "Wield Ranged" [] (filter can-be-wielded-for-ranged-combat? (-> state :world :player :inventory))))

#_(defn render-raw-popover [state]
  (let [screen     (state :screen)
        message    (rpop/get-popover-message state)
        lines      (clojure.string/split-lines message)
        width      (reduce max 27 (map markup->length lines))
        height     (count lines)
        [v-width
         v-height] (rv/viewport-wh state)
        popover-x  (int (- (/ v-width 2) (/ width 2)))
        popover-y  (int (- (/ v-height 2) (/ height 2)))]
    (println "rendering popover at" popover-x "," popover-y)
    (render-list screen :ui popover-x popover-y width (inc height)
      (concat
        [{:s "" :fg :black :bg :white :style #{}}]
        (map
          (fn [line] {:s line :fg :black :bg :white :style #{:center}})
          lines)))
    (render-rect-double-border screen (dec popover-x) popover-y (inc width) (inc height) :black :white)))

#_(defn render-popover [state]
  (let [screen     (state :screen)
        message    (rpop/get-popover-message state)
        lines      (clojure.string/split-lines message)
        width      (reduce max 27 (map markup->length lines))
        height     (count lines)
        [v-width
         v-height] (rv/viewport-wh state)
        popover-x  (int (- (/ v-width 2) (/ width 2)))
        popover-y  (int (- (/ v-height 2) (/ height 2)))]
    (println "rendering popover at" popover-x "," popover-y)
    (render-list screen :ui popover-x popover-y width (+ 4 height)
      (concat
        [{:s "" :fg :black :bg :white :style #{}}]
        (map
          (fn [line] {:s line :fg :black :bg :white :style #{:center}})
          (remove empty? lines))
        [{:s "" :fg :black :bg :white :style #{}}
         {:s "  Press <color fg=\"highlight\">space</color> to continue." :fg :black :bg :white :style #{:center}}]))
    (render-rect-double-border screen (dec popover-x) popover-y (inc width) (+ 4 height) :black :white)))


#_(defn render-rescued-text [state]
  (let [screen      (state :screen)
        rescue-mode (rendgame/rescue-mode state)]
    (render-list screen :ui 16 4 53 6
      (concat
        [{:s "" :fg :black :bg :white :style #{}}]
        (map
          (fn [line] {:s line :fg :black :bg :white :style #{:center}})
          ["Rescue!"
           (format "A passing %s spots you." rescue-mode)
           "Press <color fg=\"highlight\">space</color> to continue."])))
    (render-rect-double-border screen 16 4 53 6 :black :white)))


#_(defn render-harvest
  "Render the harvest prompt if the world state is `:harvest`."
  [state]
  (put-string (state :screen) :ui 0 0 "Pick a direction to harvest."))

#_(defn render-map
  "The big render function used during the normal game.
   This renders everything - the map, the menus, the log,
   the status bar. Everything."
  [state]
  (let [screen                                      (state :screen)
        {:keys [columns rows]}                      (-> screen zat/groups :app)
        current-time                                (get-in state [:world :time])
        {{player-x :x player-y :y} :pos :as player} (rp/get-player state)
        d                                           (rlos/sight-distance state)
        cells                                       (rv/cellsxy-in-viewport state)
        lantern-on                                  (when-let [lantern (rp/inventory-id->item state :lantern)]
                                                      (get lantern state :off))
        ;_ (println cells)
        ;_ (log/info "cells" (str cells))
        characters     (persistent!
                         (reduce (fn [characters [cell vx vy wx wy]]
                                   ;(log/debug "begin-render")
                                   ;(clear (state :screen))
                                   ;;(debug "rendering place" (current-place state))
                                   ;; draw map
                                   ;(println "render-cell" (str cell) vx vy wx wy)
                                   #_(when (= (get cell :discovered 0) current-time)
                                     (println "render-cell" (select-keys cell [:type :discovered]) vx vy wx wy))
                                   (if (or (nil? cell)
                                           (not (cell :discovered)))
                                     characters
                                     (let [cell-items (cell :items)
                                           ;_ (println "render-cell" (str cell) vx vy wx wy)
                                           out-char (apply fill-put-string-color-style-defaults
                                                      (rcolor/color-bloodied-char 
                                                        (< current-time (get cell :bloodied 0))
                                                        (if (and cell-items
                                                                 (seq cell-items)
                                                                 (= (cell :discovered) current-time))
                                                          (if (contains? #{:chest :artifact-chest} (get cell :type))
                                                            [\■ :dark-beige :black]
                                                            [(rutil/item->char (first cell-items))
                                                             (rutil/item->fg   (first cell-items))
                                                             :black])
                                                          (case (cell :type)
                                                           :floor           [\·]
                                                           :open-door       [\-  :brown  :black #{:bold}]
                                                           :close-door      [\+  :brown  :black #{:bold}]
                                                           :corridor        [\#] 
                                                           :down-stairs     [\>] 
                                                           :up-stairs       [\<] 
                                                           :fire            [\u2240 (if (= (cell :discovered) current-time)
                                                                                       :fire
                                                                                       :red) :black] ;; ≀ 
                                                           :water           [\u2248 (if (= (cell :discovered) current-time)
                                                                                       :water
                                                                                       :blue) :black] ;; ≈ 
                                                           :surf            [\~ (if (= (cell :discovered) current-time)
                                                                                   :surf
                                                                                   :light-blue) :black]
                                                           :shallow-water   [\~ (if (= (cell :discovered) current-time)
                                                                                   :shallow-water
                                                                                   :light-blue) :black]
                                                           :swamp           [\~ (if (= (cell :discovered) current-time)
                                                                                   :swamp
                                                                                   :light-blue) :black]
                                                           :lava            [\~ (if (= (cell :discovered) current-time)
                                                                                   :lava
                                                                                   :light-blue) :black]
                                                           :mountain        [\u2206 :gray :black] ;; ∆
                                                           :sand            [\·  :beige      :black]
                                                           :dirt            [\·  :brown      :black]
                                                           :dune            [\u1d16  :light-brown :black] ;; ᴖ
                                                           :rocky-shore     [\u1d16  :dark-gray  :black] ;; ᴖ
                                                           :gravel          [\·  :gray       :black]
                                                           :short-grass     [\·  :green      :black]
                                                           :tall-grass      [\" :dark-green :black]
                                                           :tree            [\T  :dark-green :black]
                                                           :bamboo          [\u01c1 :light-green :black] ;; ∥ 
                                                           :palisade        [\# :brown :black]
                                                           :ramada          [\# :beige :black]
                                                           :tarp-shelter    [\# :blue  :black]
                                                           :lean-to         [\# :light-green :black]
                                                           :campfire        [\^ :brown :black]
                                                           :bamboo-water-collector
                                                                            (if (< 10 (get cell :water 0))
                                                                              [\O :bamboo-water-collector :black]
                                                                              [\O])
                                                           :solar-still
                                                                            (if (< 10 (get cell :water 0))
                                                                              [\O :solar-still :black]
                                                                              [\O])
                                                           :palm-tree       [\7  :dark-green :black]
                                                           :fruit-tree      [\u2648  :light-green :black] ;; ♈
                                                           :freshwater-hole (if (< 10 (get cell :water 0))
                                                                              [\~ :freshwater-hole :black]
                                                                              [\O])
                                                           :saltwater-hole  (if (< 10 (get cell :water 0))
                                                                              [\~ :saltwater-hole :black]
                                                                              [\O])
                                                           :dry-hole        [\O]
                                                           ;; pirate ship cell types
                                                           :bulkhead        [\◘ :brown :black]
                                                           :wheel           [\○ :dark-brown :black]
                                                           :bulkhead2       [\◘ :brown :black]
                                                           :wooden-wall     [\# :ship-brown :black]
                                                           :railing         [\# :ship-brown :black]
                                                           :hammock-v       [\) :brown :black]
                                                           :hammock-h       [\- :brown :black]
                                                           :deck            [\· :dark-brown :black]
                                                           :canon-breach    [\║ :gray :dark-brown]
                                                           :tackle          [\º :brown :black]
                                                           :canon           [\║ :gray :black]
                                                           :grate           [\╬ :dark-beige :black]
                                                           :table           [\╤ :ship-light-brown :black]
                                                           :chair           [\╥ :ship-light-brown :black]
                                                           :mast            [\╨ :ship-light-brown :black]
                                                           :beam            [\═ :brown :black]
                                                           :canon-truck-1   [\▄ :dark-brown :black]
                                                           :locker          [\▌ :brown :black]
                                                           :locker2         [\▐ :brown :black]
                                                           :canon-truck-2   [\▀ :dark-brown :black]
                                                           :ships-wheel     [\Φ :brown :black]
                                                           :ladder          [\≡ :dark-beige :black]
                                                           :porthole        [\° :brown :black]
                                                           :chest           [\■ :ship-dark-brown :black]
                                                           :artifact-chest  [\■ :dark-beige :black]
                                                           ;; ruined temple cell types
                                                           :vertical-wall   [\║ :temple-beige :black]
                                                           :horizontal-wall [\═ :temple-beige :black]
                                                           :vertical-wall-alt [\° :white :black]
                                                           :horizontal-wall-alt [\° :white :black]
                                                           :upper-left-1    [\╔ :temple-beige :black]
                                                           :upper-right-1   [\╗ :temple-beige :black]
                                                           :bottom-left-1   [\╚ :temple-beige :black]
                                                           :bottom-right-1  [\╝ :temple-beige :black]
                                                           :upper-left-2    [\◙ :temple-beige :black]
                                                           :upper-right-2   [\◙ :temple-beige :black]
                                                           :bottom-left-2   [\◙ :temple-beige :black]
                                                           :bottom-right-2  [\◙ :temple-beige :black]
                                                           :altar           [\┬ :white :black]
                                                           :vine            [\⌠ :moss-green :black]
                                                           :moss-corridor   [\# :moss-green :black]
                                                           :moss-vertical-wall  [\║ :moss-green :black]
                                                           :moss-horizontal-wall [\═ :moss-green :black]
                                                           :moss-vertical-wall-alt [\° :white :black]
                                                           :moss-horizontal-wall-alt [\° :white :black]
                                                           :moss-upper-left-1 [\╔ :moss-green :black]
                                                           :moss-upper-right-1 [\╗ :moss-green :black]
                                                           :moss-bottom-left-1 [\╚ :moss-green :black]
                                                           :moss-bottom-right-1 [\╝ :moss-green :black]
                                                           :moss-upper-left-2 [\◙ :moss-green :black]
                                                           :moss-upper-right-2 [\◙ :moss-green :black]
                                                           :moss-bottom-left-2 [\◙ :moss-green :black]
                                                           :moss-bottom-right-2 [\◙ :moss-green :black]
                                                           :white-corridor  [\# :white :black]
                                                           :white-vertical-wall   [\║ :white :black]
                                                           :white-horizontal-wall [\═ :white :black]
                                                           :white-vertical-wall-alt [\° :white :black]
                                                           :white-horizontal-wall-alt [\° :white :black]
                                                           :white-upper-left-1 [\╔ :white :black]
                                                           :white-upper-right-1 [\╗ :white :black]
                                                           :white-bottom-left-1 [\╚ :white :black]
                                                           :white-bottom-right-1 [\╝ :white :black]
                                                           :white-upper-left-2 [\◙ :white :black]
                                                           :white-upper-right-2 [\◙ :white :black]
                                                           :white-bottom-left-2 [\◙ :white :black]
                                                           :white-bottom-right-2 [\◙ :white :black]
                                                           :empty                [\space :black :black]
                                                           :crushing-wall-trigger
                                                                             (if (get cell :trap-found)
                                                                               [\^]
                                                                               [\·])
                                                           :wall-darts-trigger
                                                                             (if (get cell :trap-found)
                                                                               [\^]
                                                                               [\·])
                                                           :poisonous-gas-trigger
                                                                             (if (get cell :trap-found)
                                                                               [\^]
                                                                               [\·])
                                                           :spike-pit
                                                                             (if (get cell :trap-found)
                                                                               [\^]
                                                                               [\·])
                                                           :snakes-trigger
                                                                             (if (get cell :trap-found)
                                                                               [\^]
                                                                               [\·])
                                              
                                                           (do (log/info (format "unknown type: %s %s" (str (get cell :type)) (str cell)))
                                                           [\?])))))
                                           ;; shade character based on visibility, harvestableness, raft
                                           shaded-out-char (cond
                                                             (not= (cell :discovered) current-time)
                                                               (-> out-char
                                                                 (update-in [1] (fn [c] (rcolor/rgb->mono (rcolor/darken-rgb c 0.18))))
                                                                 (update-in [2] (fn [c] (rcolor/rgb->mono (rcolor/darken-rgb c 0.15)))))
                                                             (contains? cell :harvestable)
                                                               (let [[chr fg bg] out-char]
                                                                 [chr bg (rcolor/night-tint (rcolor/color->rgb fg) d)])
                                                             (contains? (set (map :id cell-items)) :raft)
                                                               (let [[chr fg bg] out-char]
                                                                 (log/info "raft-cell" out-char cell-items)
                                                                 (if (> (count cell-items) 1)
                                                                   [chr fg (rcolor/color->rgb :brown)]
                                                                   [\u01c1 (rcolor/color->rgb :black) (rcolor/color->rgb :brown)]))
                                                             :else
                                                               out-char)
                                           ;; add character opts to indicate distance from player
                                           shaded-out-char (if (and (= (get cell :discovered) current-time)
                                                                    (not (contains? #{:fire :lava} (get cell :type)))
                                                                    (get shaded-out-char 4))
                                                             (update shaded-out-char
                                                                     4 ;; opts index
                                                                     (fn [opts]
                                                                       (assoc opts
                                                                              :visible (= (cell :discovered) current-time)
                                                                              :lantern-flicker (and lantern-on (= (cell :discovered) current-time))
                                                                              :distance-from-player
                                                                                (distance-from-player state (xy->pos wx wy))
                                                                              :night-tint
                                                                                d)))
                                                             shaded-out-char)]
                                         (conj! characters {:x    vx
                                                            :y    vy
                                                            :c    (get shaded-out-char 0)
                                                            :fg   (get shaded-out-char 1)
                                                            :bg   (get shaded-out-char 2)
                                                            :opts (get shaded-out-char 4)}))))
                                    (transient [])
                                    cells))]
    (clear screen)
    ;; set rain mask to all true
    ; TODO: remove and use groups instead
    ;(ranimation/reset-rain-mask! screen true)
    ;; set palette
    (zaw/set-palette! screen cell-type-palette)
    #_(log/info "putting chars" characters)
    (put-chars screen :map characters)
    ;; draw character
    ;(log/debug (-> state :world :player))
    (let [[vx vy] (rv/viewport-xy state)
          [x y]   (rp/player-xy state)]
      (put-string
        screen
        :map
        (- x vx)
        (- y vy)
        "@"
        (if (< current-time (get player :bloodied 0))
          :dark-red
          :white)
        (if (contains? (set (map :id (get (first (player-cellxy state)) :items))) :raft)
          :brown
          :black)))
    ;; if character is fishing, draw pole
    (condp = (current-state state)
      :fishing-left  (put-string screen :ui (dec (-> state :world :player :pos :x))
                                        (-> state :world :player :pos :y)
                                        "\\"
                                        :white :black)
      :fishing-right (put-string screen :ui (inc (-> state :world :player :pos :x))
                                        (-> state :world :player :pos :y)
                                        "/"
                                        :white :black)
      :fishing-up    (put-string screen :ui (-> state :world :player :pos :x)
                                        (dec (-> state :world :player :pos :y))
                                        "/"
                                        :white :black)
      :fishing-down  (put-string screen :ui (-> state :world :player :pos :x)
                                        (inc (-> state :world :player :pos :y))
                                        "\\"
                                        :white :black)
      nil)
    ;; draw ranged-attack line
    (when (contains? #{:select-ranged-target :select-throw-target} (current-state state))
      (let [[target-sx
             target-sy] (case (current-state state)
                          :select-ranged-target
                            (let [target-ranged-index (get-in state [:world :target-ranged-index])
                                  target-ranged-pos-coll (get-in state [:world :target-ranged-pos-coll])
                                  target-pos             (nth target-ranged-pos-coll target-ranged-index)]
                              (log/debug "target-ranged-index" target-ranged-index)
                              (log/debug "target-ranged-pos-coll" (get-in state [:world :target-ranged-pos-coll]))
                              (log/debug "target-pos" target-pos)
                              (rv/world-xy->screen-xy state (rc/pos->xy target-pos)))
                          :select-throw-target
                            (rc/pos->xy (rv/get-cursor-pos state)))]
        (log/debug "target-sx" target-sx "target-y" target-sy)
        (doseq [[sx sy] (rlos/line-segment-fast-without-endpoints (rv/world-xy->screen-xy state [player-x player-y])
                                                                  [target-sx target-sy])]
            (put-string screen :ui sx sy "\u25CF" :green :black))))
      
    ;; draw npcs
    (let [place-npcs (npcs-in-viewport state)
          ;_ (log/debug "place-npcs" place-npcs)
          pos (-> state :world :player :pos)
          get-cell (memoize (fn [x y] (get-cell state x y)))
          place-id (rw/current-place-id state)]
      (doall (map (fn [npc]
                    (let [x         (-> npc :pos :x)
                          y         (-> npc :pos :y)
                          vx        (- x (if place-id 0 (-> state :world :viewport :pos :x)))
                          vy        (- y (if place-id 0 (-> state :world :viewport :pos :y)))
                          targeted? (when (= (current-state state) :select-ranged-target)
                                      (let [target-ranged-index (get-in state [:world :target-ranged-index])
                                            target-ranged-pos-coll (get-in state [:world :target-ranged-pos-coll])
                                            target-pos             (nth target-ranged-pos-coll target-ranged-index)]
                                        (= target-pos (get npc :pos))))
                          t       (rw/get-time state)
                          visible (and (not (farther-than?
                                              pos
                                              {:x x :y y}
                                              8))
                                       (= (get (rw/get-cell state x y) :discovered) t))]
                      ;(log/debug "npc@" x y "visible?" visible)
                      (when visible
                        (apply put-string screen
                                          :features
                                            vx
                                            vy
                                            (map (fn [f v] (f v))
                                              [str identity identity]
                                              (rcolor/target-tint-npc
                                                (rcolor/night-tint-npc
                                                  (rcolor/color-bloodied-char 
                                                    (< current-time (get npc :bloodied 0))
                                                    (case (get npc :race)
                                                      :rat             [\r]
                                                      :spider          [\S]
                                                      :scorpion        [\u03C2] ;;ς
                                                      :snake           [\u00A7] ;;§
                                                      :bat             [\B]
                                                      :boar            [\b :brown :black]
                                                      :gecko           [\g :green :black]
                                                      :monkey          [\y :orange :black]
                                                      :bird            [\a :red :black]
                                                      :centipede       [\c :red :black]
                                                      :turtle          [\t :green :black]
                                                      :red-frog        [\u03B1 :red :black] ;;α
                                                      :orange-frog     [\u03B1 :orange :black] ;;α
                                                      :yellow-frog     [\u03B1 :yellow :black] ;;α
                                                      :green-frog      [\u03B1 :green :black] ;;α
                                                      :blue-frog       [\u03B1 :blue :black] ;;α
                                                      :purple-frog     [\u03B1 :purple :black] ;;α
                                                      :parrot          [\p :red :black]
                                                      :shark           [\u039B] ;;Λ
                                                      :fish            [\f]
                                                      :octopus         [\# :orange :black]
                                                      :sea-snake       [\u00A7]
                                                      :clam            [\c]
                                                      :urchin          [\u :purple :black]
                                                      :squid           [\q :orange :black]
                                                      :crocodile       [\l :green :black]
                                                      :mosquito        [\m]
                                                      :mongoose        [\r :brown :black]
                                                      :tarantula       [\s :brown :black]
                                                      :monitor-lizard  [\l :gray :black]
                                                      :komodo-dragon   [\l :dark-green :black]
                                                      :cobra           [\u00A7] ;;§
                                                      :puffer-fish     [\f :yellow :black]
                                                      :crab            [\c :orange :black]
                                                      :hermit-crab     [\c :yellow :black]
                                                      :electric-eel    [\e :brown :black]
                                                      :jellyfish       [\j]
                                                      ;; pirate ship npc
                                                      :giant-rat       [\R]
                                                      :eel             [\e]
                                                      :giant-lizard    [\L]
                                                      ;; ruined temple ncs
                                                      :giant-centipedge [\C]
                                                      :gorilla         [\M]
                                                      :giant-snake     [\u00A7 :green :black] ;;§
                                                      :human           [\@ (class->rgb (get npc :class)) :black]
                                                      [\@])) d) targeted?))))))
                   place-npcs)))
    (render-hud state)
    (log/info "current-state" (current-state state))
    (if-not (nil? (get-in state [:world :ui-hint]))
      ;; ui-hint
      (put-chars screen :ui (markup->chars 0 0 (get-in state [:world :ui-hint])))
      ;; draw log
      (let [current-time     (dec (rw/get-time state))
            log-idx          (get-in state [:world :log-idx] 0)
            num-logs         (count (filter #(= current-time (get % :time)) (get-in state [:world :log])))
            up-arrow-char    \u2191
            down-arrow-char  \u2193
            msg-above?       (< log-idx (dec num-logs))
            msg-below?       (pos? log-idx)
            up-arrow-color   (when msg-above?
                               (get (nth (reverse (get-in state [:world :log])) (inc log-idx)) :color))
            down-arrow-color (when msg-below?
                               (get (nth (reverse (get-in state [:world :log])) (dec log-idx)) :color))
            message          (if (zero? num-logs)
                               {:message "" :time 0 :color :black} 
                               (nth (reverse (get-in state [:world :log])) log-idx))
            darken-factor    (inc  (* (/ -1 5) (- current-time (message :time))))
            log-color        (rcolor/darken-rgb (rcolor/color->rgb (get message :color)) darken-factor)
            characters       (markup->chars 0 0 (format "%s%s %s" (if msg-above?
                                                                    (str "<color fg=\"highlight\">/</color>-<color fg=\"" (name up-arrow-color) "\">" up-arrow-char "</color>")
                                                                    "   ")
                                                                  (if msg-below?
                                                                    (str "<color fg=\"highlight\">*</color>-<color fg=\"" (name down-arrow-color) "\">" down-arrow-char "</color>")
                                                                    "   ")
                                                                  (if (get message :text)
                                                                    (str "<color fg=\"" (name (get message :color)) "\">" (get message :text) "</color>")
                                                                    "")))]
        (log/info "current-time" current-time)
        (log/info "num-log-msgs" num-logs)
        (log/info "message" message)
        (put-chars screen :ui characters)
        (render-traps state)))
    (case (current-state state)
      :pickup-selection     (render-pick-up-selection state)
      :inventory            (render-inventory state)
      :abilities            (render-abilities state)
      :player-stats         (render-player-stats state)
      :gain-level           (render-ability-choices state)
      :action-select        (render-action-choices state)
      :describe             (render-describe state)
      :apply                (render-apply state)
      :apply-item-inventory
                            (render-apply-to state)
      :quaff-inventory
                            (render-quaff-inventory state)
      :magic                (render-magic state)
      :drop                 (render-drop state)
      :describe-inventory   (render-describe-inventory state)
      :throw-inventory      (render-throw-inventory state)
      :eat                  (render-eat state)
      :quests               (render-quests state)
      :craft                (render-craft state)
      :craft-weapon         (render-craft-weapon state)
      :craft-survival       (render-craft-survival state)
      :craft-shelter        (render-craft-shelter state)
      :craft-transportation (render-craft-transportation state)
      :wield                (render-wield state)
      :wield-ranged         (render-wield-ranged state)
      :start-text           (render-start-text state)
      :popover              (render-popover state)
      :quaff-popover        (render-raw-popover state)
      :dead                 (render-dead-text state)
      :rescued              (render-rescued-text state)
      nil)
    (case (current-state state)
      :quit               (render-quit? state)
      :harvest            (render-harvest state)
      :dialog             (render-dialog state)
      :shopping           (render-shopping state)
      :buy                (render-buy state)
      :sell               (render-sell state)
      nil)
    ;; draw cursor
    (if-let [cursor-pos (-> state :world :cursor)]
      (move-cursor screen (cursor-pos :x) (cursor-pos :y))
      (move-cursor screen -1 -1))
    (refresh screen)))
    ;;(log/debug "end-render")))

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

(defn render-cell [cell wx wy current-time]
  {:post [(char? (get % :c))
          (vector? (get % :fg))
          (vector (get % :bg))]}
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
              (if (contains? #{:chest :artifact-chest} (get cell :type))
                [\■ :dark-beige :black]
                [(rutil/item->char (first cell-items))
                 (rutil/item->fg   (first cell-items))
                 :black])
              (case (cell :type)
               :floor           [\·]
               :open-door       [\-  :brown  :black #{:bold}]
               :close-door      [\+  :brown  :black #{:bold}]
               :corridor        [\#] 
               :down-stairs     [\>] 
               :up-stairs       [\<] 
               :fire            [\u2240 (if (= (cell :discovered) current-time)
                                           :fire
                                           :red) :black] ;; ≀ 
               :water           [\u2248 (if (= (cell :discovered) current-time)
                                           :water
                                           :blue) :black] ;; ≈ 
               :surf            [\~ (if (= (cell :discovered) current-time)
                                       :surf
                                       :light-blue) :black]
               :shallow-water   [\~ (if (= (cell :discovered) current-time)
                                       :shallow-water
                                       :light-blue) :black]
               :swamp           [\~ (if (= (cell :discovered) current-time)
                                       :swamp
                                       :light-blue) :black]
               :lava            [\~ (if (= (cell :discovered) current-time)
                                       :lava
                                       :light-blue) :black]
               :mountain        [\u2206 :gray :black] ;; ∆
               :sand            [\·  :beige      :black]
               :dirt            [\·  :brown      :black]
               :dune            [\u1d16  :light-brown :black] ;; ᴖ
               :rocky-shore     [\u1d16  :dark-gray  :black] ;; ᴖ
               :gravel          [\·  :gray       :black]
               :short-grass     [\·  :green      :black]
               :tall-grass      [\" :dark-green :black]
               :tree            [\T  :dark-green :black]
               :bamboo          [\u01c1 :light-green :black] ;; ∥ 
               :palisade        [\# :brown :black]
               :ramada          [\# :beige :black]
               :tarp-shelter    [\# :blue  :black]
               :lean-to         [\# :light-green :black]
               :campfire        [\^ :brown :black]
               :bamboo-water-collector
                                (if (< 10 (get cell :water 0))
                                  [\O :bamboo-water-collector :black]
                                  [\O])
               :solar-still
                                (if (< 10 (get cell :water 0))
                                  [\O :solar-still :black]
                                  [\O])
               :palm-tree       [\7  :dark-green :black]
               :fruit-tree      [\u2648  :light-green :black] ;; ♈
               :freshwater-hole (if (< 10 (get cell :water 0))
                                  [\~ :freshwater-hole :black]
                                  [\O])
               :saltwater-hole  (if (< 10 (get cell :water 0))
                                  [\~ :saltwater-hole :black]
                                  [\O])
               :dry-hole        [\O]
               ;; pirate ship cell types
               :bulkhead        [\◘ :brown :black]
               :wheel           [\○ :dark-brown :black]
               :bulkhead2       [\◘ :brown :black]
               :wooden-wall     [\# :ship-brown :black]
               :railing         [\# :ship-brown :black]
               :hammock-v       [\) :brown :black]
               :hammock-h       [\- :brown :black]
               :deck            [\· :dark-brown :black]
               :canon-breach    [\║ :gray :dark-brown]
               :tackle          [\º :brown :black]
               :canon           [\║ :gray :black]
               :grate           [\╬ :dark-beige :black]
               :table           [\╤ :ship-light-brown :black]
               :chair           [\╥ :ship-light-brown :black]
               :mast            [\╨ :ship-light-brown :black]
               :beam            [\═ :brown :black]
               :canon-truck-1   [\▄ :dark-brown :black]
               :locker          [\▌ :brown :black]
               :locker2         [\▐ :brown :black]
               :canon-truck-2   [\▀ :dark-brown :black]
               :ships-wheel     [\Φ :brown :black]
               :ladder          [\≡ :dark-beige :black]
               :porthole        [\° :brown :black]
               :chest           [\■ :ship-dark-brown :black]
               :artifact-chest  [\■ :dark-beige :black]
               ;; ruined temple cell types
               :vertical-wall   [\║ :temple-beige :black]
               :horizontal-wall [\═ :temple-beige :black]
               :vertical-wall-alt [\° :white :black]
               :horizontal-wall-alt [\° :white :black]
               :upper-left-1    [\╔ :temple-beige :black]
               :upper-right-1   [\╗ :temple-beige :black]
               :bottom-left-1   [\╚ :temple-beige :black]
               :bottom-right-1  [\╝ :temple-beige :black]
               :upper-left-2    [\◙ :temple-beige :black]
               :upper-right-2   [\◙ :temple-beige :black]
               :bottom-left-2   [\◙ :temple-beige :black]
               :bottom-right-2  [\◙ :temple-beige :black]
               :altar           [\┬ :white :black]
               :vine            [\⌠ :moss-green :black]
               :moss-corridor   [\# :moss-green :black]
               :moss-vertical-wall  [\║ :moss-green :black]
               :moss-horizontal-wall [\═ :moss-green :black]
               :moss-vertical-wall-alt [\° :white :black]
               :moss-horizontal-wall-alt [\° :white :black]
               :moss-upper-left-1 [\╔ :moss-green :black]
               :moss-upper-right-1 [\╗ :moss-green :black]
               :moss-bottom-left-1 [\╚ :moss-green :black]
               :moss-bottom-right-1 [\╝ :moss-green :black]
               :moss-upper-left-2 [\◙ :moss-green :black]
               :moss-upper-right-2 [\◙ :moss-green :black]
               :moss-bottom-left-2 [\◙ :moss-green :black]
               :moss-bottom-right-2 [\◙ :moss-green :black]
               :white-corridor  [\# :white :black]
               :white-vertical-wall   [\║ :white :black]
               :white-horizontal-wall [\═ :white :black]
               :white-vertical-wall-alt [\° :white :black]
               :white-horizontal-wall-alt [\° :white :black]
               :white-upper-left-1 [\╔ :white :black]
               :white-upper-right-1 [\╗ :white :black]
               :white-bottom-left-1 [\╚ :white :black]
               :white-bottom-right-1 [\╝ :white :black]
               :white-upper-left-2 [\◙ :white :black]
               :white-upper-right-2 [\◙ :white :black]
               :white-bottom-left-2 [\◙ :white :black]
               :white-bottom-right-2 [\◙ :white :black]
               :empty                [\space :black :black]
               :crushing-wall-trigger
                                 (if (get cell :trap-found)
                                   [\^]
                                   [\·])
               :wall-darts-trigger
                                 (if (get cell :trap-found)
                                   [\^]
                                   [\·])
               :poisonous-gas-trigger
                                 (if (get cell :trap-found)
                                   [\^]
                                   [\·])
               :spike-pit
                                 (if (get cell :trap-found)
                                   [\^]
                                   [\·])
               :snakes-trigger
                                 (if (get cell :trap-found)
                                   [\^]
                                   [\·])
          
               (do (log/info (format "unknown type: %s %s" (str (get cell :type)) (str cell)))
               [\?])))))
        ((apply? (not in-view?)
           (fn [{:keys [c fg bg]}] {:c c :fg (rcolor/rgb->mono fg) :bg (rcolor/rgb->mono bg)})))
        ((apply? (and in-view? harvestable?)
           (fn [{:keys [c fg bg]}] {:c c :fg bg :bg fg}))))
      {:c \  :fg [0 0 0 0] :bg [0 0 0 0]})))

(zc/def-component MapInViewport
  [this]
  (let [{:keys [cells current-time]} (zc/props this)]
    (zc/csx [:img {:width 80 :height 23}
                  (map-indexed (fn [y line]
                            (map-indexed (fn [x cell]
                              (render-cell cell x y current-time))
                              line))
                          cells)])))

(defn render-npc [npc current-time]
  (apply fill-put-string-color-style-defaults
    0 0
    (rcolor/color-bloodied-char 
      (< current-time (get npc :bloodied 0))
      (case (get npc :race)
        :rat             [\r]
        :spider          [\S]
        :scorpion        [\u03C2] ;;ς
        :snake           [\u00A7] ;;§
        :bat             [\B]
        :boar            [\b :brown :black]
        :gecko           [\g :green :black]
        :monkey          [\y :orange :black]
        :bird            [\a :red :black]
        :centipede       [\c :red :black]
        :turtle          [\t :green :black]
        :red-frog        [\u03B1 :red :black] ;;α
        :orange-frog     [\u03B1 :orange :black] ;;α
        :yellow-frog     [\u03B1 :yellow :black] ;;α
        :green-frog      [\u03B1 :green :black] ;;α
        :blue-frog       [\u03B1 :blue :black] ;;α
        :purple-frog     [\u03B1 :purple :black] ;;α
        :parrot          [\p :red :black]
        :shark           [\u039B] ;;Λ
        :fish            [\f]
        :octopus         [\# :orange :black]
        :sea-snake       [\u00A7]
        :clam            [\c]
        :urchin          [\u :purple :black]
        :squid           [\q :orange :black]
        :crocodile       [\l :green :black]
        :mosquito        [\m]
        :mongoose        [\r :brown :black]
        :tarantula       [\s :brown :black]
        :monitor-lizard  [\l :gray :black]
        :komodo-dragon   [\l :dark-green :black]
        :cobra           [\u00A7] ;;§
        :puffer-fish     [\f :yellow :black]
        :crab            [\c :orange :black]
        :hermit-crab     [\c :yellow :black]
        :electric-eel    [\e :brown :black]
        :jellyfish       [\j]
        ;; pirate ship npc
        :giant-rat       [\R]
        :eel             [\e]
        :giant-lizard    [\L]
        ;; ruined temple ncs
        :giant-centipedge [\C]
        :gorilla         [\M]
        :giant-snake     [\u00A7 :green :black] ;;§
        :human           [\@ :white :black]))))

(defn render-player [player current-time]
  (apply fill-put-string-color-style-defaults
    (rcolor/color-bloodied-char 
      (< current-time (get player :bloodied 0))
      ["@" :white :black])))
   ; TODO: draw raft? draw fishing pole?
        #_(if (contains? (set (map :id (get (first (player-cellxy state)) :items))) :raft)
          :brown
          :black)
        ;; if character is fishing, draw pole
        #_(condp = (current-state state)
          :fishing-left  (put-string screen :ui (dec (-> state :world :player :pos :x))
                                            (-> state :world :player :pos :y)
                                            "\\"
                                            :white :black)
          :fishing-right (put-string screen :ui (inc (-> state :world :player :pos :x))
                                            (-> state :world :player :pos :y)
                                            "/"
                                            :white :black)
          :fishing-up    (put-string screen :ui (-> state :world :player :pos :x)
                                            (dec (-> state :world :player :pos :y))
                                            "/"
                                            :white :black)
          :fishing-down  (put-string screen :ui (-> state :world :player :pos :x)
                                            (inc (-> state :world :player :pos :y))
                                            "\\"
                                            :white :black))
  
(zc/def-component CharactersInViewport
  [this]
  (let [{:keys [npcs player-pos player-bloodied vx vy current-time]} (zc/props this)]
    (zc/csx [:view {}
                   (reduce (fn [children npc]
                             (let [x         (-> npc :pos :x)
                                   y         (-> npc :pos :y)
                                   ;targeted? (when (= (current-state state) :select-ranged-target)
                                   ;            (let [target-ranged-index (get-in state [:world :target-ranged-index])
                                   ;                  target-ranged-pos-coll (get-in state [:world :target-ranged-pos-coll])
                                   ;                  target-pos             (nth target-ranged-pos-coll target-ranged-index)]
                                   ;              (= target-pos (get npc :pos))))
                                   ;t       (rw/get-time state)
                                   {:keys [c fg bg]} (render-npc npc current-time)]
                               ;(log/debug "npc@" x y "visible?" visible)
                               (conj children (zc/csx [:text {:style {:position :fixed
                                                                      :top (- y vy)
                                                                      :left (- x vx)
                                                                      :color fg
                                                                      :background-color bg}} [(str c)]]))))
                           ; Always render player
                           [(zc/csx [:text {:style {:position :fixed
                                                    :top (- (get player-pos :y) vy)
                                                    :left (- (get player-pos :x) vx)
                                                    :color (rcolor/color->rgb (if player-bloodied :dark-red :white))
                                                    :background-color (rcolor/color->rgb :black)}}
                                           ["@"]])]
                           npcs)])))

(zc/def-component CrushingWall
  [this]
  (let [{:keys [trap]} (zc/props this)]
    nil))
#_(defn render-crushing-wall
  [screen trap]
  (let [ch (case (get trap :direction)
             :up    \╧
             :down  \╤
             :left  \╢
             :right \╟)]
    (put-chars screen :features (for [[x y] (first (get trap :locations))]
                                  {:x x :y y :c ch :fg [90 90 90] :bg [0 0 0]}))))

(zc/def-component PoisonousGas
  [this]
  (let [{:keys [trap]} (zc/props this)]
    nil))
#_(defn render-poisonous-gas
  [screen state trap]
  (let [current-time (rw/get-time state)]
    (doseq [[x y] (keys (get trap :locations))]
      (when (= (get (rw/get-cell state x y) :discovered) current-time)
        (let [bg (rcolor/color->rgb (rand-nth [:beige :temple-beige :light-brown]))]
          (set-bg! screen :map x y bg))))))

(zc/def-component Traps
  [this]
  (let [{:keys [game-state]} (zc/props this)
        traps (rw/current-place-traps game-state)]
    (zc/csx [:view {}
      (map (fn [trap]
             (case (get trap :type)
               :crushing-wall
                 (zc/csx [CrushingWall trap])
               :poisonous-gas
                 (zc/csx [PoisonousGas trap])
               (zc/csx [:view {} []])))
            traps)])))

(zc/def-component CharacterFx
  [this]
  (let [{:keys [fx]} (zc/props this)
        {:keys [pos]} fx]
    (zc/csx [:view {:style {:position :absolute
                            :top (get pos :y)
                            :left (get pos :x)}} [
              [:text {} [(str (get fx :ch))]]]])))

(zc/def-component FX
  [this]
  (let [{:keys [fx]} (zc/props this)]
    (zc/csx [:view {}
      (map (fn [effect]
             (case (get fx :type)
               :character-fx
                 (zc/csx [CharacterFx effect])
               (zc/csx [:view {} []])))
            fx)])))

(zc/def-component MultiSelect
  [this]
  (let [default-props {:selected-hotkeys []
                       :items []
                       :disable? (fn [_] false)}
        {:keys [title selected-hotkeys items disable?]} (merge default-props (zc/props this))
        children (map (fn [item]
                        (zc/csx [:text {:style {:color (rcolor/color->rgb (if (or (not (disable? item))
                                                                                  (get item :applicable))
                                                                            :black
                                                                            :gray))
                                                :background-color (rcolor/color->rgb :white)}} [
                                  [Highlight {} [(str (or (item :hotkey)
                                                          \space))]]
                                  [:text {} [(format "%c%s%s %s %s"
                                               (if (contains? selected-hotkeys (item :hotkey))
                                                 \+
                                                 \-)
                                               (if (contains? item :count)
                                                 (format "%dx " (int (get item :count)))
                                                 "")
                                               (get item :name)
                                               (if (contains? item :utility)
                                                 (format "(%d%%)" (int (get item :utility)))
                                                 "")
                                               (cond
                                                 (contains? item :wielded)
                                                   "(wielded)"
                                                 (contains? item :wielded-ranged)
                                                   "(wielded ranged)"
                                                 (contains? item :worn)
                                                   "(worn)"
                                                 :else
                                                   ""))]]]]))
                      items)]
    (zc/csx [:view {} (concat [(zc/csx [:text {:style {:color (rcolor/color->rgb :black)}} [title]])
                               (zc/csx [:text {} [""]])]
                              children)])))

; Render the pickup item menu if the world state is `:pickup-selection`.
(zc/def-component PickupSelection
  [this]
  (let [{:keys [game-state]} (zc/props this)
        direction        (get-in game-state [:world :pickup-direction])
        {x :x y :y}      (rw/player-adjacent-pos game-state direction)
        cell             (get-cell game-state x y)
        cell-items       (or (cell :items) [])
        hotkeys          (-> game-state :world :remaining-hotkeys)
        selected-hotkeys (-> game-state :world :selected-hotkeys)
        items            (fill-missing (fn missing [item] (not (contains? item :hotkey)))
                                       (fn apply-hotkey [item hotkey] (assoc item :hotkey hotkey))
                                       hotkeys
                                       cell-items)]
   (zc/csx [:view {:style {:width 40
                           :height 20
                           :position :fixed
                           :left 40
                           :top 1
                           :padding 1
                           :background-color (rcolor/color->rgb :white)}} [
            [MultiSelect {:title "Pick up"
                          :items (concat (translate-identified-items game-state items)
                                         [{:name "All" :hotkey \space}])}]]])))

(zc/def-component Inventory
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-items (-> game-state :world :player :inventory)]
   (zc/csx [:view {:style {:width 40
                           :height 20
                           :position :fixed
                           :left 40
                           :top 1
                           :padding 1
                           :background-color (rcolor/color->rgb :white)}} [
            [MultiSelect {:title "Inventory"
                          :items (translate-identified-items game-state player-items)}]]])))

(zc/def-component Abilities
  [this]
  (let [{:keys [game-state]} (zc/props this)
        abilities (rp/player-abilities game-state)
        height (if (seq abilities)
                 (+ 3 (* 3 (count abilities)))
                 4)]
    (zc/csx [:view {:style {:width 43
                            :height height
                            :position :fixed
                            :left 0
                            :top 0
                            :background-color (rcolor/color->rgb :white)}} [
            (if (seq abilities)
              (zc/csx [zcui/Popup {} [
                        [:view {:style {:border 2}} [
                          [MultiSelect {:title "Abilities"
                                        :items abilities}]]]]])
               #_{:s (format "    %s" (get ability :description)) :fg :black :bg :white :style #{}}
               #_[{:s "" :fg :black :bg :white :style #{}}
                  {:s "Select hotkey or press <color fg=\"highlight\">Esc</color> to exit." :fg :black :bg :white :style #{}}]
              (zc/csx [zcui/Popup {} [
                [:view {} [
                  [:text {} ["No abilities."]]
                  [:text {} [""]]
                  [:text {} [
                    [:text {} ["Press "]]
                    [Highlight {} ["Esc "]]
                    [:text {} ["to exit."]]]]]]]]))]])))

; Render the player character stats  menu if the world state is `:player-stats`.
(zc/def-component PlayerStats
  [this]
  (let [{:keys [game-state]} (zc/props this)
        x             18
        y             5
        height        11
        player-name   (rp/get-player-attribute game-state :name)
        max-hp        (int (rp/player-max-hp game-state))
        level         (rp/player-level game-state)
        xp            (or (rp/xp-acc-for-next-level game-state) -9)
        xp-next-level (or (rp/xp-for-next-level game-state) -99)
        strength      (int (rp/get-player-attribute game-state :strength))
        dexterity     (rp/get-player-attribute game-state :dexterity)
        toughness     (rp/get-player-attribute game-state :toughness)]
    (zc/csx [zcui/Popup {:style {:top 5}} [
        [:text {} [(format "Name:      %s" player-name)]]
        [:text {} [(format "Level:     %d (%d/%d)" level xp xp-next-level)]]
        [:text {} [""]]
        [:text {} [(format "Max HP:    %d" max-hp )]]
        [:text {} [""]]
        [:text {} [(format "Strength:  %d" strength )]]
        [:text {} [(format "Dexterity: %d" dexterity )]]
        [:text {} [(format "Toughness: %d" toughness )]]
        [:text {} [""]]
        [:text {} [
          [:text {} ["Press "]]
          [Highlight {} ["Esc "]]
          [:text {} ["to exit."]]]]]])))

(zc/def-component AbilityChoices
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))
#_(defn render-ability-choices 
  "Render the player ability choice menu if the world state is `:gain-level`."
  [state]
  (let [screen (get state :screen)
        abilities (get-in state [:world :ability-choices])
        height (+ 3 (* 3 (count abilities)))]
    (render-list screen :ui 17 4 43 height
      (concat
        (mapcat
          (fn [ability]
            [{:s (format "<color fg=\"highlight\">%s</color> - %s" (get ability :hotkey) (get ability :name))
              :fg :black
              :bg :white
              :style #{}}
             {:s (format "    %s" (get ability :description)) :fg :black :bg :white :style #{}}
             {:s "" :fg :black :bg :white :style #{}}])
          abilities)
        [{:s "" :fg :black :bg :white :style #{}}
         {:s "Select hotkey." :fg :black :bg :white :style #{}}]))
    (render-rect-double-border screen 16 3 43 height :black :white)
    (put-string screen :ui 29 3 "Choose New Ability" :black :white)))


(zc/def-component ActionChoices
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

#_(defn render-action-choices
  "Render the player action choices menu if the world state is `:action-select`."
  [state]
  (let [screen (get state :screen)
        abilities (get-in state [:world :action-select])
        height (if (seq abilities)
                 (+ 3 (* 3 (count abilities)))
                 4)]  
    (render-list screen :ui 17 4 43 height
      (if (seq abilities)
        (concat
          (mapcat
            (fn [ability]
              [{:s (format "<color fg=\"highlight\">%s</color> - %s" (get ability :hotkey) (get ability :name))
                :fg :black
                :bg :white
                :style #{}}
               {:s "" :fg :black :bg :white :style #{}}])
            abilities)
          [{:s "" :fg :black :bg :white :style #{}}
           {:s "Select hotkey or press <color fg=\"highlight\">Esc</color> to exit." :fg :black :bg :white :style #{}}])
        [{:s "Nothing to do." :fg :black :bg :white :style #{}}
         {:s "" :fg :black :bg :white :style #{}}
         {:s "Press <color fg=\"highlight\">Esc</color> to exit." :fg :black :bg :white :style #{}}]))
        
    (render-rect-double-border screen 16 3 43 height :black :white)
    (put-string screen :ui 30 3 "Choose Action" :black :white)))


(zc/def-component Describe
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))
#_(defn render-describe
  "Render the describe info pane if the world state is `:describe`."
  [state]
  (let [{cursor-x :x
         cursor-y :y} (get-in state [:world :cursor])
        [x y]         (rv/viewport-xy state)
        [w _]         (rv/viewport-wh state)
        _             (println "cursor-x" cursor-x "w" w)
        position      (if (< cursor-x (/ w 2))
                        :right
                        :left)
        description   (rdesc/describe-cell-at-xy state (+ x cursor-x) (+ y cursor-y))]
  (render-text (state :screen) position "Look" (if (get-in state [:world :dev-mode])
                                                 (str description "[" (+ x cursor-x) " " (+ y cursor-y) "]"
                                                      (get-cell state (+ x cursor-x) (+ y cursor-y)))
                                                 description))))



(zc/def-component Apply
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-items (-> game-state :world :player :inventory)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Apply Inventory"
                           :items (translate-identified-items game-state player-items)}]]])))

(zc/def-component ApplyTo
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-items (-> game-state :world :player :inventory)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Apply To"
                           :items (translate-identified-items game-state player-items)}]]])))


(zc/def-component QuaffInventory
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Quaff"
                           :items (translate-identified-items game-state (filter ig/is-quaffable?
                                                                           (-> game-state :world :player :inventory)))}]]])))

(zc/def-component Drop
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-items (-> game-state :world :player :inventory)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Drop Inventory"
                           :items (translate-identified-items game-state player-items)}]]])))

(zc/def-component DescribeInventory
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-items (-> game-state :world :player :inventory)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Describe"
                           :items (translate-identified-items game-state player-items)}]]])))

(zc/def-component ThrowInventory
  [this]
  (let [{:keys [game-state]} (zc/props this)]
  (let [{:keys [game-state]} (zc/props this)
        player-items (-> game-state :world :player :inventory)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Throw"
                           :items (translate-identified-items game-state player-items)}]]]))))

(zc/def-component Eat
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx [:view {:style {:width 40
                            :height 20
                            :position :fixed
                            :left 40
                            :top 1
                            :padding 1
                            :background-color (rcolor/color->rgb :white)}} [
             [MultiSelect {:title "Eat Inventory"
                           :items (filter #(contains? % :hunger)
                                         (inventory-and-player-cell-items game-state))}]]])))

(zc/def-component Quests
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component Craft
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))
#_(defn render-craft
  "Render the craft menu if the world state is `:craft`."
  [state]
  (let [screen (state :screen)]
    (render-multi-select screen nil [] [{:name "Weapons" :hotkey \w}
                                        {:name "Survival" :hotkey \s}
                                        {:name "Shelter" :hotkey \c}
                                        {:name "Transportation" :hotkey \t}]
                                        30 6 20 5)
    (render-rect-single-border screen 29 5 20 5 :black :white)
    (put-string screen :ui 37 5 "Craft" :black :white)))


(zc/def-component CraftWeapon
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component CraftSurvival
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component CraftShelter
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component CraftTransportation
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component Wield
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-items (filter can-be-wielded? (-> game-state :world :player :inventory))]
   (zc/csx [:view {:style {:width 40
                           :height 20
                           :position :fixed
                           :left 40
                           :top 1
                           :padding 1
                           :background-color (rcolor/color->rgb :white)}} [
            [MultiSelect {:title "Wield"
                          :items (translate-identified-items game-state player-items)}]]])))

(zc/def-component WieldRanged
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component StartText
  [this]
  (let [{:keys [game-state]} (zc/props this)
        start-text (sg/start-text game-state)]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
			[:view {:style {:position :fixed
							:top 4
							:left 20}} [
              [:text {:style {:width 40}} [(str start-text)]]
              [:text {:style {:top 10}} [
                [:text {} ["Press "]]
                [Highlight {} ["any key "]]
                [:text {} ["to continue and "]]
                [Highlight {} ["? "]]
                [:text {} ["to view help."]]]]]]]]]]]])))

(zc/def-component Popover
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component RawPopover
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component DeadText
  [this]
  (let [{:keys [game-state]} (zc/props this)
        hp             (get-in game-state [:world :player :hp])
        hunger         (get-in game-state [:world :player :hunger])
        max-hunger     (get-in game-state [:world :player :max-hunger])
        thirst         (get-in game-state [:world :player :thirst])
        max-thirst     (get-in game-state [:world :player :max-thirst])
        will-to-live   (get-in game-state [:world :player :will-to-live])
        cause-of-death (or
                         (get-in game-state [:world :cause-of-death])
                         (format "%s"
                           (cond
                             (<= hp 0)             "massive injuries"
                             (> hunger max-hunger) "literally starving to death"
                             (> thirst max-thirst) "not drinking enough water"
                             (<= will-to-live 0)   "just giving up on life"
                             :else                 "mysterious causes")))]
      (zc/csx [zcui/Popup {:style {:position :fixed :top 10}} [
                [:view {:style {:border 2}} [
                  [:text {} ["You died."]]
                  [:text {} [(format "From %s" cause-of-death)]]
                  [:text {} [""]]
                  [:text {} [
                    [:text {} ["Press "]]
                    [Highlight {} ["space "]]
                    [:text {} ["to continue."]]]]]]]])))

(zc/def-component RescuedText
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component QuitPrompt
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx [zcui/Popup {:style {:position :fixed :top 10}} [
              [:text {} [
                [:text {} ["quit? ["]]
                [Highlight {} ["y"]]
                [:text {} ["/"]]
                [Highlight {} ["n"]]
                [:text {} ["]"]]]]]])))

(zc/def-component Harvest
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil))

(zc/def-component MapUI
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (case (current-state game-state)
      :pickup-selection     (zc/csx [PickupSelection {:game-state game-state}])
      :inventory            (zc/csx [Inventory {:game-state game-state}])
      :abilities            (zc/csx [Abilities {:game-state game-state}])
      :player-stats         (zc/csx [PlayerStats {:game-state game-state}])
      :gain-level           (zc/csx [AbilityChoices {:game-state game-state}])
      :action-select        (zc/csx [ActionChoices {:game-state game-state}])
      :describe             (zc/csx [Describe {:game-state game-state}])
      :apply                (zc/csx [Apply {:game-state game-state}])
      :apply-item-inventory
                            (zc/csx [ApplyTo {:game-state game-state}])
      :quaff-inventory
                            (zc/csx [QuaffInventory {:game-state game-state}])
      :drop                 (zc/csx [Drop {:game-state game-state}])
      :describe-inventory   (zc/csx [DescribeInventory {:game-state game-state}])
      :throw-inventory      (zc/csx [ThrowInventory {:game-state game-state}])
      :eat                  (zc/csx [Eat {:game-state game-state}])
      :quests               (zc/csx [Quests {:game-state game-state}])
      :craft                (zc/csx [Craft {:game-state game-state}])
      :craft-weapon         (zc/csx [CraftWeapon {:game-state game-state}])
      :craft-survival       (zc/csx [CraftSurvival {:game-state game-state}])
      :craft-shelter        (zc/csx [CraftShelter {:game-state game-state}])
      :craft-transportation (zc/csx [CraftTransportation {:game-state game-state}])
      :wield                (zc/csx [Wield {:game-state game-state}])
      :wield-ranged         (zc/csx [WieldRanged {:game-state game-state}])
      :start-text           (zc/csx [StartText {:game-state game-state}])
      :popover              (zc/csx [Popover {:game-state game-state}])
      :quaff-popover        (zc/csx [RawPopover {:game-state game-state}])
      :dead                 (zc/csx [DeadText {:game-state game-state}])
      :rescued              (zc/csx [RescuedText {:game-state game-state}])
      :quit?                (zc/csx [QuitPrompt {:game-state game-state}])
      :harvest              (zc/csx [Harvest {:game-state game-state}])
      (zc/csx [:view {} []]))
    ;; draw cursor
    #_(if-let [cursor-pos (-> state :world :cursor)]
      (move-cursor screen (cursor-pos :x) (cursor-pos :y))
      (move-cursor screen -1 -1))))

(zc/def-component Message
  [this]
  ;; draw log
  (let [{:keys [game-state]} (zc/props this)
        current-time     (dec (rw/get-time game-state))
        log-idx          (get-in game-state [:world :log-idx] 0)
        num-logs         (count (filter #(= current-time (get % :time)) (get-in game-state [:world :log])))
        up-arrow-char    "\u2191"
        down-arrow-char  "\u2193"
        msg-above?       (< log-idx (dec num-logs))
        msg-below?       (pos? log-idx)
        up-arrow-color   (if msg-above?
                           (rcolor/color->rgb (get (nth (reverse (get-in game-state [:world :log])) (inc log-idx)) :color))
                           (rcolor/color->rgb :white))
        down-arrow-color (if msg-below?
                           (rcolor/color->rgb (get (nth (reverse (get-in game-state [:world :log])) (dec log-idx)) :color))
                           (rcolor/color->rgb :white))
        message          (if (zero? num-logs)
                           {:message "" :time 0 :color :black} 
                           (nth (reverse (get-in game-state [:world :log])) log-idx))
        darken-factor    (inc  (* (/ -1 5) (- current-time (message :time))))
        log-color        (rcolor/darken-rgb (rcolor/color->rgb (get message :color)) darken-factor)]
    (zc/csx
        [:view {} [
          [:text {:style {:position :fixed :top -4 :left 0}} [
            (if msg-above?
              (zc/csx [:text {} [
                        [Highlight {} ["/"]]
                        [:text {} ["-"]]
                        [:text {:style {:color up-arrow-color}} [up-arrow-char]]]])
              (zc/csx [:text {} ["   "]]))
            (if msg-below?
              (zc/csx [:text {} [
                        [Highlight {} ["*"]]
                        [:text {} ["-"]]
                        [:text {:style {:color down-arrow-color}} [down-arrow-char]]]])
              (zc/csx [:text {} ["   "]]))
            (if (get message :text)
              (zc/csx [:text {:style {:color (rcolor/color->rgb :white #_(name (get message :color)))}} [(str (get message :text))]])
              (zc/csx [:text {} [""]]))]]]])))

(zc/def-component Map
  [this]
  (let [{:keys [game-state]} (zc/props this)
        [columns rows] [80 24]
        current-time (get-in game-state [:world :time])
        {{player-x :x player-y :y :as player-pos}
         :pos :as player}                           (rp/get-player game-state)
        d                                           (rlos/sight-distance game-state)
        cells                                       (rv/cells-in-viewport game-state)
        visible-npcs                                (filter (fn [npc]
                                                              (let [{npc-x :x npc-y :y :as npc-pos} (get npc :pos)]
                                                                (and (not (farther-than?
                                                                             player-pos
                                                                             (get npc :pos)
                                                                             8))
                                                                     (= (get (rw/get-cell game-state npc-x npc-y) :discovered)
                                                                        current-time))))
                                                            (npcs-in-viewport game-state))
        place-id                                    (rw/current-place-id game-state)
        vx                                          (if place-id 0 (-> game-state :world :viewport :pos :x))
        vy                                          (if place-id 0 (-> game-state :world :viewport :pos :y))
        lantern-on                                  (when-let [lantern (rp/inventory-id->item game-state :lantern)]
                                                      (get lantern game-state :off))]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :map} [
            [:view {:style {:top 0 :left 0}} [
              [MapInViewport {:cells cells :current-time current-time}]]]]]
		  [:layer {:id :features} [
            [:view {:style {:top 0 :left 0}} [
              [CharactersInViewport {:npcs visible-npcs
                                     :player-pos (rp/player-pos game-state)
                                     :player-bloodied (get player :bloodied)
                                     :vx vx
                                     :vy vy
                                     :current-time current-time}]]]
            [:view {:style {:top 0 :left 0}} [
              [Traps {:traps visible-npcs
                                     :player-pos (rp/player-pos game-state)
                                     :player-bloodied (get player :bloodied)
                                     :vx vx
                                     :vy vy
                                     :current-time current-time}]]]
            [:view {:style {:top 0 :left 0}} [
              [FX {:fx (rfx/fx game-state)}]]]]]
          [:layer {:id :ui} [
            [Hud {:game-state game-state}]
            [Message {:game-state game-state}]
            [MapUI {:game-state game-state}]]]]]]])))

#_(defn render-enter-name [state]
  (let [screen (state :screen)
        player-name (get-in state [:world :player :name])]
    (clear (state :screen))
    (put-string screen :ui 30 5 "Robinson")
    (put-string screen :ui 20 7 "Name:___________________")
    (put-string screen :ui 25 7 (str player-name "\u2592"))
    (refresh screen)))

(def loading-index (atom 0))
(def loading-tidbits
  ["rat swarm"
   "poisonous frog"
   "coconut"
   "palm tree"
   "human blood"
   "monkey"
   "island"
   "terrain"
   "lava"
   "volcano"
   "abandoned hut"
   "lair"
   "pirate"
   "clam"
   "leaf"
   "ancient temple"
   "beaches"
   "crab legs. (8) check."
   "cannibal"
   "beeshive"
   "monkey ambush"
   "cave"
   "spider tunnel"
   "insane castaway"
   "watering hole"
   "other survivor"
   "goat"
   "hurricane"])
(def tidbit-freqs (atom (zipmap (range (count loading-tidbits)) (repeat 0))))

#_(defn render-loading [state]
  (let [screen     (state :screen)
        n          (->> (sort-by val @tidbit-freqs)
                        (partition-by val)
                        first
                        rand-nth
                        first)]
    (swap! tidbit-freqs (fn [freqs] (update freqs n inc)))
    (clear (state :screen))
    (put-string screen :ui 30 12 (format "Generating %s..." (nth loading-tidbits n)))
    (put-string screen :ui 40 18 (nth ["/" "-" "\\" "|"] (mod (swap! loading-index inc) 4)))
    (refresh screen)))

#_(defn render-connection-failed [state]
  (let [screen     (state :screen)]
    (clear screen)
    (put-string screen 30 12 :ui "Connection failed")
          (put-chars (state :screen) :ui (markup->chars 30 22 "Play again? [<color fg=\"highlight\">y</color>/<color fg=\"highlight\">n</color>]"))
    (refresh screen)))

(def connecting-index (atom 0))
#_(defn render-connecting [state]
  (let [screen     (state :screen)]
    (clear screen)
    (put-string screen 30 12 :ui (format "Connecting%s" (apply str (repeat (mod (swap! connecting-index inc) 4) "."))))
    (refresh screen)))

(defn cp437->unicode
  [c]
  (case (int c)
      0 \ 
      3 \u2665
    179 \u2502
    219 \u2588
    220 \u2584
    249 "·"
    250 \u00B7
    c))

#_(defn render-histogram
  [state x y title value histogram]
  (let [group-size (get histogram "group-size")]
  ;; render x-axis
  (doseq [i (range 8)]
    (put-chars (state :screen) :ui [{:c (get single-border :horizontal) :x (+ x i 1) :y (+ y 8) :fg (rcolor/color->rgb :white) :bg (rcolor/color->rgb :black) :style #{}}]))
  ;; put caret
  (put-chars (state :screen) :ui [{:c \^ :x (+ x (int (/ value group-size)) 1) :y (+ y 8) :fg (rcolor/color->rgb :highlight) :bg (rcolor/color->rgb :black) :style #{}}])
  ;; render y-axis
  (doseq [i (range 7)]
    (put-chars (state :screen) :ui [{:c (get single-border :vertical)  :x x :y (+ y i 1) :fg (rcolor/color->rgb :white) :bg (rcolor/color->rgb :black) :style #{}}]))
  (put-chars (state :screen) :ui [{:c (get single-border :bottom-left)  :x x :y (+ y 8) :fg (rcolor/color->rgb :white) :bg (rcolor/color->rgb :black) :style #{}}])
  ;; print title
  (put-chars (state :screen) :ui (markup->chars x y title))
  ;; print bars
  (let [max-count (reduce (fn [m data](max m (get data "count"))) 0 (get histogram "data"))]
    (log/debug "data" histogram)
    (log/debug "max-count" max-count)
    (log/debug "group-size" group-size)
    ;; for each bar
    (doseq [data (get histogram "data")
            :when (get data "group")
            :let [group (get data "group")
                  x (+ x 1 (/ group group-size))
                  fg (rcolor/color->rgb
                       (if (< group (inc value) (+ group group-size 1))
                         :highlight
                         :dark-gray))]]
      ;; for each step in the bar
      (let [from (int (- (+ y 7) (* 7 (/ (get data "count") max-count))))
            to   (+ y 7)]
        (log/debug "from" from "to" to "x" x)
      (doseq [y (range from to)]
        (put-chars (state :screen) :ui [{:c (cp437->unicode 219)#_"*" :x x :y (inc y) :fg fg :bg (rcolor/color->rgb :black) :style #{}}])))))))

#_(defn render-share-score
  [state]
  (let [score      (get state :last-score)
        top-scores (get state :top-scores)]
    (clear (state :screen))
    ;; Title
    (put-string (state :screen) :ui 10 1 "Top scores")
    ;; highscore list
    (doseq [[idx score] (map-indexed vector (take 10 (concat top-scores (repeat nil))))]
      (if score
        (let [player-name    (get score "player-name" "?name?")
              points         (get score "points" 0)
              days-survived  (get score :days-survived 0 )
              turns-survived (get score :turns-survived 0 )]
          ;;(put-string (state :screen) 30 (+ idx 3) (format "%d. %s survived for %d %s. (%d points)"
;;                                                                                       (inc idx)
;;                                                                                       player-name days-survived
;;                                                                                       (if (> 1 days-survived)
;;                                                                                         "days"
;;                                                                                          "day")
;;                                                                                      points)))
          (put-string (state :screen) :ui 1 (+ idx 3) (format "%2d.%-20s (%d points)" (inc idx) player-name points) (if (and (= player-name (get-in state [:world :player :name]))
                                                                                                                         (= points (get state :points)))
                                                                                                                  :highlight
                                                                                                                  :white)
                                                                                                                :black))
        (put-string (state :screen) :ui 1 (+ idx 3) "...")))
    ;; Performance
    (put-string (state :screen) :ui 50 1 "Performance")
    (render-histogram state 45 3  "Points"        (get state :points)                                                                  (get state :point-data))
    (render-histogram state 61 3  "Turns"         (rw/get-time state)                                                                  (get state :time-data))
    (render-histogram state 45 13 "Kills"         (reduce + 0 (map second (get-in state [:world :player :stats :num-animals-killed]))) (get state :kills-data))
    (render-histogram state 61 13 "Items Crafted" (reduce + 0 (map second (get-in state [:world :player :stats :num-items-crafted])))  (get state :crafted-data))
    (put-chars (state :screen) :ui (markup->chars 45 22 "Your performance - <color fg=\"highlight\">^</color>"))
    (put-chars (state :screen) :ui (markup->chars 7 22 "Play again? [<color fg=\"highlight\">y</color>/<color fg=\"highlight\">n</color>]"))
    (refresh (state :screen))))

(defn rockpick->render-map
  [layers]
  (let [layer (first layers)]
    (log/info "layer" (vec layer))
    (reduce concat
            []
            (map-indexed (fn [y line]
                           (log/info "line" (vec line))
                           (map-indexed (fn [x tile]
                                          ;(log/info "tile" tile x y)
                                          {:c (cp437->unicode (get tile :ch))
                                           :fg [(get-in tile [:fg :r])
                                                (get-in tile [:fg :g])
                                                (get-in tile [:fg :b])]
                                           :bg [(get-in tile [:bg :r])
                                                (get-in tile [:bg :g])
                                                (get-in tile [:bg :b])]
                                           :x  x
                                           :y  y})
                                        line))
                         layer))))

(zc/def-component Start
  [this]
  (zc/csx
    [:terminal {} [
      [:group {:id :app} [
        [:layer {:id :map} [
          [zcui/Image {:src "/home/santos/src/robinson/images/robinson-mainmenu.jpg"}]]]
        [:layer {:id :ui} [
              [:view {:style {:color [255 255 255 255]
                              :background-color [0 0 0 0]
                              :position :fixed
                              :top 20
                              :left 30}} [
                [:text {} [
                  [:text {} ["Press "]]
                  [Highlight {}  ["space "]]
                  [:text {} ["to play"]]]]
                [:text {} [[Highlight {} ["c"]] [:text  {} [" - configure"]]]]]]]]]]]]))

(zc/def-component Configure
  [this]
  (let [{:keys [state]} (zc/props this)]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
			[:view {:style {:position :fixed
							:top 30
							:left 20}} [
			  [:text {} ["Configure"]]
			  [:text {} ["f - font"]]]]]]]]]])))

(zc/def-component ConfigureFont
  [this]
  (let [{:keys [state]} (zc/props this)
        font   (get-in state [:fonts (get state :new-font (get (rc/get-settings state) :font))])]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
			[:view {:style {:position-type :absolute
							:position-top 30
							:position-left 20}} [
			  [:text {} ["Configure Font"]]
			  [:text {} ["Font: " (get font :name)]]
			  [:text {} ["n - next font" (get font :name)]]
			  [:text {} ["p - previous font" (get font :name)]]
			  [:text {} ["s - save and apply" (get font :name)]]]]]]]]]])))

(zc/def-component EnterName
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-name (get-in game-state [:world :player :name])]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
			[:view {:style {:top 10
							:left 30
                            :width 80
                            :display :flex
                            :flex-direction :row}} [
              [:text {:style {:width 5}} ["Name:"]]
              [:view {:style {:position :relative} } [
			    [zcui/Input {:value player-name
                             :focused true}]]]]]]]]]]])))

(zc/def-component StartInventory
  [this]
  (let [{:keys [game-state]} (zc/props this)
        player-name      (get-in game-state [:world :player :name])
        selected-hotkeys (get-in game-state [:world :selected-hotkeys])
        start-inventory  (sg/start-inventory)]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
            [:view {:style {:align-items :center
                            :justify-content :center
                            :position :fixed
                            :top 2
                            :left 0}} [
              [SelectItemList {:title "Choose up to three things to take with you:"
                               :selected-hotkeys selected-hotkeys
                               :use-applicable true
                               :items start-inventory}]]]]]]]]])))

(zc/def-component Loading
  [this]
  (let [{:keys [state]} (zc/props this)]
    (zc/csx 
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
            [:view {:style {:position :fixed
                            :top 10
                            :left 36}} [
              [:text {} ["Loading..."]]]]]]]]]])))

(zc/def-component Connecting
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil
))

(zc/def-component ConnectionFailed
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil
))

(zc/def-component GameOverDead
  [this]
  (let [{:keys [player cause-of-death madlib days-survived turns-survived points]} (zc/props this)
        player-name    (get player :name)
        hp             (get player :hp)
        hunger         (get player :hunger)
        max-hunger     (get player :max-hunger)
        thirst         (get player :thirst)
        max-thirst     (get player :max-thirst)
        will-to-live   (get player :will-to-live)
        cause-of-death (or
                         cause-of-death
                         (cond
                           (<= hp 0)             "massive injuries"
                           (> hunger max-hunger) "literall starving to death"
                           (> thirst max-thirst) "not drinking enough water"
                           (<= will-to-live 0)   "just giving up on life"
                           :else                 "mysterious causes"))]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
            [:view {:style {:left 10}} [
              [:text {} [(format "%s: %s" player-name madlib)]]
              [:text {} [(format "%s: %s" player-name madlib)]]
              [:text {} [""]]
              [:text {} [(format "Points: %s." points)]]
              [:text {} [(format "Survived for %d %s. (%d turns)"
                                   days-survived
                                   (if (> 1 days-survived) "days" "day")
                                   turns-survived)]]
              [:text {} [(format "Died from %s" cause-of-death)]]
              [:text {} [""]]
              [:text {} ["Inventory:"]]
              [:view {} 
                (map-indexed
                  (fn [idx item]
                    (zc/csx [:text {} [(format "%s%s" (if (pos? (get item :count 0))
                                                        (format "%dx " (get item :count))
                                                        "")
                                                      (item :name))]]))
                  (get player :inventory))]]]
              [:text {} [""]]
              [:view {:style {:left 10}} [
                [:text {} [[:text {} ["Play again? ["]]
                           [Highlight {} ["y"]]
                           [:text {} ["/"]]
                           [Highlight {} ["n"]]
                           [:text {} ["] "]]
                           [Highlight {} ["space "]]
                           [:text {} ["- share and compare with other players"]]]]]]]]]]]])))

(zc/def-component GameOverRescued
  [this]
  (let [{:keys [game-state]} (zc/props this)
        rescue-mode (rendgame/rescue-mode game-state)]
    ;; Title
    ;(put-string (game-state :screen) :ui 10 1 (format "%s: %s." player-name madlib))
    ;(put-string (game-state :screen) :ui 18 2 (format "Rescued by %s after surviving for %d days." rescue-mode days-survived))
    ;(put-string (game-state :screen) :ui 10 3 (format "Points: %s." points))
    ;(put-string (game-state :screen) :ui 10 4 "Inventory:")
    ;(doall (map-indexed
    ;  (fn [idx item] (put-string (game-state :screen) :ui 18 (+ idx 5) (item :name)))
    ;  (-> game-state :world :player :inventory)))
    ;(put-string (game-state :screen) :ui 10 22 "Play again? [yn]")))
))

(zc/def-component GameOver
  [this]
  (let [{:keys [game-state]} (zc/props this)
        cur-state      (current-state game-state)
        points         (rs/state->points game-state)
        turns-survived  (get-time game-state)
        turns-per-day   (count (get-in game-state [:data :atmo]))
        days-survived   (int (/ turns-survived turns-per-day))
        player-name     (get-in game-state [:world :player :name])
        madlib          (gen-end-madlib game-state)]
    (case cur-state
      :game-over-dead
        (zc/csx [GameOverDead {:player (get-in game-state [:world :player])
                               :days-survived days-survived
                               :turns-survived turns-survived
                               :points points
                               :cause-of-death (get-in game-state [:world :cause-of-death])
                               :madlib madlib}])
      :game-over-rescued
        (zc/csx [GameOverRescued {:game-state game-state}]))))

(zc/def-component ShareScore
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    nil
))

(zc/def-component RexPaintFromData
  [this]
  (let [{:keys [game-state data-key]} (zc/props this)
        data (get-in game-state [:data data-key])]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
            [:view {} [
              [zcui/RexPaintImage {:data data}]]]]]]]]])))

(zc/def-component KeyboardControlsHelp
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx
	  [RexPaintFromData {:game-state game-state :data-key :keyboard-controls}])))


(zc/def-component UIHelp
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx
	  [RexPaintFromData {:game-state game-state :data-key :ui}])))

(zc/def-component GameplayHelp
  [this]
  (let [{:keys [game-state]} (zc/props this)]
    (zc/csx
	  [RexPaintFromData {:game-state game-state :data-key :gameplay}])))

(zc/def-component FullLog
  [this]
  (let [{:keys [game-state]} (zc/props this)
        log (get-in game-state [:world :log])]
    (zc/csx
	  [:terminal {} [
		[:group {:id :app} [
		  [:layer {:id :ui} [
            [:view {}
              (for [{:keys [text color]} (reverse (take 23 log))]
                (zc/csx [:text {:style {:color (rcolor/color->rgb color)}} [(str text)]]))]]]]]]])))

(zc/def-component Robinson
  [this]
  (let [game-state (get (zc/props this) :game-state)]
    (assert (not (nil? game-state)))
    (log/info "render current-state" (current-state game-state))
    (cond
      (= (current-state game-state) :start)
        (zc/csx [Start {}])
      (= (current-state game-state) :configure)
        (zc/csx [Configure {:game-state game-state}])
      (= (current-state game-state) :configure-font)
        (zc/csx [ConfigureFont {:game-state game-state}])
      (= (current-state game-state) :enter-name)
        (zc/csx [EnterName {:game-state game-state}])
      (= (current-state game-state) :start-inventory)
        (zc/csx [StartInventory {:game-state game-state}])
      (= (current-state game-state) :loading)
        (zc/csx [Loading {:game-state game-state}])
      (= (current-state game-state) :connecting)
        (zc/csx [Connecting {:game-state game-state}])
      (= (current-state game-state) :connection-failed)
        (zc/csx [ConnectionFailed {:game-state game-state}])
      (= (current-state game-state) :start-text)
        (zc/csx [StartText {:game-state game-state}])
      ;  (render-start-text game-state)
      ;; Is player dead?
      (contains? #{:game-over-dead :game-over-rescued} (current-state game-state))
        ;; Render game over
        (zc/csx [GameOver {:game-state game-state}])
      (= (get-in game-state [:world :current-state]) :share-score)
        (zc/csx [ShareScore {:game-state game-state}])
      (= (get-in game-state [:world :current-state]) :help-controls)
        (zc/csx [KeyboardControlsHelp {:game-state game-state}])
      (= (get-in game-state [:world :current-state]) :help-ui)
        (zc/csx [UIHelp {:game-state game-state}])
      (= (get-in game-state [:world :current-state]) :help-gameplay)
        (zc/csx [GameplayHelp {:game-state game-state}])
      (= (get-in game-state [:world :current-state]) :log)
        (zc/csx [FullLog {:game-state game-state}])
      :else
        (zc/csx [Map {:game-state game-state}]))))
)