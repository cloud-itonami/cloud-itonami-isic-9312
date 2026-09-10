(ns sportsclub.store
  "SSoT for the sports-club-membership-governance actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/sportsclub/store_contract_test.clj), which is the whole point:
  the actor, the Membership Governor and the audit ledger never know
  which SSoT they run on.

  Like `parksafety.store`'s/`recreation.store`'s simpler entities, a
  MEMBER is acted on directly by the ONE actuation op -- no
  dynamically-filed sub-record, and the double-actuation guard checks
  a dedicated `:membership-action-finalized?` boolean rather than a
  `:status` value, the same discipline every prior governor's guards
  establish.

  The ledger stays append-only on every backend: 'which member was
  screened for an unresolved disciplinary complaint, which member's
  membership action was finalized, on what jurisdictional basis,
  approved by whom' is always a query over an immutable log -- the
  audit trail a club trusting this actor needs, and the evidence a
  member needs if a suspension/expulsion is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [sportsclub.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (member [s id])
  (all-members [s])
  (conduct-screen-of [s member-id] "committed disciplinary-complaint screening verdict for a member, or nil")
  (verify-of [s member-id] "committed jurisdiction verification, or nil")
  (ledger [s])
  (action-history [s] "the append-only membership-action history (sportsclub.registry drafts)")
  (next-sequence [s jurisdiction] "next membership-action-number sequence for a jurisdiction")
  (member-action-already-finalized? [s member-id] "has this member's membership action already been finalized?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-members [s members] "replace/seed the member directory (map id->member)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained member set so the actor + tests run offline."
  []
  {:members
   {"member-1" {:id "member-1" :member-name "Sato Kenji" :notice-reason "conduct complaint upheld at hearing"
                :days-since-suspension-notice 30 :minimum-appeal-window-days 14
                :disciplinary-complaint-unresolved? false :membership-action-finalized? false
                :jurisdiction "JPN" :status :intake}
    "member-2" {:id "member-2" :member-name "Atlantis Member" :notice-reason "scheduled review"
                :days-since-suspension-notice 30 :minimum-appeal-window-days 14
                :disciplinary-complaint-unresolved? false :membership-action-finalized? false
                :jurisdiction "ATL" :status :intake}
    "member-3" {:id "member-3" :member-name "鈴木花子" :notice-reason "conduct complaint upheld at hearing"
                :days-since-suspension-notice 5 :minimum-appeal-window-days 14
                :disciplinary-complaint-unresolved? false :membership-action-finalized? false
                :jurisdiction "JPN" :status :intake}
    "member-4" {:id "member-4" :member-name "田中太郎" :notice-reason "conduct complaint under review"
                :days-since-suspension-notice 30 :minimum-appeal-window-days 14
                :disciplinary-complaint-unresolved? true :membership-action-finalized? false
                :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-member-action!
  "Backend-agnostic `:member/mark-action-finalized` -- looks up the
  member via the protocol and drafts the membership-action record,
  and returns {:result .. :member-patch ..} for the caller to
  persist."
  [s member-id]
  (let [m (member s member-id)
        seq-n (next-sequence s (:jurisdiction m))
        result (registry/register-membership-action member-id (:jurisdiction m) seq-n)]
    {:result result
     :member-patch {:membership-action-finalized? true
                    :action-number (get result "action_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (member [_ id] (get-in @a [:members id]))
  (all-members [_] (sort-by :id (vals (:members @a))))
  (conduct-screen-of [_ id] (get-in @a [:conduct-screens id]))
  (verify-of [_ member-id] (get-in @a [:verifications member-id]))
  (ledger [_] (:ledger @a))
  (action-history [_] (:actions @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (member-action-already-finalized? [_ member-id] (boolean (get-in @a [:members member-id :membership-action-finalized?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :member/upsert
      (swap! a update-in [:members (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :conduct-screen/set
      (swap! a assoc-in [:conduct-screens (first path)] payload)

      :member/mark-action-finalized
      (let [member-id (first path)
            {:keys [result member-patch]} (finalize-member-action! s member-id)
            jurisdiction (:jurisdiction (member s member-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:members member-id] merge member-patch)
                       (update :actions registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-members [s members] (when (seq members) (swap! a assoc :members members)) s))

(defn seed-db
  "A MemStore seeded with the demo member set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :conduct-screens {} :ledger [] :sequences {}
                           :actions []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/conduct-screen payloads, ledger
  facts, membership-action records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:member/id                    {:db/unique :db.unique/identity}
   :verification/member-id       {:db/unique :db.unique/identity}
   :conduct-screen/member-id     {:db/unique :db.unique/identity}
   :ledger/seq                   {:db/unique :db.unique/identity}
   :action/seq                   {:db/unique :db.unique/identity}
   :sequence/jurisdiction        {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- member->tx [{:keys [id member-name notice-reason days-since-suspension-notice
                          minimum-appeal-window-days disciplinary-complaint-unresolved?
                          membership-action-finalized? jurisdiction status action-number]}]
  (cond-> {:member/id id}
    member-name                              (assoc :member/member-name member-name)
    notice-reason                            (assoc :member/notice-reason notice-reason)
    days-since-suspension-notice             (assoc :member/days-since-suspension-notice days-since-suspension-notice)
    minimum-appeal-window-days                (assoc :member/minimum-appeal-window-days minimum-appeal-window-days)
    (some? disciplinary-complaint-unresolved?) (assoc :member/disciplinary-complaint-unresolved? disciplinary-complaint-unresolved?)
    (some? membership-action-finalized?)        (assoc :member/membership-action-finalized? membership-action-finalized?)
    jurisdiction                                  (assoc :member/jurisdiction jurisdiction)
    status                                          (assoc :member/status status)
    action-number                                    (assoc :member/action-number action-number)))

(def ^:private member-pull
  [:member/id :member/member-name :member/notice-reason :member/days-since-suspension-notice
   :member/minimum-appeal-window-days :member/disciplinary-complaint-unresolved?
   :member/membership-action-finalized? :member/jurisdiction :member/status :member/action-number])

(defn- pull->member [m]
  (when (:member/id m)
    {:id (:member/id m) :member-name (:member/member-name m) :notice-reason (:member/notice-reason m)
     :days-since-suspension-notice (:member/days-since-suspension-notice m)
     :minimum-appeal-window-days (:member/minimum-appeal-window-days m)
     :disciplinary-complaint-unresolved? (boolean (:member/disciplinary-complaint-unresolved? m))
     :membership-action-finalized? (boolean (:member/membership-action-finalized? m))
     :jurisdiction (:member/jurisdiction m) :status (:member/status m)
     :action-number (:member/action-number m)}))

(defrecord DatomicStore [conn]
  Store
  (member [_ id]
    (pull->member (d/pull (d/db conn) member-pull [:member/id id])))
  (all-members [_]
    (->> (d/q '[:find [?id ...] :where [?e :member/id ?id]] (d/db conn))
         (map #(pull->member (d/pull (d/db conn) member-pull [:member/id %])))
         (sort-by :id)))
  (conduct-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?k :conduct-screen/member-id ?mid] [?k :conduct-screen/payload ?p]]
              (d/db conn) id)))
  (verify-of [_ member-id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?a :verification/member-id ?mid] [?a :verification/payload ?p]]
              (d/db conn) member-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (action-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :action/seq ?s] [?e :action/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (member-action-already-finalized? [s member-id]
    (boolean (:membership-action-finalized? (member s member-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :member/upsert
      (d/transact! conn [(member->tx value)])

      :verification/set
      (d/transact! conn [{:verification/member-id (first path) :verification/payload (enc payload)}])

      :conduct-screen/set
      (d/transact! conn [{:conduct-screen/member-id (first path) :conduct-screen/payload (enc payload)}])

      :member/mark-action-finalized
      (let [member-id (first path)
            {:keys [result member-patch]} (finalize-member-action! s member-id)
            jurisdiction (:jurisdiction (member s member-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(member->tx (assoc member-patch :id member-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:action/seq (count (action-history s)) :action/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-members [s members]
    (when (seq members) (d/transact! conn (mapv member->tx (vals members)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:members ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [members]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-members s members))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo member set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
