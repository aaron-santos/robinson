;; Utility functions and functions for manipulating state
(ns robinson.worldgen
  (:use 
        robinson.viewport
        robinson.world
        robinson.player
        [robinson.lineofsight :exclude [-main]]
        robinson.npc
        clojure.contrib.core)
  (:require 
            [robinson.common :as rc]
            [robinson.random :as rr]
            [robinson.noise :as rn]
            [robinson.prism :as rp]
            [robinson.itemgen :as ig]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.data.generators :as dg]
            [taoensso.timbre :as timbre]
            [taoensso.timbre :as timbre]
            [pallet.thread-expr :as tx]
            [taoensso.nippy :as nippy])
  (:import [java.io DataInputStream DataOutputStream]))



(timbre/refer-timbre)

(defn rand-xy-in-circle
  [x y max-r]
  (let [theta (rand-nth (range (* 2 Math/PI)))
        r     (rand-nth (range max-r))]
    [(int (+ x (* r (Math/cos theta)))) (int (+ y (* r (Math/sin theta))))]))

(defn line-segments [x1 y1 x2 y2]
  #_(println "line-segments" x1 y1 x2 y2)
  (if (not (rc/farther-than? (rc/xy->pos x1 y1) (rc/xy->pos x2 y2) 5))
    ;; too short to split, return direct line betweem two points.
    [[x1 y1] [x2 y2]]
    ;; subdivide
    (let [mx       (/ (+ x1 x2) 2)
          my       (/ (+ y1 y2) 2)
          r        (min 20 (rc/distance (rc/xy->pos x1 y1) (rc/xy->pos mx my)))
          [rmx rmy] (rand-xy-in-circle mx my (dec r))]
      (concat (line-segments x1 y1 rmx rmy)
              (line-segments rmx rmy x2 y2)))))

(defn add-extras
  "Adds extras to a place like items, and special cell types
   extras are in the format of `[[[x y] object] [[x y] object] &]`
   objects are cells with a type and maybe items `{:type :floor :items []}`"
  [place extras]
  ;; create a list of functions that can be applied to assoc extras, then create a composition of
  ;; so that setting can pass through each fn in turn.
  #_(debug "add-extras" place extras)
  (reduce (fn [place [[x y] & r]]
           (let [args (concat [[y x]] r)]
             #_(debug "assoc-in place" args)
             (apply assoc-in place args))) place extras))


(defn init-ocean
  []
  (let [max-x 80
        max-y 22]
    (add-extras
      (vec
        (map vec
          (partition max-x
            (for [y (range max-y)
                  x (range max-x)]
                {:type :water}))))
      [])))

(defn sample-tree [n x y]
  ((rp/coerce (rp/offset [-0.5 -0.5] (rp/scale 0.01 (rp/noise n)))) x y))

