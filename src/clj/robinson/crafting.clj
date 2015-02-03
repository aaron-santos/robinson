;; Functions for generating random items.
(ns robinson.crafting
  (:use     robinson.common
            robinson.world
            robinson.itemgen
            robinson.player)
  (:require [clojure.core.typed :as t]
            [pallet.thread-expr :as tx]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(t/defalias Recipe (t/HMap :mandatory {:name String :hotkey Character :recipe (t/Map t/Kw (t/Vec t/Kw))}))

(t/ann recipes (t/HMap :mandatory {:weapons        (t/Vec Recipe)
                                   :survival       (t/Vec Recipe)
                                   :shelter        (t/Vec Recipe)
                                   :traps          (t/Vec Recipe)
                                   :transportation (t/Vec Recipe)}))
(def recipes 
  {:weapons  [
     {:name "obsidian spear"         :hotkey \a :recipe {:exhaust [:obsidian-blade :stick :rope] :add [:obsidian-spear]}}
     {:name "obsidian axe"           :hotkey \b :recipe {:exhaust [:obsidian-blade :stick :rope] :add [:obsidian-axe]}}
     {:name "obsidian knife"         :hotkey \c :recipe {:exhaust [:obsidian-blade :stick :rope] :add [:obsidian-knife]}}
     {:name "bow"                    :hotkey \d :recipe {:exhaust [:stick :rope] :add [:bow]}}
     {:name "arrow"                  :hotkey \e :recipe {:exhaust [:obsidian-blade :stick] :add [:arrow]}}]
   :survival [
     {:name "obsidian blade"         :hotkey \a :recipe {:exhaust [:rock :obsidian]              :add [:obsidian-blade]}}
     {:name "rope"                   :hotkey \b :recipe {:exhaust [:plant-fiber]                 :add [:rope]}}
     {:name "sharpened stick"        :hotkey \c :recipe {:exhaust [:stick]
                                                         :have-or [:obsidian-knife
                                                                   :obsidian-spear
                                                                   :obsidian-axe
                                                                   :knife]
                                                         :add     [:sharpened-stick]}}
     {:name "bamboo water collector" :hotkey \d :recipe {:exhaust [:rope :bamboo :stick]
                                                         :have-or [:obsidian-knife
                                                                   :obsidian-spear
                                                                   :obsidian-axe
                                                                   :knife]
                                                         :add [:bamboo-water-collector]} :place :cell-type}
     {:name "solar still"            :hotkey \e :recipe {:exhaust [:rock :tarp :stick :coconut-shell]
                                                         :have-or [:stick]
                                                         :add [:solar-still]} :place :cell-type}
     {:name "fishing pole"           :hotkey \f :recipe {:exhaust [:fishing-line-and-hook :stick]
                                                         :add [:fishing-pole]}
                                                :place :inventory}
     {:name "fire plough"            :hotkey \g :recipe {:exhaust [:stick :stick]
                                                         :add [:fire-plough]}
                                                :place :inventory}
     {:name "hand drill"             :hotkey \h :recipe {:exhaust [:stick :stick]
                                                         :add [:hand-drill]}
                                                :place :inventory}
     {:name "bow drill"              :hotkey \i :recipe {:exhaust [:stick :stick :stick :rope :rock]
                                                         :add [:bow-drill]}
                                                :place :inventory}
     {:name "campfire"               :hotkey \j :recipe {:exhaust [:match :stick :log :log :rock :rock :rock]
                                                         :add [:campfire]}
                                                :place :cell-type}]
   :shelter [
     {:name "palisade"               :hotkey \a :recipe {:exhaust [:rope :sharpened-stick]       :add [:palisade]} :place :inventory}
     {:name "ramada"                 :hotkey \b :recipe {:exhaust [:rope :leaves :stick
                                                                   :stick :stick :stick :stick]  :add [:ramada]}  :place :cell-type}
     {:name "tarp shelter"           :hotkey \c :recipe {:exhaust [:rope :tarp :stick
                                                                   :stick :stick :stick]         :add [:tarp-shelter]}  :place :cell-type}
     {:name "lean-to"                :hotkey \d :recipe {:exhaust [:leaves :stick :stick
                                                                   :stick :stick :stick]         :add [:lean-to]}  :place :cell-type}]
   :traps [
     {:name "snare"                  :hotkey \a :recipe {:exhaust [:rope :stick]                 :add [:snare]}}
     {:name "deadfall trap"          :hotkey \b :recipe {:exhaust [:rope :stick :rock]           :add [:deadfall-trap]}}]
   :transportation [
     {:name "raft"               :hotkey \a :recipe {:exhaust [:rope :log :log
                                                               :log :log :log]
                                                         :add [:raft]} :place :drop}]})
(t/ann has-prerequisites? (t/Fn [State Recipe -> Boolean]))
(defn has-prerequisites?
  "Return true if the player has the ability to make the recipe."
  [state recipe]
  (let [inventory        (get-in state [:world :player :inventory])
        and-requirements (frequencies (concat (get-in recipe [:recipe :exhaust] [])
                                              (get-in recipe [:recipe :have-and] [])))
        or-requirements  (get-in recipe [:recipe :have-or] [])
        have-and-reqs    (every? (fn [[requirement n]] (some (fn [item]
                                                               (and (= (get item :id) requirement)
                                                                 (>= (get item :count 1) n)))
                                                             inventory))
                                 and-requirements)
        have-or-reqs    (or (some (set or-requirements) (set (map :id inventory)))
                            (empty? or-requirements))]
    (when (= (get recipe :name) "sharpened stick")
      (info "recipe" (get recipe :name))
      (info "inventory" inventory)
      (info "or-requirements" or-requirements)
      (info "have and reqs?" have-and-reqs)
      (info "have or reqs?" have-or-reqs))
    (and have-and-reqs have-or-reqs)))

(defn get-recipes
  "Return recipes tagged with :applicable true if the recipe has the required pre-requisites."
  [state]
  (apply hash-map
    (mapcat
      (fn [[group-name group]]
        [group-name (map (fn [recipe] (if (has-prerequisites? state recipe)
                                        (assoc recipe :applicable true)
                                        recipe))
                         group)])
      recipes)))

(defn- exhaust-by-ids
  [state ids]
  (reduce (fn [state id]
            (do 
              (info "removing" id)
              (dec-item-count state id)))
          state
          ids))

(defn- place-cell-type
  [state id]
    (let [[x y] (player-xy state)]
      (assoc-cell state x y :type id)))
  
(defn- place-drop
  [state id]
  (let [[x y] (player-xy state)]
    (conj-cell-items state x y (id->item id))))
  
(defn- add-by-ids
  [state ids place]
  (reduce (fn [state id]
            (case place
              :cell-type
                (place-cell-type state id)
              :drop
                (place-drop state id)
              :inventory
                (let [item (id->item id)]
                  (info "adding" item)
                  (add-to-inventory state [item]))))
          state
          ids))
  
(defn craft-recipe
  "Perform the recipe."
  [state recipe]
  (let [exhaust      (get-in recipe [:recipe :exhaust])
        add          (get-in recipe [:recipe :add])
        have-or      (get-in recipe [:recipe :have-or])
        wielded-item (wielded-item (player-inventory state))
        have-applicable-ids (clojure.set/intersection (set have-or)
                                                       (set (map :id (player-inventory state))))
        _ (info "crafting" recipe)]
    (if (has-prerequisites? state recipe)
      ;; player has only one applicable item, or has many and is wielding one, or the recipe doesn't require the player to have any items?
      (if (or (zero? (count have-or))
              (= (count have-applicable-ids) 1)
              wielded-item)
        (let [state (as-> state state
                      (if (or (= (count have-applicable-ids) 1)
                              wielded-item)
                        (dec-item-utility state (or (and wielded-item (get wielded-item :id))
                                                    (first have-applicable-ids)))
                        state)
                      (add-by-ids state add (get recipe :place :inventory))
                      (exhaust-by-ids state exhaust)
                      (reduce update-crafted state (map (fn [id] {:id id}) add)))]
          state)
        (ui-hint state (format "You have multiple items that can be used to make this. Wield one of them")))
      (ui-hint state (format "You don't have the necessary items to make %s recipe." (get recipe :name))))))
