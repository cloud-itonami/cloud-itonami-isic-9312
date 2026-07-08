(ns sportsclub.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean member through
  intake -> jurisdiction verification -> disciplinary-complaint
  screening -> membership-action finalization proposal (always
  escalates) -> human approval -> commit, then shows four HARD holds
  (a jurisdiction with no spec-basis, a member whose own recorded
  appeal window has NOT yet elapsed, a member whose own recorded
  disciplinary complaint has NOT been resolved [screened directly via
  `:conduct/screen` -- never via an actuation op against an
  unscreened member -- see this actor's own governor ns docstring /
  the lesson `parksafety`'s ADR-2607071922 Decision 5, `eldercare`'s,
  `museum`'s, `conservation`'s, `salon`'s, `entertainment`'s,
  `casework`'s, `hospital`'s, `facility`'s, `school`'s, `association`'s,
  `leasing`'s, `behavioral`'s, `secondary`'s, `card`'s, `water`'s,
  `telecom`'s, `aerospace`'s, `recovery`'s, `consulting`'s, `union`'s,
  `congregation`'s, `fab`'s, `energy`'s, `care`'s, `navigator`'s,
  `learning`'s, `banking`'s, `advertising`'s, `polling`'s, `research`'s,
  `design`'s, `nursing`'s, `sports`'s, `alliedhealth`'s, `laundry`'s,
  `holdco`'s, `photo`'s, `personalservice`'s, `edsupport`'s,
  `headoffice`'s, `residential`'s, `cultural`'s, `reserve`'s,
  `proserv`'s, `sportsevent`'s and `recreation`'s ADR-0001s already
  recorded], and a double finalization of an already-processed member)
  that never reach a human at all, and prints the audit ledger + the
  draft membership-action records."
  (:require [langgraph.graph :as g]
            [sportsclub.store :as store]
            [sportsclub.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :club-officer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== member/intake member-1 (JPN, clean; appeal window elapsed, complaint resolved) ==")
    (println (exec! actor "t1" {:op :member/intake :subject "member-1"
                                :patch {:id "member-1" :member-name "Sato Kenji"}} operator))

    (println "== eligibility/verify member-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :eligibility/verify :subject "member-1"} operator))
    (println (approve! actor "t2"))

    (println "== conduct/screen member-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :conduct/screen :subject "member-1"} operator))
    (println (approve! actor "t3"))

    (println "== actuation/finalize-membership-action member-1 (always escalates -- actuation/finalize-membership-action) ==")
    (let [r (exec! actor "t4" {:op :actuation/finalize-membership-action :subject "member-1"} operator)]
      (println r)
      (println "-- human club officer approves --")
      (println (approve! actor "t4")))

    (println "== eligibility/verify member-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :eligibility/verify :subject "member-2" :no-spec? true} operator))

    (println "== eligibility/verify member-3 (escalates -- human approves; sets up the appeal-window test) ==")
    (println (exec! actor "t6" {:op :eligibility/verify :subject "member-3"} operator))
    (println (approve! actor "t6"))

    (println "== actuation/finalize-membership-action member-3 (appeal window 5 days < minimum 14 days -> HARD hold) ==")
    (println (exec! actor "t7" {:op :actuation/finalize-membership-action :subject "member-3"} operator))

    (println "== conduct/screen member-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :conduct/screen :subject "member-4"} operator))

    (println "== actuation/finalize-membership-action member-1 AGAIN (double-finalization -> HARD hold) ==")
    (println (exec! actor "t9" {:op :actuation/finalize-membership-action :subject "member-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft membership-action records ==")
    (doseq [r (store/action-history db)] (println r))))
