(ns sportsclub.registry
  "Pure-function membership-action record construction -- an append-
  only club book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a membership-action reference number --
  every club/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the
  same honest, non-fabricating discipline `sportsclub.facts` uses.

  `appeal-window-still-open?` is the TENTH instance of this fleet's
  MINIMUM-threshold sufficiency check family (`veterinary.registry/
  withdrawal-period-insufficient?`/`funeral.registry/waiting-period-
  elapsed?`/`hospital.registry/observation-period-elapsed?` established
  the first three, temporal; `association.registry/continuing-
  education-hours-insufficient?`/`secondary.registry/attendance-hours-
  insufficient?`/`polling.registry/sample-size-insufficient?`/
  `research.registry/replication-count-insufficient?` generalized the
  fourth through seventh, non-temporal; `personalservice.registry/
  cooling-off-period-not-elapsed?` the eighth, RETURNING to a temporal
  ground truth; `cultural.registry/practice-hours-insufficient?` the
  ninth), applying the SAME 'elapsed-time must satisfy a minimum
  before an act may proceed' shape `personalservice.registry`
  established -- but grounded in a genuinely DIFFERENT real-world
  concept: a mandatory member due-process notice/appeal period before
  a suspension or expulsion may be finalized (nonprofit membership-
  organization law: BGB §35 Anhörungsrecht, California Corporations
  Code §7341, JPN's 一般社団法人法 第25条), not a consumer cooling-off
  right. Not claimed as a new SHAPE, only a new domain application.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real club-management system. It builds the RECORD a
  club would keep, not the act of finalizing the membership action
  itself (that is `sportsclub.operation`'s `:actuation/finalize-
  membership-action`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  club operator's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn appeal-window-still-open?
  "Does `member`'s own `:days-since-suspension-notice` fall BELOW its
  own recorded `:minimum-appeal-window-days`? A pure ground-truth
  check comparing TWO permanent fields on the same entity -- see ns
  docstring for how this reuses the MINIMUM-threshold sufficiency
  family's temporal-return sub-pattern for a genuinely new domain
  concept (due-process appeal window, not consumer cooling-off)."
  [{:keys [days-since-suspension-notice minimum-appeal-window-days]}]
  (and (number? days-since-suspension-notice) (number? minimum-appeal-window-days)
       (< days-since-suspension-notice minimum-appeal-window-days)))

(defn register-membership-action
  "Validate + construct the MEMBERSHIP-ACTION registration DRAFT -- the
  club's own legal act of finalizing a member suspension or expulsion.
  Pure function -- does not touch any real club-management system; it
  builds the RECORD a club would keep. `sportsclub.governor`
  independently re-verifies the member's own disciplinary-complaint
  status and appeal-window elapse, and blocks a double-finalization
  of the same member's action, before this is ever allowed to commit."
  [member-id jurisdiction sequence]
  (when-not (and member-id (not= member-id ""))
    (throw (ex-info "membership-action: member_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "membership-action: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "membership-action: sequence must be >= 0" {})))
  (let [action-number (str (str/upper-case jurisdiction) "-MSA-" (zero-pad sequence 6))
        record {"record_id" action-number
                "kind" "membership-action-draft"
                "member_id" member-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "action_number" action-number
     "certificate" (unsigned-certificate "MembershipAction" action-number action-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
