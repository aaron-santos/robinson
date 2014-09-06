;; Functions that manipulate state to do what the user commands.
(ns dungeon-crusade.combat
  (:use     
    clojure.pprint
    dungeon-crusade.common)
  (:require clojure.pprint
            clojure.contrib.core
            [clojure.stacktrace :as st]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(defn- vec-match?
  [v0 v1]
  (let [arg-match? (fn [[arg0 arg1]]
    (cond
      (fn? arg0)  (arg0 arg1)
      (= :* arg0) true
      (set? arg0) (contains? arg0 arg1)
      :else       (= arg0 arg1)))]
  (every? arg-match? (map vector v0 v1))))

(defn- gen-attack-message
  "Logs an attack message to the global state.
   `attack` is one of :bite :claws :punch.
   `damage-type` is one of :miss :hit :dead"
  [attacker defender attack defender-body-part damage-type]
  (let [attacker-name      (get attacker :name)
        defender-name      (get defender :name)
        rand-punch-verb    (fn [] (rand-nth ["wack" "punch" "hit" "pummel" "batter"
                                             "pound" "beat" "strike" "slug"]))]
    (condp vec-match? [attacker-name defender-name attack defender-body-part damage-type]
      ["Player" :*       :punch :*       :miss] (format "You punch the %s but miss." defender-name)
      ["Player" :*       :punch :*       :hit]  (rand-nth [(format "You %s the %s %s %s the %s."
                                                             (rand-punch-verb)
                                                             defender-name
                                                             (rand-nth ["solidly" "swiftly" "repeatedly"
                                                                        "perfectly" "competently"])
                                                             (rand-nth ["on" "in" "across"])
                                                             (name defender-body-part))
                                                           (format "You %s the %s %s the %s."
                                                             (rand-punch-verb)
                                                             defender-name
                                                             (rand-nth ["on" "in" "across"])
                                                             (name defender-body-part))
                                                           (format "You %s the %s."
                                                             (rand-punch-verb)
                                                             defender-name)])
      ["Player" :*       :punch       :head     :dead] (format "You %s the %s in the head. Brains fly everywhere and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :neck     :dead] (format "You %s the %s in the neck snapping it and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :body     :dead] (format "You %s the %s in the body damaging internal organs. It dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :leg      :dead] (format "You %s the %s in the leg severing it and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :face     :dead] (format "You %s the %s in the face. Peices of face fly everywhere and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :abdomen  :dead] (format "You %s the %s in the abdomen. Internal organs fly everywhere and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :claw     :dead] (format "You %s the %s in the claw and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :tail     :dead] (format "You %s the %s in the tail causing massive injuries and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :wing     :dead] (format "You %s the %s in the wing ripping it clean off and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :eye      :dead] (format "You %s the %s in the eye exploding it upon impact and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :snout    :dead] (format "You %s the %s in the snount crushing it and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :arm      :dead] (format "You %s the %s in the arm crushing bones it and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :beak     :dead] (format "You %s the %s in the beak ripping it from its face and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :shell    :dead] (format "You %s the %s in the shell ripping to peices and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :tentacle :dead] (format "You %s the %s in the tentacle shredding it and it dies." (rand-punch-verb) defender-name)
      ["Player" :*       :punch       :*        :dead] (format "You %s the %s causing massive injuries and it dies." (rand-punch-verb) defender-name)
      [:*       "Player" :bite        :*        :miss] (format "The %s lunges at you its mouth but misses." (rand-punch-verb) attacker-name)
      [:*       "Player" :bite-venom  :*        :miss] (format "The %s snaps at you its mouth but misses." (rand-punch-verb) attacker-name)
      [:*       "Player" :claw        :*        :miss] (format "The %s claws at you and narrowly misses." (rand-punch-verb) attacker-name)
      [:*       "Player" :punch       :*        :miss] (format "The %s punches you but misses." attacker-name)
      [:*       "Player" :sting       :*        :miss] (format "The %s tries to sting you but misses." attacker-name)
      [:*       "Player" :sting-venom :*        :miss] (format "The %s tries to sting you but misses." attacker-name)
      [:*       "Player" :squeeze     :*        :miss] (format "The %s starts to constrict around you but fumbles." attacker-name)
      [:*       "Player" :clamp       :*        :miss] (format "The %s tries to clamp onto you but isn't fast enough." attacker-name)
      [:*       "Player" :spike       :*        :miss] (format "You almost get poked by the %'s spikes." attacker-name)
      [:*       "Player" :bite        :*        :hit]  (format "The %s sinks its teeth into your flesh." attacker-name)
      [:*       "Player" :bite-venom  :*        :hit]  (format "The %s buries its teeth into your body and starts pumping poison into you." attacker-name)
      [:*       "Player" :claw        :*        :hit]  (format "The %s claws into your flesh." attacker-name)
      [:*       "Player" :punch       :*        :hit]  (format "The %s punches you." attacker-name)
      [:*       "Player" :gore        :*        :hit]  (format "The %s gores into your body with it's tusks.`" attacker-name)
      [:*       "Player" :sting       :*        :hit]  (format "The %s jabs you with its stinger." attacker-name)
      [:*       "Player" :sting-venom :*        :hit]  (format "The %s stings you, pumping you full of poison." attacker-name)
      [:*       "Player" :squeeze     :*        :hit]  (format "The %s squeezes you leaving you gasping for breath." attacker-name)
      [:*       "Player" :clamp       :*        :hit]  (format "The %s clamps down on your flesh crushing it." attacker-name)
      [:*       "Player" :spike       :*        :hit]  (format "The %s's spikes drive into your body." attacker-name))))

(defn calc-dmg
  [attacker-race attack defender-race defender-body-part]
  (condp vec-match? [attacker-race attack defender-race defender-body-part]
    [:bat        :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:bat        :bite         :human      :arm]       (+  (rand)  0.5)
    [:bat        :bite         :human      :face]      (+  (rand)  0.5)
    [:bat        :bite         :human      :foot]      (+  (rand)  0.5)
    [:bat        :bite         :human      :head]      (+  (rand)  0.5)
    [:bat        :bite         :human      :leg]       (+  (rand)  0.5)
    [:bat        :bite         :human      :neck]      (+  (rand)  0.5)
    [:bird       :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:bird       :bite         :human      :arm]       (+  (rand)  0.5)
    [:bird       :bite         :human      :face]      (+  (rand)  0.5)
    [:bird       :bite         :human      :foot]      (+  (rand)  0.5)
    [:bird       :bite         :human      :head]      (+  (rand)  0.5)
    [:bird       :bite         :human      :leg]       (+  (rand)  0.5)
    [:bird       :bite         :human      :neck]      (+  (rand)  0.5)
    [:bird       :claw         :human      :abdomen]   (+  (rand)  0.5)
    [:bird       :claw         :human      :arm]       (+  (rand)  0.5)
    [:bird       :claw         :human      :face]      (+  (rand)  0.5)
    [:bird       :claw         :human      :foot]      (+  (rand)  0.5)
    [:bird       :claw         :human      :head]      (+  (rand)  0.5)
    [:bird       :claw         :human      :leg]       (+  (rand)  0.5)
    [:bird       :claw         :human      :neck]      (+  (rand)  0.5)
    [:boar       :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:boar       :bite         :human      :arm]       (+  (rand)  0.5)
    [:boar       :bite         :human      :face]      (+  (rand)  0.5)
    [:boar       :bite         :human      :foot]      (+  (rand)  0.5)
    [:boar       :bite         :human      :head]      (+  (rand)  0.5)
    [:boar       :bite         :human      :leg]       (+  (rand)  0.5)
    [:boar       :bite         :human      :neck]      (+  (rand)  0.5)
    [:boar       :gore         :human      :abdomen]   (+  (rand)  0.5)
    [:boar       :gore         :human      :arm]       (+  (rand)  0.5)
    [:boar       :gore         :human      :face]      (+  (rand)  0.5)
    [:boar       :gore         :human      :foot]      (+  (rand)  0.5)
    [:boar       :gore         :human      :head]      (+  (rand)  0.5)
    [:boar       :gore         :human      :leg]       (+  (rand)  0.5)
    [:boar       :gore         :human      :neck]      (+  (rand)  0.5)
    [:centipede  :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:centipede  :bite         :human      :arm]       (+  (rand)  0.5)
    [:centipede  :bite         :human      :face]      (+  (rand)  0.5)
    [:centipede  :bite         :human      :foot]      (+  (rand)  0.5)
    [:centipede  :bite         :human      :head]      (+  (rand)  0.5)
    [:centipede  :bite         :human      :leg]       (+  (rand)  0.5)
    [:centipede  :bite         :human      :neck]      (+  (rand)  0.5)
    [:clam       :clamp        :human      :abdomen]   (+  (rand)  0.5)
    [:clam       :clamp        :human      :arm]       (+  (rand)  0.5)
    [:clam       :clamp        :human      :face]      (+  (rand)  0.5)
    [:clam       :clamp        :human      :foot]      (+  (rand)  0.5)
    [:clam       :clamp        :human      :head]      (+  (rand)  0.5)
    [:clam       :clamp        :human      :leg]       (+  (rand)  0.5)
    [:clam       :clamp        :human      :neck]      (+  (rand)  0.5)
    [:fish       :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:fish       :bite         :human      :arm]       (+  (rand)  0.5)
    [:fish       :bite         :human      :face]      (+  (rand)  0.5)
    [:fish       :bite         :human      :foot]      (+  (rand)  0.5)
    [:fish       :bite         :human      :head]      (+  (rand)  0.5)
    [:fish       :bite         :human      :leg]       (+  (rand)  0.5)
    [:fish       :bite         :human      :neck]      (+  (rand)  0.5)
    [:frog       :claw         :human      :abdomen]   (+  (rand)  0.5)
    [:frog       :claw         :human      :arm]       (+  (rand)  0.5)
    [:frog       :claw         :human      :face]      (+  (rand)  0.5)
    [:frog       :claw         :human      :foot]      (+  (rand)  0.5)
    [:frog       :claw         :human      :head]      (+  (rand)  0.5)
    [:frog       :claw         :human      :leg]       (+  (rand)  0.5)
    [:frog       :claw         :human      :neck]      (+  (rand)  0.5)
    [:gecko      :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:gecko      :bite         :human      :arm]       (+  (rand)  0.5)
    [:gecko      :bite         :human      :face]      (+  (rand)  0.5)
    [:gecko      :bite         :human      :foot]      (+  (rand)  0.5)
    [:gecko      :bite         :human      :head]      (+  (rand)  0.5)
    [:gecko      :bite         :human      :leg]       (+  (rand)  0.5)
    [:gecko      :bite         :human      :neck]      (+  (rand)  0.5)
    [:human      :punch        :bat        :body]      (+  (rand)  0.5)
    [:human      :punch        :bat        :face]      (+  (rand)  0.5)
    [:human      :punch        :bat        :head]      (+  (rand)  0.5)
    [:human      :punch        :bat        :leg]       (+  (rand)  0.5)
    [:human      :punch        :bat        :wing]      (+  (rand)  0.5)
    [:human      :punch        :bird       :beak]      (+  (rand)  0.5)
    [:human      :punch        :bird       :body]      (+  (rand)  0.5)
    [:human      :punch        :bird       :head]      (+  (rand)  0.5)
    [:human      :punch        :bird       :leg]       (+  (rand)  0.5)
    [:human      :punch        :bird       :tail]      (+  (rand)  0.5)
    [:human      :punch        :bird       :wing]      (+  (rand)  0.5)
    [:human      :punch        :boar       :body]      (+  (rand)  0.5)
    [:human      :punch        :boar       :eye]       (+  (rand)  0.5)
    [:human      :punch        :boar       :face]      (+  (rand)  0.5)
    [:human      :punch        :boar       :head]      (+  (rand)  0.5)
    [:human      :punch        :boar       :leg]       (+  (rand)  0.5)
    [:human      :punch        :boar       :snout]     (+  (rand)  0.5)
    [:human      :punch        :boar       :tail]      (+  (rand)  0.5)
    [:human      :punch        :centipede  :body]      (+  (rand)  0.5)
    [:human      :punch        :centipede  :head]      (+  (rand)  0.5)
    [:human      :punch        :centipede  :leg]       (+  (rand)  0.5)
    [:human      :punch        :clam       :shell]     (+  (rand)  0.5)
    [:human      :punch        :fish       :body]      (+  (rand)  0.5)
    [:human      :punch        :fish       :fin]       (+  (rand)  0.5)
    [:human      :punch        :fish       :head]      (+  (rand)  0.5)
    [:human      :punch        :fish       :tail]      (+  (rand)  0.5)
    [:human      :punch        :frog       :body]      (+  (rand)  0.5)
    [:human      :punch        :frog       :face]      (+  (rand)  0.5)
    [:human      :punch        :frog       :head]      (+  (rand)  0.5)
    [:human      :punch        :frog       :leg]       (+  (rand)  0.5)
    [:human      :punch        :gecko      :body]      (+  (rand)  0.5)
    [:human      :punch        :gecko      :face]      (+  (rand)  0.5)
    [:human      :punch        :gecko      :head]      (+  (rand)  0.5)
    [:human      :punch        :gecko      :leg]       (+  (rand)  0.5)
    [:human      :punch        :gecko      :tail]      (+  (rand)  0.5)
    [:human      :punch        :monkey     :arm]       (+  (rand)  0.5)
    [:human      :punch        :monkey     :body]      (+  (rand)  0.5)
    [:human      :punch        :monkey     :face]      (+  (rand)  0.5)
    [:human      :punch        :monkey     :head]      (+  (rand)  0.5)
    [:human      :punch        :monkey     :leg]       (+  (rand)  0.5)
    [:human      :punch        :monkey     :neck]      (+  (rand)  0.5)
    [:human      :punch        :monkey     :tail]      (+  (rand)  0.5)
    [:human      :punch        :octopus    :body]      (+  (rand)  0.5)
    [:human      :punch        :octopus    :head]      (+  (rand)  0.5)
    [:human      :punch        :octopus    :tentacle]  (+  (rand)  0.5)
    [:human      :punch        :parrot     :body]      (+  (rand)  0.5)
    [:human      :punch        :parrot     :face]      (+  (rand)  0.5)
    [:human      :punch        :parrot     :head]      (+  (rand)  0.5)
    [:human      :punch        :parrot     :leg]       (+  (rand)  0.5)
    [:human      :punch        :parrot     :tail]      (+  (rand)  0.5)
    [:human      :punch        :parrot     :wing]      (+  (rand)  0.5)
    [:human      :punch        :rat        :body]      (+  (rand)  0.5)
    [:human      :punch        :rat        :face]      (+  (rand)  0.5)
    [:human      :punch        :rat        :head]      (+  (rand)  0.5)
    [:human      :punch        :rat        :leg]       (+  (rand)  0.5)
    [:human      :punch        :rat        :neck]      (+  (rand)  0.5)
    [:human      :punch        :rat        :tail]      (+  (rand)  0.5)
    [:human      :punch        :scorpion   :abdomen]   (+  (rand)  0.5)
    [:human      :punch        :scorpion   :claw]      (+  (rand)  0.5)
    [:human      :punch        :scorpion   :head]      (+  (rand)  0.5)
    [:human      :punch        :scorpion   :leg]       (+  (rand)  0.5)
    [:human      :punch        :scorpion   :tail]      (+  (rand)  0.5)
    [:human      :punch        :sea-snake  :body]      (+  (rand)  0.5)
    [:human      :punch        :sea-snake  :head]      (+  (rand)  0.5)
    [:human      :punch        :shark      :body]      (+  (rand)  0.5)
    [:human      :punch        :shark      :fin]       (+  (rand)  0.5)
    [:human      :punch        :shark      :head]      (+  (rand)  0.5)
    [:human      :punch        :shark      :nose]      (+  (rand)  0.5)
    [:human      :punch        :shark      :tail]      (+  (rand)  0.5)
    [:human      :punch        :snake      :body]      (+  (rand)  0.5)
    [:human      :punch        :snake      :head]      (+  (rand)  0.5)
    [:human      :punch        :snake      :tail]      (+  (rand)  0.5)
    [:human      :punch        :spider     :abdomen]   (+  (rand)  0.5)
    [:human      :punch        :spider     :face]      (+  (rand)  0.5)
    [:human      :punch        :spider     :leg]       (+  (rand)  0.5)
    [:human      :punch        :squid      :body]      (+  (rand)  0.5)
    [:human      :punch        :squid      :head]      (+  (rand)  0.5)
    [:human      :punch        :squid      :tentacle]  (+  (rand)  0.5)
    [:human      :punch        :turtle     :body]      (+  (rand)  0.5)
    [:human      :punch        :turtle     :face]      (+  (rand)  0.5)
    [:human      :punch        :turtle     :head]      (+  (rand)  0.5)
    [:human      :punch        :turtle     :leg]       (+  (rand)  0.5)
    [:human      :punch        :turtle     :neck]      (+  (rand)  0.5)
    [:human      :punch        :turtle     :shell]     (+  (rand)  0.5)
    [:human      :punch        :urchin     :body]      (+  (rand)  0.5)
    [:monkey     :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:monkey     :bite         :human      :arm]       (+  (rand)  0.5)
    [:monkey     :bite         :human      :face]      (+  (rand)  0.5)
    [:monkey     :bite         :human      :foot]      (+  (rand)  0.5)
    [:monkey     :bite         :human      :head]      (+  (rand)  0.5)
    [:monkey     :bite         :human      :leg]       (+  (rand)  0.5)
    [:monkey     :bite         :human      :neck]      (+  (rand)  0.5)
    [:monkey     :punch        :human      :abdomen]   (+  (rand)  0.5)
    [:monkey     :punch        :human      :arm]       (+  (rand)  0.5)
    [:monkey     :punch        :human      :face]      (+  (rand)  0.5)
    [:monkey     :punch        :human      :foot]      (+  (rand)  0.5)
    [:monkey     :punch        :human      :head]      (+  (rand)  0.5)
    [:monkey     :punch        :human      :leg]       (+  (rand)  0.5)
    [:monkey     :punch        :human      :neck]      (+  (rand)  0.5)
    [:octopus    :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:octopus    :bite         :human      :arm]       (+  (rand)  0.5)
    [:octopus    :bite         :human      :face]      (+  (rand)  0.5)
    [:octopus    :bite         :human      :foot]      (+  (rand)  0.5)
    [:octopus    :bite         :human      :head]      (+  (rand)  0.5)
    [:octopus    :bite         :human      :leg]       (+  (rand)  0.5)
    [:octopus    :bite         :human      :neck]      (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :abdomen]   (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :arm]       (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :face]      (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :foot]      (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :head]      (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :leg]       (+  (rand)  0.5)
    [:octopus    :bite-venom   :human      :neck]      (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :abdomen]   (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :arm]       (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :face]      (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :foot]      (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :head]      (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :leg]       (+  (rand)  0.5)
    [:octopus    :squeeze      :human      :neck]      (+  (rand)  0.5)
    [:parrot     :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:parrot     :bite         :human      :arm]       (+  (rand)  0.5)
    [:parrot     :bite         :human      :face]      (+  (rand)  0.5)
    [:parrot     :bite         :human      :foot]      (+  (rand)  0.5)
    [:parrot     :bite         :human      :head]      (+  (rand)  0.5)
    [:parrot     :bite         :human      :leg]       (+  (rand)  0.5)
    [:parrot     :bite         :human      :neck]      (+  (rand)  0.5)
    [:parrot     :claw         :human      :abdomen]   (+  (rand)  0.5)
    [:parrot     :claw         :human      :arm]       (+  (rand)  0.5)
    [:parrot     :claw         :human      :face]      (+  (rand)  0.5)
    [:parrot     :claw         :human      :foot]      (+  (rand)  0.5)
    [:parrot     :claw         :human      :head]      (+  (rand)  0.5)
    [:parrot     :claw         :human      :leg]       (+  (rand)  0.5)
    [:parrot     :claw         :human      :neck]      (+  (rand)  0.5)
    [:rat        :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:rat        :bite         :human      :arm]       (+  (rand)  0.5)
    [:rat        :bite         :human      :face]      (+  (rand)  0.5)
    [:rat        :bite         :human      :foot]      (+  (rand)  0.5)
    [:rat        :bite         :human      :head]      (+  (rand)  0.5)
    [:rat        :bite         :human      :leg]       (+  (rand)  0.5)
    [:rat        :bite         :human      :neck]      (+  (rand)  0.5)
    [:rat        :claw         :human      :abdomen]   (+  (rand)  0.5)
    [:rat        :claw         :human      :arm]       (+  (rand)  0.5)
    [:rat        :claw         :human      :face]      (+  (rand)  0.5)
    [:rat        :claw         :human      :foot]      (+  (rand)  0.5)
    [:rat        :claw         :human      :head]      (+  (rand)  0.5)
    [:rat        :claw         :human      :leg]       (+  (rand)  0.5)
    [:rat        :claw         :human      :neck]      (+  (rand)  0.5)
    [:scorpion   :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:scorpion   :bite         :human      :arm]       (+  (rand)  0.5)
    [:scorpion   :bite         :human      :face]      (+  (rand)  0.5)
    [:scorpion   :bite         :human      :foot]      (+  (rand)  0.5)
    [:scorpion   :bite         :human      :head]      (+  (rand)  0.5)
    [:scorpion   :bite         :human      :leg]       (+  (rand)  0.5)
    [:scorpion   :bite         :human      :neck]      (+  (rand)  0.5)
    [:scorpion   :claw         :human      :abdomen]   (+  (rand)  0.5)
    [:scorpion   :claw         :human      :arm]       (+  (rand)  0.5)
    [:scorpion   :claw         :human      :face]      (+  (rand)  0.5)
    [:scorpion   :claw         :human      :foot]      (+  (rand)  0.5)
    [:scorpion   :claw         :human      :head]      (+  (rand)  0.5)
    [:scorpion   :claw         :human      :leg]       (+  (rand)  0.5)
    [:scorpion   :claw         :human      :neck]      (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :abdomen]   (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :arm]       (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :face]      (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :foot]      (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :head]      (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :leg]       (+  (rand)  0.5)
    [:scorpion   :sting-venom  :human      :neck]      (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :arm]       (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :face]      (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :foot]      (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :head]      (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :leg]       (+  (rand)  0.5)
    [:sea-snake  :bite         :human      :neck]      (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :abdomen]   (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :arm]       (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :face]      (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :foot]      (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :head]      (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :leg]       (+  (rand)  0.5)
    [:sea-snake  :bite-venom   :human      :neck]      (+  (rand)  0.5)
    [:shark      :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:shark      :bite         :human      :arm]       (+  (rand)  0.5)
    [:shark      :bite         :human      :face]      (+  (rand)  0.5)
    [:shark      :bite         :human      :foot]      (+  (rand)  0.5)
    [:shark      :bite         :human      :head]      (+  (rand)  0.5)
    [:shark      :bite         :human      :leg]       (+  (rand)  0.5)
    [:shark      :bite         :human      :neck]      (+  (rand)  0.5)
    [:snake      :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:snake      :bite         :human      :arm]       (+  (rand)  0.5)
    [:snake      :bite         :human      :face]      (+  (rand)  0.5)
    [:snake      :bite         :human      :foot]      (+  (rand)  0.5)
    [:snake      :bite         :human      :head]      (+  (rand)  0.5)
    [:snake      :bite         :human      :leg]       (+  (rand)  0.5)
    [:snake      :bite         :human      :neck]      (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :abdomen]   (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :arm]       (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :face]      (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :foot]      (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :head]      (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :leg]       (+  (rand)  0.5)
    [:snake      :bite-venom   :human      :neck]      (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :abdomen]   (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :arm]       (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :face]      (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :foot]      (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :head]      (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :leg]       (+  (rand)  0.5)
    [:spider     :bite-venom   :human      :neck]      (+  (rand)  0.5)
    [:squid      :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:squid      :bite         :human      :arm]       (+  (rand)  0.5)
    [:squid      :bite         :human      :face]      (+  (rand)  0.5)
    [:squid      :bite         :human      :foot]      (+  (rand)  0.5)
    [:squid      :bite         :human      :head]      (+  (rand)  0.5)
    [:squid      :bite         :human      :leg]       (+  (rand)  0.5)
    [:squid      :bite         :human      :neck]      (+  (rand)  0.5)
    [:squid      :squeeze      :human      :abdomen]   (+  (rand)  0.5)
    [:squid      :squeeze      :human      :arm]       (+  (rand)  0.5)
    [:squid      :squeeze      :human      :face]      (+  (rand)  0.5)
    [:squid      :squeeze      :human      :foot]      (+  (rand)  0.5)
    [:squid      :squeeze      :human      :head]      (+  (rand)  0.5)
    [:squid      :squeeze      :human      :leg]       (+  (rand)  0.5)
    [:squid      :squeeze      :human      :neck]      (+  (rand)  0.5)
    [:turtle     :bite         :human      :abdomen]   (+  (rand)  0.5)
    [:turtle     :bite         :human      :arm]       (+  (rand)  0.5)
    [:turtle     :bite         :human      :face]      (+  (rand)  0.5)
    [:turtle     :bite         :human      :foot]      (+  (rand)  0.5)
    [:turtle     :bite         :human      :head]      (+  (rand)  0.5)
    [:turtle     :bite         :human      :leg]       (+  (rand)  0.5)
    [:turtle     :bite         :human      :neck]      (+  (rand)  0.5)
    [:urchin     :spike        :human      :abdomen]   (+  (rand)  0.5)
    [:urchin     :spike        :human      :arm]       (+  (rand)  0.5)
    [:urchin     :spike        :human      :face]      (+  (rand)  0.5)
    [:urchin     :spike        :human      :foot]      (+  (rand)  0.5)
    [:urchin     :spike        :human      :head]      (+  (rand)  0.5)
    [:urchin     :spike        :human      :leg]       (+  (rand)  0.5)
    [:urchin     :spike        :human      :neck]      (+  (rand)  0.5)
    [:* :* :* :*] (+ (rand) 0.5)))

(defn attack
  "Perform combat. The attacker fights the defender, but not vice-versa.
   Return a new state reflecting combat outcome."
  [state attacker-path defender-path]
  {:pre [(every? (set (keys (get-in state defender-path))) [:hp :pos :race :body-parts :inventory])
         (every? (set (keys (get-in state attacker-path))) [:attacks])
         (vector? (get-in state [:world :npcs]))]
   :post [(vector? (get-in % [:world :npcs]))]}
  (let [defender           (get-in state defender-path)
        attacker           (get-in state attacker-path)
        attack             (rand-nth (vec (get attacker :attacks)))
        defender-body-part (rand-nth (vec (get defender :body-parts)))
        {x :x y :y}        (get defender :pos)
        hp                 (get defender :hp)
        dmg                (calc-dmg (get attacker :race) attack (get defender :race) defender-body-part)
        is-wound           (> dmg 1.5)]
    (debug "attack" attacker-path "is attacking defender" defender-path)
    (debug "attacker-detail" attacker)
    (debug "defender-detail" defender)
    (cond
      ;; defender still alive?
      (pos? (- hp dmg))
        (-> state
          ;; modify defender hp
          (update-in (conj defender-path :hp)
            (fn [hp] (- hp dmg)))
          (append-log (gen-attack-message attacker
                                          defender
                                          attack
                                          defender-body-part
                                          :hit))
          ;; chance of being envenomed by venomous attacks
          (update-in (conj defender-path :status) (fn [status] (if (and (re-find #"venom" (str attack))
                                                                        (= (rand-int 10) 0))
                                                                 (conj status :poisioned)
                                                                 status)))
          ;; chance of being wounded
          (update-in defender-path (fn [defender] (if (and is-wound
                                                           (contains? defender :wounds))
                                                    (update-in defender [:wounds]
                                                      (fn [wounds] (merge-with (fn [w0 w1] {:time (max (get w0 :time) (get w1 :time))
                                                                                            :dmg  (+   (get w0 :dmg)  (get w1 :dmg))})
                                                                     wounds
                                                                     {defender-body-part {:time (get-in state [:world :time])
                                                                                    :dmg dmg}})))
                                                    defender)))
          ((fn [state] (if (and is-wound
                                (contains? (set defender-path) :player))
                         (append-log state "You have been wounded.")
                         state))))
      ;; defender dead? (0 or less hp)
      (not (pos? (- hp dmg)))
        (if (contains? (set defender-path) :npcs)
          ;; defender is npc
          (-> state
            ;; remove defender
            (remove-in (butlast defender-path) (partial = defender))
            ;; maybe add corpse
            (update-in [:world :places (current-place-id state) y x :items]
                       (fn [items]
                         (if (zero? (rand-int 3))
                           (conj items {:type :food :name (format "%s corpse" (name (get defender :race))) :hunger 10})
                           items)))
            (append-log (gen-attack-message attacker
                                            defender
                                            attack
                                            defender-body-part
                                            :dead)))
          ;; defender is player
          (update-in state [:world :player :status]
            (fn [status] (conj status :dead)))))))