(defn sample-island
  [n x y]
  (let [c  ((rp/coerce (rp/scale 43.0 (rp/offset (rp/vnoise n) (rp/radius)))) x y)
        c1 ((rp/coerce (rp/offset [0.5 0.5] (rp/scale 0.6 (rp/snoise n)))) x y)
        c2 ((rp/coerce (rp/offset [-110.5 -640.5] (rp/scale 0.8 (rp/snoise n)))) x y)
        cgt #+clj (> (Math/abs c1) (Math/abs c2))
            #+cljs (> (.abs js/Math c1) (.abs js/Math c2))]
    (cond
      ;; interior biomes
      (> 0.55  c)
        (cond
          (and (pos? c1) (pos? c2) cgt)
          :jungle
          (and (pos? c1) (pos? c2))
          :heavy-forest
          (and (pos? c1) (neg? c2) cgt)
          :light-forest
          (and (pos? c1) (neg? c2))
          :bamboo-grove
          (and (neg? c1) (pos? c2) cgt)
          :meadow
          (and (neg? c1) (pos? c2))
          :rocky
          (and (neg? c1) (neg? c2) cgt)
          :swamp
          :else
          :dirt)
      ;; shore/yellow
      (> 0.6  c)
          :sand
      ;; surf/light blue
      (> 0.68 c)
        :surf
      ;; else ocean
      :else
        :ocean)))

(defn find-starting-pos [seed max-x max-y]
  (let [angle (dg/rand-nth (range (* 2 Math/PI)))
        radius (/ (min max-x max-y) 2)
        [x y]   [(* radius (Math/cos angle))
                 (* radius (Math/sin angle))]
        points  (line-segment [x y] [0 0])
        samples (take-nth 5 points)
        n       (rn/create-noise (rr/create-random seed))
        _ (info "samples" samples)
        non-water-samples (remove
          (fn [[x y]]
            (let [s (sample-island n x y)]
              (info "sample" x y s)
              (or (= s :surf)
                  (= s :ocean))))
          samples)
        [sx sy] (first non-water-samples)]
    (rc/xy->pos sx sy)))

(defn find-lava-terminal-pos [seed starting-pos max-x max-y]
  {:pre [(rc/has-keys? starting-pos [:x :y])]
   :post [(rc/has-keys? % [:x :y])]}
  (let [{x :x y :y}  starting-pos
        _ (info "seed" seed "starting-pos" starting-pos "max-x" max-x "max-y" max-y)
        player-angle #+clj  (Math/atan2 (- x (/ max-x 2)) (- y (/ max-y 2)))
                     #+cljs (.atan2 js/Math (- x (/ max-x 2)) (- y (/ max-y 2)))
        angle        (- player-angle 0.03)
        radius       (/ (min max-x max-y) 2)
        [x y]        [(* radius #+clj  (Math/cos angle)
                                #+cljs (.cos js/Math angle))
                      (* radius #+clj  (Math/sin angle)
                                #+cljs (.sin js/Math angle))]
        points       (line-segment [x y] [0 0])
        samples       points
        n            (rn/create-noise (rr/create-random seed))
        non-water-samples (remove
          (fn [[x y]]
            (let [s (sample-island n x y)]
            (or (= s :surf)
                (= s :ocean))))
          samples)
        [sx sy] (first non-water-samples)]
    (rc/xy->pos sx sy)))

(defn init-island
  "Create an island block. `x` and `y` denote the coordinates of the upper left cell in the block."
  [state x y width height]
  (info "init-island" x y width height)
  (let [seed                  (get-in state [:world :seed])
        n                     (rn/create-noise (rr/create-random seed))
        volcano-pos           (get-in state [:world :volcano-pos])
        lava-xys              (get-in state [:world :lava-points])]
    (vec
     (pmap vec
       (partition width
         (map (fn [[x y]]
            (let [biome     (sample-island n x y)
                  t         (sample-tree n x y)
                  ;_         (info biome t)
                  cell-type (case biome
                              :ocean         {:type :water}
                              :surf          {:type :surf}
                              :sand          {:type :sand}
                              :dirt          (case (rr/uniform-int 3)
                                               0 {:type :dirt}
                                               1 {:type :gravel}
                                               2 {:type :short-grass})
                              :bamboo-grove  (if (< t 0.1)
                                               (dg/rand-nth [
                                                 {:type :dirt}
                                                 {:type :tall-grass}
                                                 {:type :tall-grass}
                                                 {:type :short-grass}
                                                 {:type :short-grass}])
                                               {:type :bamboo})
                              :rocky         (if (< t 0.1)
                                               (dg/rand-nth [
                                                 {:type :dirt}
                                                 {:type :tall-grass}
                                                 {:type :short-grass}
                                                 {:type :short-grass}])
                                                {:type :mountain})
                              :swamp         (dg/rand-nth [
                                               {:type :dirt}
                                               {:type :surf}
                                               {:type :tree}
                                               {:type :tall-grass}
                                               {:type :short-grass}])
                              :meadow        (dg/rand-nth [
                                               {:type :dirt}
                                               {:type :tall-grass}
                                               {:type :tall-grass}
                                               {:type :short-grass}
                                               {:type :short-grass}])
                              :jungle        (if (< t 0.1)
                                               (dg/rand-nth [
                                                 {:type :tall-grass}
                                                 {:type :short-grass}
                                                 {:type :gravel}])
                                               (dg/rand-nth [
                                                 {:type :tall-grass}
                                                 {:type :palm-tree}
                                                 {:type :fruit-tree :fruit-type (dg/rand-nth [:red-fruit :orange-fruit :yellow-fruit
                                                                                              :green-fruit :blue-fruit :purple-fruit
                                                                                              :white-fruit :black-fruit])}]))
                              :heavy-forest  (if (< t 0.1)
                                               (dg/rand-nth [
                                                 {:type :tall-grass}
                                                 {:type :tree}
                                                 {:type :fruit-tree :fruit-type (dg/rand-nth [:red-fruit :orange-fruit :yellow-fruit
                                                                                              :green-fruit :blue-fruit :purple-fruit
                                                                                              :white-fruit :black-fruit])}])
                                               (dg/rand-nth [
                                                 {:type :tall-grass}
                                                 {:type :short-grass}
                                                 {:type :gravel}]))
                              :light-forest  (if (< t 0.1)
                                               (dg/rand-nth [
                                                 {:type :tall-grass}
                                                 {:type :tree}
                                                 {:type :fruit-tree :fruit-type (dg/rand-nth [:red-fruit :orange-fruit :yellow-fruit
                                                                                              :green-fruit :blue-fruit :purple-fruit
                                                                                              :white-fruit :black-fruit])}])
                                               (dg/rand-nth [
                                                 {:type :tall-grass}
                                                 {:type :tall-grass}
                                                 {:type :short-grass}
                                                 {:type :short-grass}])))
                     cell (cond
                            ;; lava
                            (not-every? #(rc/farther-than? (rc/xy->pos x y) (apply rc/xy->pos %) 3) lava-xys)
                            {:type :lava}
                            (not (rc/farther-than? (rc/xy->pos x y) volcano-pos 7))
                            {:type :mountain}
                            :else cell-type)]
              ;; drop initial harvestable items
              (if (or (and (= :gravel (get cell :type))
                           (= :rocky biome)
                           (= (rr/uniform-int 0 50) 0))
                      (and (= :tree (get cell :type))
                           (= :heavy-forest biome)
                           (= (rr/uniform-int 0 50) 0))
                      (and (= :tall-grass (get cell :type))
                           (= :meadow biome)
                           (= (rr/uniform-int 0 50) 0))
                      (and (= :palm-tree (get cell :type))
                           (= :jungle biome)
                           (= (rr/uniform-int 0 50) 0))
                      (and (contains? #{:gravel :tree :palm-tree :tall-grass} (get cell :type))
                           (= (rr/uniform-int 0 200) 0)))
                (assoc cell :harvestable true)
                (if (and (= :gravel (get cell :type))
                         (not-every? #(rc/farther-than? (rc/xy->pos x y) (apply rc/xy->pos %) 10) lava-xys)
                         (= (rr/uniform-int 0 50) 0))
                  (assoc cell :harvestable true :near-lava true)
                  cell))))
            (for [y (range y (+ y height))
                  x (range x (+ x width))]
              [x y])))))))

(defn init-world
  "Create a randomly generated world.

   A world consists of

   * an intial place id (`:0_0`)
  
   * places (indexed by place id)

   * a player
  
   * a log
  
   * a time (initialized to 0)
  
   * a state (for use with state tracking (for complex input like opening doors,
   dropping items, menus)
  
   * available hotkeys (a-zA-Z)
  
   * npcs
  
   * quests (indexed by quest id)

   Not all of the places or npcs have to be generated by this function; they can be
   added during the course of the game."
  [seed]
  ;; Assign hotkeys to inventory and remove from remaining hotkeys
  (let [width                  80
        height                 23
        max-x                  800
        max-y                  800
        x                      (/ max-x 2)
        y                      (/ max-y 2)
        inventory              []
        remaining-hotkeys      (vec (seq "abcdefghijklmnopqrstuvwxyzABCdEFGHIJKLMNOPQRSTUVWQYZ"))
        hotkey-groups          (split-at (count inventory) remaining-hotkeys)
        inventory-with-hotkeys (vec (map #(assoc %1 :hotkey %2) inventory (first hotkey-groups)))
        remaining-hotkeys      (set (clojure.string/join (second hotkey-groups)))
        ;; calculate place-id and viewport position using minimal state information
        
        starting-pos           (find-starting-pos seed max-x max-y)
        volcano-xy             [x y]
        lava-terminal-pos      (find-lava-terminal-pos seed starting-pos max-x max-y)
        lava-segments          (partition 2 (apply line-segments [(first volcano-xy) (second volcano-xy) (get lava-terminal-pos :x) (get lava-terminal-pos :y)]))
        lava-points            (map first lava-segments)
        _                      (info "lava-points" lava-points)
        min-state              {:world {:viewport {:width width :height height}
                                        :seed seed
                                        :volcano-pos (apply rc/xy->pos volcano-xy)
                                        :lava-points lava-points}}
        place-id               (apply xy->place-id min-state (rc/pos->xy starting-pos))
        [sx sy]                (rc/pos->xy starting-pos)
        [vx vy]                [(int (- sx (/ width 2))) (int (- sy (/ height 2)))]
        _ (debug "starting-pos" starting-pos)
        place-0                (init-island min-state vx vy width height)
        fruit-ids              [:red-fruit :orange-fruit :yellow-fruit :green-fruit :blue-fruit :purple-fruit :white-fruit :black-fruit]
        poisoned-fruit         (set (take (/ (count fruit-ids) 2) (dg/shuffle fruit-ids)))
        skin-identifiable      (set (take (/ (count poisoned-fruit) 2) (dg/shuffle poisoned-fruit)))
        tongue-identifiable    (set (take (/ (count poisoned-fruit) 2) (dg/shuffle poisoned-fruit)))
        frog-colors            [:reg :orange :yellow :green :blue :purple]
        poisonous-frog-colors  (set (take (/ (count frog-colors) 2) (dg/shuffle frog-colors)))
        world
          {:seed seed
           :block-size {:width width :height height}
           :width max-x
           :height max-y
           :viewport {
             :width width
             :height height
             :pos {:x vx :y vy}}
           :places {place-id place-0}
                    ;:1 (init-place-1)}
           :current-place :0_0
           :volcano-pos (apply rc/xy->pos volcano-xy)
           :lava-points lava-points
           :time 0
           :current-state :start
           :selected-hotkeys #{}
           :remaining-hotkeys remaining-hotkeys
           :log []
           :ui-hint nil
           :dialog-log []
           :player {
                    :id :player
                    :name "Player"
                    :race :human
                    :class :ranger
                    :movement-policy :entourage
                    :in-party? true
                    :inventory inventory-with-hotkeys
                    :dexterity 1
                    :speed 1
                    :size 75
                    :strength 10
                    :toughness 5
                    :hp 10
                    :max-hp 10
                    :will-to-live 100
                    :max-will-to-live 100
                    :money 50
                    :xp 0
                    :level 0
                    :hunger 0
                    :max-hunger 100
                    :thirst 0
                    :max-thirst 100
                    :pos starting-pos
                    :starting-pos starting-pos
                    :place :0_0
                    :body-parts #{:head :neck :face :abdomen :arm :leg :foot}
                    :attacks #{:punch}
                    :status #{}
                    :stats {
                      :timeline (list)
                      :num-animals-killed       {}
                      :num-items-crafted        {}
                      :num-items-harvested      {}
                      :num-kills-by-attack-type {}
                      :num-items-eaten          {}}
                    ;; map from body-part to {:time <int> :damage <float>}
                    :wounds {}}
           :fruit {
             :poisonous           poisoned-fruit
             :skin-identifiable   skin-identifiable
             :tongue-identifiable tongue-identifiable
             :identified          #{}
           }
           :frogs {
             :poisonous          poisonous-frog-colors
           }
           :quests {}
           :npcs []}]
    world))



(defn load-place
  "Returns a place, not state."
  [state id]
  (info "loading" id)
  ;; load the place into state. From file if exists or gen a new random place.
  (let [place
    (if (.exists (io/as-file (format "save/%s.place.edn" (str id))))
      (rc/log-time "read-string time" ;;(clojure.edn/read-string {:readers {'Monster mg/map->Monster}} s)))
        (with-open [o (io/input-stream (format "save/%s.place.edn" (str id)))]
          (nippy/thaw-from-in! (DataInputStream. o))))
      (let [[ax ay]            (place-id->anchor-xy state id)
            [v-width v-height] (viewport-wh state)
            w-width            (get-in state [:world :width])
            w-height           (get-in state [:world :height])]
        (rc/log-time "init-island time" (init-island state
                                                  ax ay
                                                  v-width v-height))))]
      (info "loaded place. width:" (count (first place)) "height:" (count place))
      place))

(def save-place-chan (async/chan))

(async/go-loop []
  (let [[id place] (async/<! save-place-chan)]
    (info "Saving" id)
    (with-open [o (io/output-stream (format "save/%s.place.edn" (str id)))]
      (nippy/freeze-to-out! (DataOutputStream. o) place)))
    (recur))

(defn unload-place
  [state id]
  (info "unloading" id)
  (rc/log-time "unloading"
  (async/>!! save-place-chan [id (get-in state [:world :places id])])
  ;; Remove all npcs in place being unloaded
  (->
    (reduce (fn [state npc] (if (= (apply xy->place-id state (rc/pos->xy (get npc :pos)))
                                   id)
                                (remove-npc state npc)
                                state))
            state (get-in state [:world :npcs]))
    (dissoc-in [:world :places id]))))

(defn load-unload-places
  [state]
  (let [[x y]             (player-xy state)
        loaded-place-ids  (keys (get-in state [:world :places]))
        visible-place-ids (visible-place-ids state x y)
        places-to-load    (clojure.set/difference (set visible-place-ids) (set loaded-place-ids))
        places-to-unload  (clojure.set/difference (set loaded-place-ids) (set visible-place-ids))]
    (info "currently loaded places:" loaded-place-ids)
    (info "visible places:" visible-place-ids)
    (info "unloading places:" places-to-unload)
    (info "loading places:" places-to-load)
    (-> state
      (as-> state
        (reduce unload-place state places-to-unload))
      (as-> state
        (reduce (fn [state [id place]]
          (assoc-in state [:world :places id] place))
          state (map (fn [id] [id (load-place state id)]) places-to-load))))))


(defn -main [& args]
  (let [n (rn/create-noise)]
    nil
    #_(dorun
      (map (comp (partial apply str) println)
        (partition 70
          (rc/log-time "for"
            (for [y (range 28)
                  x (range 70)
                  :let [[s _ _] (vec (map #(.calc ^clisk.IFunction % (double (/ x 70)) (double (/ y 28)) (double 0.0) (double 0.0))
                                   island-fns))]]
              (cond
                (> s 0.9) \^
                (> s 0.7) \.
                (> s 0.5) \_
                :else \~))))))))
        


