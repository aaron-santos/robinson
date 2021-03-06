(ns robinson.crafting.mod
  (:require [robinson.common :as rc]
            [robinson.world :as rw]
            [robinson.player :as rp]
            [robinson.inventory :as ri]
            [robinson.crafting.mod-protocol :as rcmp]
            [robinson.inventory :as ri]
            [robinson.npc :as rnpc]
            [robinson.monstergen :as rmg]
            [taoensso.timbre :as log]))

(defn adj-val [v amount]
  (+ (or v 0) amount))

(defn- kebab-to-pascal [sym]
  (->>
    (-> sym name (clojure.string/split #"-"))
    (map clojure.string/capitalize)
    (clojure.string/join "")
    symbol))

(defmacro defmod-type [sym & args]
  (let [pascal (kebab-to-pascal sym)
        pascal# pascal
        constructor# (symbol (str "->" (name pascal)))
        [tag body] (if (= (first args) :tag)
                     [(second args) (drop 2 args)]
                     [nil args])
        tag# tag
        body# body]
    (println "tag" tag)
    (println "body" body)
    `(do
      (defrecord ~pascal# ~'[full-name short-name k n cause]
        rcmp/Mod
        ~'(full-name [this] full-name)
        ~'(short-name [this] short-name)
        ~'(id [this] (hash [(type this) k]))
        ~`(~'merge [~'this ~'other]
          ~(if-not tag#
             `(update ~'this :n adj-val (get ~'other :n))
             `(update ~'this :n (fn [~'x ~'y] (or ~'x ~'y)) (get ~'other :n))))
        ~@(when-not tag#
            `(rcmp/ModQuantifiable
             ~'(amount [this] n)))
        ~@`(rcmp/ModCause
            ~'(cause [this] cause))
        ~@body#)
      (defn ~sym ~'[full-name short-name k amount & args]
        (let [argmap# (->> ~'args (partition 2) (map vec) (into {} ))]
          (~constructor#
            ~'full-name
            ~'short-name
            ~'k
            ~'amount
            (:cause argmap#)))))))

(defmod-type adj-item-on-create
  rcmp/ModNormative
  (utility [this] n)
  rcmp/ModItemOnCreate
  (item-on-create [this item]
    (update item k adj-val n)))

(defmod-type adj-item-on-create-with-cause
  rcmp/ModNormative
  (utility [this] n)
  rcmp/ModCause
  rcmp/ModItemOnCreate
  (item-on-create [this item]
    (update item k adj-val n)))

(defmod-type tag-item-on-create
  :tag true
  rcmp/ModItemOnCreate
  (item-on-create [this item]
    (assoc item k n)))

(defmod-type adj-player-immediate
  :tag false
  rcmp/ModImmediate
  rcmp/ModPlayerImmediate
  (player-immediate [this player]
    (update player k adj-val n)))

(defmod-type tag-player-on-create
  :tag true
  rcmp/ModPlayerOnCreate
  (player-on-create [this player]
    (assoc player k n)))

(defmod-type tag-player-immediate
  :tag true
  rcmp/ModImmediate
  rcmp/ModPlayerImmediate
  (player-immediate [this player]
    (assoc player k n)))

(defmod-type conj-player-immediate
  :tag true
  rcmp/ModImmediate
  rcmp/ModPlayerImmediate
  (player-immediate [this player]
    (update player k conj n)))

(defmod-type dec-inventory-by-hotkey
  :tag true
  rcmp/ModImmediate
  rcmp/ModPlayerDecInventoryImmediate
  (player-dec-inventory-immediate [this state]
    (ri/dec-item-count state k)))

(defmod-type dec-inventory-by-item-id
  :tag true
  rcmp/ModImmediate
  rcmp/ModPlayerDecInventoryImmediate
  (player-dec-inventory-immediate [this state]
    (some->> k
      (ri/inventory-id->item state)
      :hotkey
      (ri/dec-item-count state))))

(defmod-type remove-effect
  :tag true
  rcmp/ModImmediate
  rcmp/ModRecipeRemoveEffectImmediate
  (recipe-remove-effect-immediate [this state]
    (let [selected-recipe-hotkey (get-in state [:world :selected-recipe-hotkey])]
      (update-in state [:world :recipes selected-recipe-hotkey :effects]
        (fn [effects]
          (log/info "Removing " k " from " effects)
          (remove (partial = k) effects))))))

(defmod-type adj-attacker-on-attack
  rcmp/ModAttackerOnAttack
  (attacker-on-attack [this attacker defender]
    (update attacker k adj-val n)))

(defmod-type tag-attacker-on-attack
  :tag true
  rcmp/ModAttackerOnAttack
  (attacker-on-attack [this attacker defender]
    (assoc attacker k n)))

(defmod-type adj-defender-on-attack
  rcmp/ModDefenderOnAttack
  (defender-on-attack [this attacker defender]
    (update defender k adj-val n)))

(defmod-type tag-defender-on-attack
  :tag true
  rcmp/ModDefenderOnAttack
  (defender-on-attack [this attacker defender]
    (assoc defender k n)))

(defmod-type adj-attacker-on-attack-temp
  rcmp/ModAttackerOnAttackTemp
  (attacker-on-attack-temp [this attacker defender]
    (update attacker k adj-val n)))

(defmod-type tag-attacker-on-attack-temp
  :tag true
  rcmp/ModAttackerOnAttackTemp
  (attacker-on-attack-temp [this attacker defender]
    (assoc attacker k n)))

(defmod-type adj-defender-on-attack-temp
  rcmp/ModDefenderOnAttackTemp
  (defender-on-attack-temp [this attacker defender]
    (update defender k adj-val n)))

(defmod-type tag-defender-on-attack-temp
  :tag true
  rcmp/ModDefenderOnAttackTemp
  (defender-on-attack-temp [this attacker defender]
    (assoc defender k n)))

(defrecord ConditionedOnHierarchy [mod]
  rcmp/ModConditionedOnHierarchy
  ; returns mod when applies, otherwise nil
  (when-triggered [this h tag v]
    (when (contains? (ancestors h tag) v)
      mod)))

(defmod-type assoc-current-state-immediate
  :tag true
  rcmp/ModImmediate
  rcmp/ModStateImmediate
  (state-immediate [this state]
    (assoc-in state [:world :current-state] k)))

(defmod-type assoc-in-inventory-item-immediate
  rcmp/ModImmediate
  rcmp/ModStateImmediate
  (state-immediate [this state]
    (ri/update-inventory-item-by-id state k assoc n true)))


(defmod-type add-npc-immediate
  :tag true
  rcmp/ModImmediate
  rcmp/ModStateImmediate
  (state-immediate [this state]
    (let [monster (rmg/id->monster k)]
      (rnpc/add-npc state monster
        (->> state
             rp/player-pos
             rw/adjacent-xys-ext
             (remove (fn [[x y]] (rw/collide? state x y {:include-npcs? true
                                                         :collide-player? true
                                                         :collide-water? false})))
             rand-nth
             (apply rc/xy->pos))))))
 

