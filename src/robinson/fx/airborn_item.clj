;; Utility functions for rendering state
(ns robinson.fx.airborn-item
  (:require 
            [robinson.common :as rc]
            [robinson.renderutil :as rutil]
            [robinson.math :as rmath]
            [robinson.color :as rcolor]
            [robinson.actors :as ractors]
            [robinson.world :as rw]
            [robinson.player :as rp]
            [robinson.fx :as rfx]
            [robinson.describe :as rdesc]
            [robinson.npc :as rnpc]
            [robinson.itemgen :as ig]
            [robinson.traps :as rt]
            [robinson.combat :as rcombat]
            [taoensso.timbre :as log]
            [zaffre.animation.wrapper :as zaw]))

(defrecord AirbornItemActor [item xy-path ttl fx-ks]
  ractors/Actor
  (receive [this state]
	(let [[x y] (first xy-path)
          pos (rc/xy->pos x y)
          player-in-cell (= pos (rp/player-pos state))
		  npc (rnpc/npc-at-pos state pos)
          _ (log/info "get-cell" pos)
          cell (rw/get-cell state pos)
          trap (rw/is-trap-type? (get cell :type))
          ttl-zero (zero? ttl)]
	  (letfn [(cleanup [state]
				(-> state
				  (ractors/remove-actor this)
				  (rc/dissoc-in fx-ks)))
              (handle-player [state]
				(-> state
					(rcombat/attack (get item :attacker) [:world :player] item)
					(rw/conj-cell-items x y (assoc item :count 1))))
              (handle-npc [state npc]
				(-> state
					(rcombat/attack [:world :player] (rnpc/npc->keys state npc) item)
					(rw/conj-cell-items x y (assoc item :count 1))))
              (handle-trigger-trap [state]
				; triggers a trap
				;; remove item and trigger trap
                (-> state
                  (rc/append-log "You throw it at the trap.")
                  (rp/dec-item-count (get item :id))
                  (rt/trigger-if-trap state [x y])))
				(handle-ttl-zero [state]
				  ; hits the ground when ttl = 0
				  ;; didn't hit anything, drop into cell at max-distance
				  (-> state
					(rp/dec-item-count (get item :id))
					(rw/conj-cell-items x y (assoc item :count 1))))
				(on-move [state]
				  ; one of several things can happen
				  (cond
                    ; player in cell
                    player-in-cell
                      (-> state
                        handle-player
                        cleanup)
					; npc in cell
					npc
					  (-> state
						(handle-npc npc)
						cleanup)
					trap
					  (-> state
						handle-trigger-trap
						cleanup)
					ttl-zero
					  (-> state
						handle-ttl-zero
						cleanup)
					:default
					  (-> state
						; move item
						(assoc-in (conj fx-ks :pos) (apply rc/xy->pos (second xy-path)))
                        ; update path
						(ractors/update-actor this :xy-path rest)
						; update ttl
						(ractors/update-actor this :ttl dec))))]
      
      ; will hit wall?
      (if (second xy-path)
        (let [next-pos (apply rc/xy->pos (second xy-path))
              next-cell (rw/get-cell state next-pos)]
          (if (rw/type->collide? (get next-cell :type))
            ;; drop item into cell before hitting colliding cell
            (if (= (get cell :type) :fire)
              ; items dropped into fire add to fuel
              (rw/update-cell state pos (fn [cell] (update-in cell [:fuel] (partial + (ig/id->fuel (get item :id))))))
              ; items not dropped into fire, get added normally
              (-> state
                (rw/conj-cell-items x
                                    y
                                    (if (get item :rot-time)
                                      (assoc item :rot-time (inc (rw/get-time state)))
                                      (assoc item :count 1)))
                (rc/append-log (format "Schwaff! Thump! The dart hits %s." (rdesc/describe-cell-type next-cell)))))
            ; Nothing happened, advance item one step
            (on-move state)))
		; Nothing happened, advance item one step
		(on-move state))))))
              

(defmethod rfx/conj-effect :airborn-item [state fx-type & [item xy-path ttl]]
  (let [fx-id (rfx/fx-id)
        actor (->AirbornItemActor item xy-path ttl (rfx/fx-ks fx-id))]
    (log/info "created AirbornItemActor " item (vec xy-path) ttl (rfx/fx-ks fx-id))
    (-> state
      ; create a character fx
      (rfx/conj-fx (rfx/character-fx \- (apply rc/xy->pos (first xy-path))) fx-id)
      ; create a corresponding actor that controls the fx
      (ractors/add-actor actor))))
