(ns sportsclub.governor-contract-test
  "The governor contract as executable tests -- the sports-club analog
  of `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`.
  The single invariant under test:

    ClubOps-LLM never finalizes a membership action the Membership
    Governor would reject, `:actuation/finalize-membership-action`
    NEVER auto-commits at any phase, `:member/intake` (no direct
    capital risk) MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [sportsclub.store :as store]
            [sportsclub.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :club-officer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving a membership-
  governance evidence checklist on file. Uses distinct thread-ids per
  call site by suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :eligibility/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :member/intake :subject "member-1"
                   :patch {:id "member-1" :member-name "Sato Kenji"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sato Kenji" (:member-name (store/member db "member-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest eligibility-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :eligibility/verify :subject "member-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/verify-of db "member-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "an eligibility/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :eligibility/verify :subject "member-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/verify-of db "member-1")) "no verification written"))))

(deftest finalize-membership-action-without-verify-is-held
  (testing "actuation/finalize-membership-action before any eligibility verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/finalize-membership-action :subject "member-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest appeal-window-still-open-is-held
  (testing "a member whose own recorded appeal window has not yet elapsed -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "member-3")
          res (exec-op actor "t5" {:op :actuation/finalize-membership-action :subject "member-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:appeal-window-still-open} (-> (store/ledger db) last :basis)))
      (is (empty? (store/action-history db))))))

(deftest disciplinary-complaint-unresolved-is-held-and-unoverridable
  (testing "an unresolved disciplinary complaint on a member -> HOLD, and never reaches request-approval -- exercised via :conduct/screen DIRECTLY, not via the actuation op against an unscreened member (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's, conservation's, salon's, entertainment's, casework's, hospital's, facility's, school's, association's, leasing's, behavioral's, secondary's, card's, water's, telecom's, aerospace's, recovery's, consulting's, union's, congregation's, fab's, energy's, care's, navigator's, learning's, banking's, advertising's, polling's, research's, design's, nursing's, sports's, alliedhealth's, laundry's, holdco's, photo's, personalservice's, edsupport's, headoffice's, residential's, cultural's, reserve's, proserv's, sportsevent's and recreation's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :conduct/screen :subject "member-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:disciplinary-complaint-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conduct-screen-of db "member-4")) "no clearance written"))))

(deftest finalize-membership-action-always-escalates-then-human-decides
  (testing "a clean, fully-assessed member still ALWAYS interrupts for human approval -- actuation/finalize-membership-action is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "member-1")
          r1 (exec-op actor "t7" {:op :actuation/finalize-membership-action :subject "member-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, membership-action record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:membership-action-finalized? (store/member db "member-1"))))
          (is (= 1 (count (store/action-history db))) "one draft membership-action record"))))))

(deftest double-finalization-is-held
  (testing "finalizing the same member's membership action twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "member-1")
          _ (exec-op actor "t8a" {:op :actuation/finalize-membership-action :subject "member-1"} operator)
          _ (approve! actor "t8a")
          res (exec-op actor "t8" {:op :actuation/finalize-membership-action :subject "member-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-finalized} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/action-history db))) "still only the one earlier action"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :member/intake :subject "member-1"
                          :patch {:id "member-1" :member-name "Sato Kenji"}} operator)
      (exec-op actor "b" {:op :eligibility/verify :subject "member-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
