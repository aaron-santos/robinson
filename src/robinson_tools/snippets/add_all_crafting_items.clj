(require
  '[robinson.itemgen :as ig]
  '[robinson.monstergen :as mg]
  '[robinson.crafting :as rcrafting])

(let [item-ids (->> ig/items
                 (filter
                   rcrafting/item-satisfies-any-recipe-clause?)
                 (map :item/id))
      rat (mg/id->monster :rat)
      rat-corpse (ig/gen-corpse rat)
      rat-bones (ig/gen-bones rat-corpse)
      rat-hide (ig/gen-hide rat-corpse)]
  (ri/add-to-inventory *state*
    (concat [rat-corpse
             rat-bones
             rat-hide]
          (map (fn [id] (assoc (ig/gen-item id) :count 50)) item-ids))))

