(ns sportsclub.governor
  "Membership Governor -- the independent compliance layer that earns
  the ClubOps-LLM the right to commit. The LLM has no notion of
  jurisdictional member-safeguarding/membership-governance law,
  whether a member's own recorded disciplinary complaint has actually
  stayed unresolved, whether a member's own recorded due-process
  appeal window has actually elapsed, or when an act stops being a
  draft and becomes a real-world membership suspension or expulsion,
  so this MUST be a separate system able to *reject* a proposal and
  fall back to HOLD -- the sports-club analog of `cloud-itonami-isic-
  6512`'s `casualty.governor`.

  Five checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete evidence, an
  unresolved disciplinary complaint, a still-open appeal window, or a
  double finalization of the same member's membership action). The
  confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `sportsclub.phase`: for `:stake :actuation/finalize-membership-
  action` (a real member-record act) NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a
  human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`sportsclub.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:actuation/finalize-
                                       membership-action`, has the
                                       jurisdiction actually been
                                       assessed with a full membership-
                                       action evidence checklist
                                       (membership-conduct/safeguarding-
                                       clearance/disciplinary-hearing/
                                       appeal-notice) on file?
    3. Disciplinary complaint
       unresolved                    -- reported by THIS proposal
                                       itself (a `:conduct/screen` that
                                       just found an unresolved
                                       complaint), or already on file
                                       for the member (`:conduct/
                                       screen`/`:actuation/finalize-
                                       membership-action`). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME discipline
                                       `casualty.governor/sanctions-
                                       violations`'s original fix
                                       establishes -- an HONEST reuse
                                       of `association.governor/
                                       complaint-unresolved-violations`'s
                                       own concept (58th distinct
                                       application of this discipline
                                       overall, most recently
                                       `recreation.governor/emergency-
                                       egress-obstructed-violations` at
                                       57th), NOT claimed as new.
    4. Appeal window still open    -- for `:actuation/finalize-
                                       membership-action`, INDEPENDENTLY
                                       recompute whether the member's
                                       own `:days-since-suspension-
                                       notice` falls below its own
                                       `:minimum-appeal-window-days`
                                       (`sportsclub.registry/appeal-
                                       window-still-open?`) -- needs no
                                       proposal inspection or stored-
                                       verdict lookup at all. The TENTH
                                       instance of this fleet's
                                       MINIMUM-threshold sufficiency
                                       family, reusing `personalservice.
                                       registry/cooling-off-period-not-
                                       elapsed?`'s exact SHAPE for a
                                       genuinely different real-world
                                       concept: a nonprofit membership-
                                       organization due-process notice
                                       period, not a consumer cooling-
                                       off right.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:actuation/
                                       finalize-membership-action` (a
                                       REAL member-record act) ->
                                       escalate.

  One more guard, double-finalization prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-finalized-violations` refuses to
  finalize the SAME member's membership action twice, off a dedicated
  `:membership-action-finalized?` fact (never a `:status` value) --
  the SAME 'check a dedicated boolean, not status' discipline every
  prior governor's guards establish, informed by `cloud-itonami-isic-
  6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [sportsclub.facts :as facts]
            [sportsclub.registry :as registry]
            [sportsclub.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Finalizing a real membership suspension or expulsion is the ONE
  real-world actuation event this actor performs -- a single-member
  set, matching every other single-actuation sibling's shape."
  #{:actuation/finalize-membership-action})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "An `:eligibility/verify` (or `:actuation/finalize-membership-
  action`) proposal with no spec-basis citation is a HARD violation --
  never invent a jurisdiction's member-safeguarding/membership-
  governance requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:eligibility/verify :actuation/finalize-membership-action} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/finalize-membership-action`, the jurisdiction's
  required membership-conduct/safeguarding-clearance/disciplinary-
  hearing/appeal-notice evidence must actually be satisfied -- do not
  trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :actuation/finalize-membership-action)
    (let [m (store/member st subject)
          verification (store/verify-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction m) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(会員行動記録/セーフガーディング資格確認記録/懲戒審理記録/異議申立通知記録等)が充足していない状態での確定提案"}]))))

(defn- disciplinary-complaint-unresolved-violations
  "An unresolved disciplinary complaint -- reported by THIS proposal
  (e.g. a `:conduct/screen` that itself just found one unresolved), or
  already on file in the store for the member (`:conduct/screen`/
  `:actuation/finalize-membership-action`) -- is a HARD, un-
  overridable hold. Evaluated UNCONDITIONALLY (not scoped to a
  specific op) so the screening op itself can HARD-hold on its own
  finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (true? (get-in proposal [:value :complaint-unresolved?]))
        member-id (when (contains? #{:conduct/screen :actuation/finalize-membership-action} op) subject)
        hit-on-file? (and member-id (:disciplinary-complaint-unresolved? (store/member st member-id)))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :disciplinary-complaint-unresolved
        :detail "懲戒苦情が未解決の状態での会員資格処分確定提案は進められない"}])))

(defn- appeal-window-still-open-violations
  "For `:actuation/finalize-membership-action`, INDEPENDENTLY recompute
  whether the member's own days-since-suspension-notice falls below
  its own minimum-appeal-window-days via `sportsclub.registry/appeal-
  window-still-open?` -- needs no proposal inspection or stored-
  verdict lookup at all, an honest reuse of `personalservice.
  registry`'s own MINIMUM-threshold sufficiency shape for a genuinely
  different domain concept (due-process appeal window)."
  [{:keys [op subject]} st]
  (when (= op :actuation/finalize-membership-action)
    (let [m (store/member st subject)]
      (when (registry/appeal-window-still-open? m)
        [{:rule :appeal-window-still-open
          :detail (str subject " の異議申立期間経過日数(" (:days-since-suspension-notice m)
                      ")が最低異議申立期間(" (:minimum-appeal-window-days m) ")に未達")}]))))

(defn- already-finalized-violations
  "For `:actuation/finalize-membership-action`, refuses to finalize the
  SAME member's membership action twice, off a dedicated
  `:membership-action-finalized?` fact -- see ns docstring for why
  this sidesteps the status-lifecycle risk `cloud-itonami-isic-6492`'s
  ADR-0001 documents."
  [{:keys [op subject]} st]
  (when (= op :actuation/finalize-membership-action)
    (when (store/member-action-already-finalized? st subject)
      [{:rule :already-finalized
        :detail (str subject " の会員資格処分は既に確定済み")}])))

(defn check
  "Censors a ClubOps-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (disciplinary-complaint-unresolved-violations request proposal st)
                           (appeal-window-still-open-violations request st)
                           (already-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
