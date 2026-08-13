(ns sportsclub.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-9312`: this
  repo had NO demo page and no generator at all. Every id, status,
  verdict, rule and number on the page is produced by RUNNING this
  repo's real actor -- the compiled `langgraph.graph` StateGraph built
  by `sportsclub.operation/build` (`:intake -> :advise -> :govern ->
  :decide -+-> :commit | :request-approval -> :commit | :hold`), the
  independent `sportsclub.governor`, the `sportsclub.phase` rollout
  gate and the append-only `sportsclub.store` ledger. Nothing is
  hand-typed telemetry.

  Three things on this page are deliberately NOT hand-maintained, so
  they cannot drift away from the code:

    - the op-contract table is derived at render time from
      `phase/phases`, `phase/write-ops`, `phase/read-ops` and
      `governor/high-stakes`;
    - the jurisdiction-coverage table is `facts/coverage` called on the
      jurisdictions the seeded members actually carry;
    - the approver-attribution disclosure is a RENDER-TIME SCAN of the
      durable registers for approver-shaped keys and for the literal
      approver id we submitted. It is never a hard-coded claim that
      'this repo loses the approver' -- if someone fixes the store, the
      page reports the fix on the next build.

  CLASSIFYING A REFUSAL. This page distinguishes three things that all
  end in a HOLD, and the distinction is not `:violations`:

    - a HARD governor refusal   -- `:t :governor-hold`, no
                                   `:phase-reason`, non-empty
                                   `:violations` from
                                   `governor/check`'s five HARD checks.
    - a phase/rollout gate hold -- ALSO `:t :governor-hold` (the
                                   `:decide` node reuses
                                   `governor/hold-fact` for it), but it
                                   carries `:phase-reason
                                   :phase-disabled` and its
                                   `:violations` are EMPTY. The
                                   governor was clean; the rollout
                                   phase simply had not enabled the op.
    - an approver rejection     -- `:t :approval-rejected`, and it
                                   CARRIES a violation
                                   (`{:rule :approver-rejected}`)
                                   synthesised by
                                   `operation/build`'s
                                   `:request-approval` node. A human
                                   said no; the governor did not.

  So the classifier keys on the FACT TYPE first and only then on
  `:phase-reason`/`:violations`. Keying on `:violations` alone would
  count the approver rejection as a governor refusal; keying on `:t`
  alone would count the phase-gate hold as one.

  DETERMINISM. This repo's actor stamps no UUID and no wall-clock value
  onto any state channel, and this renderer introduces none: no
  `random-uuid`, no `System/currentTimeMillis`, no date stamp. Every
  collection rendered is either an append-ordered vector (the ledger,
  the action history) or explicitly sorted. Two builds are byte-
  identical; check it by rendering twice into a scratch directory and
  diffing:

    D=$(mktemp -d)
    clojure -M:dev:render-html $D/a.html
    clojure -M:dev:render-html $D/b.html
    diff $D/a.html $D/b.html && echo deterministic

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [sportsclub.facts :as facts]
            [sportsclub.governor :as governor]
            [sportsclub.operation :as operation]
            [sportsclub.phase :as phase]
            [sportsclub.store :as store]))

;; ----------------------------- the two identities ---------------------------
;; Deliberately DIFFERENT strings. A ledger `:committed` fact carries
;; `:actor`, which `operation/commit-fact` fills from `(:actor-id
;; context)` -- the EXECUTING actor, never the approver. If the demo used
;; one id for both, a renderer that mistakenly read `:actor` as "who
;; approved this" would print the right answer for the wrong reason and
;; nobody could tell. Keeping them distinct makes that bug visible.

(def ^:private executing-actor-id "op-1")
(def ^:private approver-id "officer-hanako")

(defn- context-for [phase]
  {:actor-id executing-actor-id :actor-role :club-officer :phase phase})

;; ----------------------------- scenarios ------------------------------------

(def ^:private scenarios
  "Every scenario the console shows, in render order and in dependency
  order (a finalization needs its jurisdiction verification committed
  first; the double-finalization refusal needs the first finalization
  to have landed). Every member id is seeded in
  `store/demo-data` -- there are no invented entities on this page.

  Between them these 13 runs exercise ALL FIVE of `governor/check`'s
  HARD rules exactly once each, plus the two look-alike holds the
  classifier has to keep apart."
  [{:tid "s01" :phase 3
    :label "member/intake member-1 at phase 3 -- the only auto-commit in the actor"
    :request {:op :member/intake :subject "member-1"
              :patch {:id "member-1" :member-name "Sato Kenji"}}}

   {:tid "s02" :phase 2
    :label "member/intake member-2 at phase 2 -- same op, but phase 2 has an empty :auto set"
    :request {:op :member/intake :subject "member-2"
              :patch {:id "member-2" :member-name "Atlantis Member"}}
    :approval {:status :approved :by approver-id}}

   {:tid "s03" :phase 1
    :label "eligibility/verify member-1 at phase 1 -- ROLLOUT GATE hold, not a refusal"
    :request {:op :eligibility/verify :subject "member-1"}}

   {:tid "s04" :phase 3
    :label "eligibility/verify member-1 (JPN) -- governor clean, human approves"
    :request {:op :eligibility/verify :subject "member-1"}
    :approval {:status :approved :by approver-id}}

   {:tid "s05" :phase 3
    :label "conduct/screen member-1 -- no unresolved complaint, human approves"
    :request {:op :conduct/screen :subject "member-1"}
    :approval {:status :approved :by approver-id}}

   {:tid "s06" :phase 3
    :label "actuation/finalize-membership-action member-1 -- always escalates, human approves"
    :request {:op :actuation/finalize-membership-action :subject "member-1"}
    :approval {:status :approved :by approver-id}}

   {:tid "s07" :phase 3
    :label "eligibility/verify member-2 (ATL) -- HARD refusal: no official spec-basis"
    :request {:op :eligibility/verify :subject "member-2" :no-spec? true}}

   {:tid "s08" :phase 3
    :label "actuation/finalize-membership-action member-2 -- HARD refusal: evidence never assessed"
    :request {:op :actuation/finalize-membership-action :subject "member-2"}}

   {:tid "s09" :phase 3
    :label "eligibility/verify member-3 (JPN) -- governor clean, human approves"
    :request {:op :eligibility/verify :subject "member-3"}
    :approval {:status :approved :by approver-id}}

   {:tid "s10" :phase 3
    :label "conduct/screen member-3 -- governor clean, human REJECTS"
    :request {:op :conduct/screen :subject "member-3"}
    :approval {:status :rejected :by approver-id}}

   {:tid "s11" :phase 3
    :label "actuation/finalize-membership-action member-3 -- HARD refusal: appeal window still open"
    :request {:op :actuation/finalize-membership-action :subject "member-3"}}

   {:tid "s12" :phase 3
    :label "conduct/screen member-4 -- HARD refusal: unresolved disciplinary complaint"
    :request {:op :conduct/screen :subject "member-4"}}

   {:tid "s13" :phase 3
    :label "actuation/finalize-membership-action member-1 AGAIN -- HARD refusal: already finalized"
    :request {:op :actuation/finalize-membership-action :subject "member-1"}}])

;; ----------------------------- driving the real actor -----------------------

(defn- run-scenario!
  "One scenario = one (possibly resumed) run of the compiled graph.

  The ledger index range `[before after)` is the join key between a run
  and the ledger fact it produced. It is EXACT: the graph writes the
  ledger only from its two terminal nodes (`:commit` / `:hold`), one
  fact each, so a scenario contributes 0 or 1 facts and the range names
  them unambiguously. A join on [op subject] would be ambiguous here --
  member-1 is the subject of five different runs and
  `:actuation/finalize-membership-action` is the op of four."
  [actor db {:keys [tid label phase request approval]}]
  (let [before (count (store/ledger db))
        r1     (g/run* actor {:request request :context (context-for phase)}
                       {:thread-id tid})
        paused? (= :interrupted (:status r1))
        during (count (store/ledger db))
        r2     (when (and paused? approval)
                 (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final  (or r2 r1)
        st     (:state final)
        after  (count (store/ledger db))]
    {:tid tid :label label :phase phase
     :op (:op request) :subject (:subject request)
     :no-spec? (boolean (:no-spec? request))
     :paused? paused?
     :paused-at (when paused? (vec (:frontier r1)))
     :ledger-writes-while-paused (- during before)
     :approval approval
     :status (:status final)
     :frontier (vec (:frontier final))
     :disposition (:disposition st)
     :verdict (:verdict st)
     :proposal (:proposal st)
     :record (:record st)
     :audit (vec (:audit st))
     :ledger-range [before after]
     :ledger-facts (subvec (vec (store/ledger db)) before after)}))

(defn run-demo!
  "Build a seeded MemStore + the real compiled actor and drive every
  scenario through it. Returns {:db .. :receipts .. :seed ..}."
  []
  (let [db    (store/seed-db)
        seed  (vec (store/all-members db))
        actor (operation/build db)]
    {:db db
     :seed seed
     :receipts (mapv #(run-scenario! actor db %) scenarios)}))

;; ----------------------------- classification -------------------------------
;; Fact type FIRST. See the ns docstring for why `:violations` alone is
;; not a safe discriminator here.

(defn- commit-fact? [f] (= :committed (:t f)))
(defn- approver-rejection? [f] (= :approval-rejected (:t f)))

(defn- phase-gate-hold?
  "A hold produced by the rollout gate, not by the governor. The
  `:decide` node reuses `governor/hold-fact` for it, so it looks like a
  governor hold except that it carries `:phase-reason` (only
  `phase/gate` sets one) and its `:violations` are empty."
  [f]
  (and (= :governor-hold (:t f)) (some? (:phase-reason f))))

(defn- hard-governor-hold?
  "A genuine refusal by `sportsclub.governor`: the fact the `:decide`
  node wrote for a `:hold` disposition that the GOVERNOR produced (no
  `:phase-reason`), carrying at least one of the five HARD rules."
  [f]
  (and (= :governor-hold (:t f))
       (nil? (:phase-reason f))
       (boolean (seq (:violations f)))))

(defn- hold-fact? [f] (not (commit-fact? f)))

(defn- rules-of [f] (mapv :rule (:violations f)))

;; ----------------------------- approver attribution -------------------------
;; MEASURED at render time, never asserted. `approver-hits` walks a
;; register's actual contents; `mentions-approver?` looks for the
;; literal id we submitted. Neither reads the store's source.

(defn- approver-shaped-key? [k]
  (and (or (keyword? k) (string? k))
       (str/includes? (str/lower-case (name k)) "approv")))

(defn- approver-hits
  "Every approver-shaped [key value] pair anywhere inside `x`, sorted."
  [x]
  (let [hits (volatile! [])]
    (walk/postwalk
     (fn [node]
       (when (map? node)
         (doseq [[k v] node]
           (when (approver-shaped-key? k) (vswap! hits conj [k v]))))
       node)
     x)
    (vec (sort-by (comp str first) (distinct @hits)))))

(defn- mentions-approver?
  "Does the literal approver id appear anywhere inside `x`?"
  [x]
  (let [found (volatile! false)]
    (walk/postwalk (fn [n] (when (= n approver-id) (vreset! found true)) n) x)
    @found))

(def ^:private effect->register
  "Which durable register each committed effect lands in. This is the
  STRUCTURE of `store/commit-record!`'s case dispatch; whether the
  approver SURVIVES into that register is measured separately below."
  {:member/upsert                :members
   :verification/set             :verifications
   :conduct-screen/set           :conduct-screens
   :member/mark-action-finalized :actions})

(defn- registers
  "Snapshot every durable register by protocol call only."
  [db]
  (let [ids (mapv :id (store/all-members db))]
    {:members         (vec (store/all-members db))
     :verifications   (into (sorted-map)
                            (for [id ids :let [v (store/verify-of db id)] :when v] [id v]))
     :conduct-screens (into (sorted-map)
                            (for [id ids :let [v (store/conduct-screen-of db id)] :when v] [id v]))
     :actions         (vec (store/action-history db))
     :ledger          (vec (store/ledger db))}))

(defn- attribution-scan
  "For every run where a human actually approved a commit: which
  register did the effect land in, and did the approver id survive into
  it? Purely a scan of the post-run store."
  [db receipts]
  (let [regs (registers db)]
    (vec
     (for [r receipts
           :when (and (= :approved (get-in r [:approval :status]))
                      (= :commit (:disposition r)))
           :let [effect (get-in r [:proposal :effect])
                 reg-k  (effect->register effect)
                 reg    (get regs reg-k)]]
       {:label (:label r) :tid (:tid r) :subject (:subject r) :op (:op r)
        :effect effect :register reg-k
        :submitted-by (get-in r [:approval :by])
        :hits (approver-hits reg)
        :retained? (mentions-approver? reg)}))))

(defn- ledger-attribution
  "The ledger's own answer to 'who approved this', measured. Every
  `:committed` fact carries `:actor`; this reports whether that field
  ever equals the approver (it must not -- it is the executing actor)
  and whether the ledger holds the approver under any key at all."
  [db]
  (let [ledger (vec (store/ledger db))
        commits (filterv commit-fact? ledger)]
    {:commit-facts (count commits)
     :distinct-actors (vec (sort (distinct (keep :actor commits))))
     :approver-shaped-keys (approver-hits ledger)
     :mentions-approver? (mentions-approver? ledger)}))

;; ----------------------------- derived contract tables ----------------------

(defn- op-contract
  "The commit gate for every op, derived from `phase/*` and
  `governor/high-stakes` at render time."
  []
  (let [phs (vec (sort (keys phase/phases)))]
    (vec
     (for [op (sort-by str (concat (sort-by str phase/read-ops)
                                   (sort-by str phase/write-ops)))]
       {:op op
        :kind (if (contains? phase/read-ops op) :read :write)
        :write-phases (filterv #(contains? (:writes (phase/phases %)) op) phs)
        :auto-phases  (filterv #(contains? (:auto   (phase/phases %)) op) phs)
        :high-stakes? (contains? governor/high-stakes op)}))))

(defn- coverage-report
  "`facts/coverage` over the jurisdictions the SEEDED members actually
  carry -- the honest answer to 'can this actor act here at all'."
  [seed]
  (facts/coverage (vec (sort (distinct (keep :jurisdiction seed))))))

;; ----------------------------- html helpers ---------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (cond (keyword? v) (name v) (nil? v) "" :else (str v)))
(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))
(defn- dash [] "<span class=\"muted\">&mdash;</span>")
(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- bad [s] (str "<span class=\"critical\">" s "</span>"))
(defn- yn [b] (if b (esc "true") (esc "false")))

(defn- cells [tag xs] (str/join (map #(str "<" tag ">" % "</" tag ">") xs)))
(defn- row [& xs] (str "        <tr>" (cells "td" xs) "</tr>"))

(defn- section [id title lead headers body-rows]
  (str "  <section class=\"dds-ext-card\" id=\"" id "\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"lead\">" lead "</p>\n"
       (if (seq body-rows)
         (str "    <div class=\"scroll\">\n"
              "    <table>\n"
              "      <thead><tr>" (cells "th" headers) "</tr></thead>\n"
              "      <tbody>\n" (str/join "\n" body-rows) "\n      </tbody>\n"
              "    </table>\n"
              "    </div>\n")
         "    <p class=\"muted\">(no rows)</p>\n")
       "  </section>\n"))

(defn- prose-section [id title & paragraphs]
  (str "  <section class=\"dds-ext-card\" id=\"" id "\">\n"
       "    <h2>" title "</h2>\n"
       (str/join "\n" (map #(str "    <p>" % "</p>") paragraphs)) "\n"
       "  </section>\n"))

(defn- stat [label value note]
  (str "    <div class=\"stat\"><span class=\"stat-v\">" (esc value) "</span>"
       "<span class=\"stat-l\">" label "</span>"
       "<span class=\"stat-n\">" note "</span></div>\n"))

;; ----------------------------- ground truth for a refusal -------------------

(defn- ground-truth
  "The seed/store fact a HARD rule fired on, read back out of the store
  by protocol call at render time. Never a restatement of the rule."
  [db rule subject]
  (let [m (store/member db subject)]
    (case rule
      :no-spec-basis
      (str "jurisdiction " (code (:jurisdiction m)) " &rarr; "
           (if (facts/spec-basis (:jurisdiction m))
             (ok "in facts/catalog")
             (bad "absent from facts/catalog")))

      :evidence-incomplete
      (str "store/verify-of " (code subject) " &rarr; "
           (if-let [v (store/verify-of db subject)]
             (str (esc (count (:checklist v))) " checklist items on file")
             (bad "nil &middot; never assessed")))

      :disciplinary-complaint-unresolved
      (str (code :disciplinary-complaint-unresolved?) " = "
           (if (:disciplinary-complaint-unresolved? m) (bad "true") (ok "false")))

      :appeal-window-still-open
      (str (code :days-since-suspension-notice) " = "
           (esc (:days-since-suspension-notice m)) " &lt; "
           (code :minimum-appeal-window-days) " = "
           (esc (:minimum-appeal-window-days m)))

      :already-finalized
      (str (code :membership-action-finalized?) " = "
           (if (:membership-action-finalized? m) (bad "true") (ok "false"))
           (when-let [a (:action-number m)] (str " &middot; " (code a))))

      (dash))))

;; ----------------------------- page -----------------------------------------

(def ^:private app-css "
main{max-width:76rem;margin:0 auto;padding:1.5rem 1.25rem 4rem}
header.hero{max-width:76rem;margin:0 auto;padding:2.5rem 1.25rem 1rem}
header.hero h1{margin:0 0 .35rem}
.dds-ext-card{margin:1.25rem 0;padding:1.25rem 1.35rem;border:1px solid var(--color-neutral-solid-gray-300,#949495);border-radius:8px;background:var(--color-neutral-white,#fff)}
.dds-ext-card h2{margin:0 0 .35rem;font-size:1.15rem}
p.lead{margin:0 0 .9rem;color:var(--color-neutral-solid-gray-700,#626264);font-size:.9rem;line-height:1.7}
.scroll{overflow-x:auto}
table{border-collapse:collapse;width:100%;font-size:.83rem}
th,td{border-bottom:1px solid var(--color-neutral-solid-gray-200,#d8d8db);padding:.5rem .55rem;text-align:left;vertical-align:top}
th{background:var(--color-neutral-solid-gray-50,#f2f2f2);font-weight:700;white-space:nowrap}
tbody tr:hover{background:var(--color-key-50,#f0f4ff)}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.92em;background:var(--color-neutral-solid-gray-50,#f2f2f2);padding:.1em .35em;border-radius:3px;white-space:nowrap}
.ok{color:var(--color-primitive-green-800,#197a4b);font-weight:700}
.warn{color:var(--color-primitive-orange-900,#9c4d00);font-weight:700}
.critical{color:var(--color-primitive-red-800,#c8000f);font-weight:700}
.muted{color:var(--color-neutral-solid-gray-600,#767678)}
.stats{display:flex;flex-wrap:wrap;gap:.75rem;margin:1rem 0 0}
.stat{flex:1 1 8.5rem;border:1px solid var(--color-neutral-solid-gray-300,#949495);border-radius:8px;padding:.7rem .8rem;background:var(--color-neutral-white,#fff)}
.stat-v{display:block;font-size:1.6rem;font-weight:700;line-height:1.1}
.stat-l{display:block;font-size:.8rem;font-weight:700;margin-top:.15rem}
.stat-n{display:block;font-size:.72rem;color:var(--color-neutral-solid-gray-600,#767678);margin-top:.2rem;line-height:1.5}
footer{max-width:76rem;margin:0 auto;padding:1.5rem 1.25rem 3rem;font-size:.82rem;color:var(--color-neutral-solid-gray-600,#767678)}
")

(defn- scenario-row [r]
  (row (code (:tid r))
       (esc (:label r))
       (esc (:phase r))
       (code (:op r))
       (code (:subject r))
       (if (:paused? r)
         (str (warn "interrupted") " at " (str/join ", " (map code (:paused-at r)))
              "<br><span class=\"muted\">ledger writes while paused: </span>"
              (if (zero? (:ledger-writes-while-paused r))
                (ok "0") (bad (esc (:ledger-writes-while-paused r)))))
         (str (esc (kw (:status r))) " " (dash)))
       (let [v (:verdict r)]
         (str "hard? " (if (:hard? v) (bad "true") (ok "false"))
              "<br>escalate? " (esc (yn (:escalate? v)))
              "<br>high-stakes? " (esc (yn (:high-stakes? v)))
              "<br>confidence " (code (:confidence v))))
       (if-let [a (:approval r)]
         (str (esc (kw (:status a))) " by " (code (:by a)))
         (dash))
       (case (:disposition r)
         :commit (ok "commit")
         :hold   (bad "hold")
         (warn (esc (kw (:disposition r)))))))

(defn- seed-row [m]
  (row (code (:id m))
       (esc (:member-name m))
       (code (:jurisdiction m))
       (esc (:notice-reason m))
       (esc (:days-since-suspension-notice m))
       (esc (:minimum-appeal-window-days m))
       (if (:disciplinary-complaint-unresolved? m) (bad "true") (ok "false"))
       (if (:membership-action-finalized? m) (warn "true") (esc "false"))))

(defn- final-member-row [m]
  (row (code (:id m))
       (code (:jurisdiction m))
       (if (:membership-action-finalized? m) (warn "finalized") (esc "not finalized"))
       (if-let [a (:action-number m)] (code a) (dash))
       (esc (kw (:status m)))
       (if (:disciplinary-complaint-unresolved? m) (bad "true") (ok "false"))))

(defn- hard-row [db r f]
  (row (code (:tid r))
       (esc (:label r))
       (code (:op r))
       (code (:subject r))
       (str/join "<br>" (map #(bad (esc (kw (:rule %)))) (:violations f)))
       (str/join "<br>" (map #(esc (:detail %)) (:violations f)))
       (str/join "<br>" (map #(ground-truth db (:rule %) (:subject r)) (:violations f)))))

(defn- phase-hold-row [r f]
  (row (code (:tid r))
       (esc (:label r))
       (esc (:phase r))
       (code (:op r))
       (code (:phase-reason f))
       (if (empty? (:violations f))
         (ok "[] &middot; the governor was clean")
         (bad (esc (pr-str (rules-of f)))))
       (if (hard-governor-hold? f) (bad "MISCLASSIFIED") (ok "not a governor refusal"))))

(defn- rejection-row [r f]
  (row (code (:tid r))
       (esc (:label r))
       (code (:op r))
       (code (get-in r [:approval :by]))
       (code (:t f))
       (if (seq (:violations f))
         (warn (esc (pr-str (rules-of f))))
         (dash))
       (if (hard-governor-hold? f) (bad "MISCLASSIFIED") (ok "not a governor refusal"))))

(defn- escalation-row [r]
  (let [ask (last (filter #(= :approval-requested (:t %)) (:audit r)))]
    (row (code (:tid r))
         (esc (:label r))
         (esc (:phase r))
         (code (:op r))
         (code (:reason ask))
         (code (:confidence ask))
         (if (zero? (:ledger-writes-while-paused r))
           (ok "0") (bad (esc (:ledger-writes-while-paused r))))
         (if-let [a (:approval r)] (esc (kw (:status a))) (warn "never resumed")))))

(defn- contract-row [{:keys [op kind write-phases auto-phases high-stakes?]}]
  (row (code op)
       (esc (name kind))
       (if (seq write-phases) (esc (str/join ", " write-phases)) (bad "never"))
       (cond
         high-stakes?     (warn "NEVER auto &middot; governor high-stakes + absent from every :auto set")
         (seq auto-phases) (ok (str "auto-commits at phase " (esc (str/join ", " auto-phases)) " when clean"))
         :else            (warn "human approval at every phase"))
       (if high-stakes? (bad "yes") (esc "no"))))

(defn- attribution-row [{:keys [tid label subject op effect register submitted-by hits retained?]}]
  (row (code tid)
       (esc label)
       (code subject)
       (code op)
       (code effect)
       (code register)
       (code submitted-by)
       (if (seq hits)
         (str/join "<br>" (map (fn [[k v]] (str (code k) " = " (code v))) hits))
         (dash))
       (if retained? (ok "retained") (bad "LOST"))))

(defn- ledger-row [i f]
  (row (esc (inc i))
       (cond (commit-fact? f)       (ok "commit")
             (hard-governor-hold? f) (bad "HARD governor refusal")
             (phase-gate-hold? f)   (warn "phase gate")
             (approver-rejection? f) (warn "approver rejection")
             :else                  (warn (esc (kw (:t f)))))
       (code (:t f))
       (code (:op f))
       (code (:subject f))
       (code (:actor f))
       (if-let [pr* (:phase-reason f)] (code pr*) (dash))
       (if (seq (:violations f)) (esc (pr-str (rules-of f))) (dash))
       (if-let [s (:summary f)] (esc s) (dash))))

(defn- coverage-row [iso3 covered?]
  (let [sb (facts/spec-basis iso3)]
    (row (code iso3)
         (if covered? (ok "spec-basis on file") (bad "NO spec-basis"))
         (if sb (esc (:name sb)) (dash))
         (if sb (esc (:owner-authority sb)) (dash))
         (if sb (esc (count (:required-evidence sb))) (dash))
         (if sb (str "<code>" (esc (:provenance sb)) "</code>") (dash)))))

(defn render
  "The whole document. Pure: takes the run result, returns a string."
  [{:keys [db seed receipts]}]
  (let [ledger      (vec (store/ledger db))
        hard-facts  (filterv hard-governor-hold? ledger)
        phase-facts (filterv phase-gate-hold? ledger)
        rej-facts   (filterv approver-rejection? ledger)
        commits     (filterv commit-fact? ledger)
        hard-rules  (vec (sort (map str (distinct (mapcat rules-of hard-facts)))))
        by-fact     (into {} (for [r receipts, f (:ledger-facts r)] [f r]))
        attribution (attribution-scan db receipts)
        led-attr    (ledger-attribution db)
        cov         (coverage-report seed)
        escalated   (filterv :paused? receipts)
        actions     (vec (store/action-history db))
        regs        (registers db)]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<meta name=\"theme-color\" content=\"#ffffff\">\n"
     "<title>cloud-itonami-isic-9312 &middot; ClubOps-LLM &#8867; Membership Governor &mdash; operator console</title>\n"
     "<meta name=\"description\" content=\"Build-time console generated by running the real sportsclub OperationActor: "
     (esc (count receipts)) " scenarios, " (esc (count hard-facts))
     " HARD governor refusals, " (esc (count commits)) " commits.\">\n"
     "<style>\n" (skin/dds+skin) "\n" app-css "</style>\n"
     "</head>\n<body>\n"

     "<header class=\"hero\">\n"
     "  <p class=\"muted\"><code>cloud-itonami-isic-9312</code> &middot; ISIC rev.5 9312 &middot; Activities of sports clubs</p>\n"
     "  <h1>ClubOps-LLM &#8867; Membership Governor &mdash; operator console</h1>\n"
     "  <p class=\"lead\">Every row below was produced by <strong>running this repository's actor</strong> at build time: the compiled "
     "<code>langgraph.graph</code> StateGraph from <code>sportsclub.operation/build</code>, over the seeded member directory in "
     "<code>sportsclub.store/demo-data</code>. Nothing here is illustrative, hand-typed or mocked. Regenerate with "
     "<code>clojure -M:render-html</code>; the build <strong>refuses to write this file</strong> unless the run produced at least one HARD governor refusal.</p>\n"
     "  <div class=\"stats\">\n"
     (stat "scenarios" (count receipts) "full graph runs")
     (stat "ledger facts" (count ledger) "append-only, one per run")
     (stat "HARD refusals" (count hard-facts)
           (str "rules: " (esc (str/join ", " hard-rules))))
     (stat "phase-gate holds" (count phase-facts) "rollout, <em>not</em> the governor")
     (stat "approver rejections" (count rej-facts) "a human said no")
     (stat "commits" (count commits) "reached the SSoT")
     "  </div>\n"
     "</header>\n<main>\n"

     (section
      "seed" "Seeded member directory &mdash; the ground truth every row traces back to"
      (str "Read out of the store with <code>store/all-members</code> before the first run. "
           "These members are the ENTIRE universe of entities on this page; no id below was invented. "
           "The last four columns are the facts the governor's HARD checks recompute independently of anything the advisor says.")
      ["member" "name" "jurisdiction" "notice reason" "days since notice" "min appeal window"
       "complaint unresolved?" "action finalized?"]
      (map seed-row seed))

     (section
      "runs" (str "Scenario runs &mdash; " (count receipts) " full passes through the compiled graph")
      (str "Each row is one <code>langgraph.graph/run*</code>. <code>interrupted</code> means "
           "<code>interrupt-before #{:request-approval}</code> actually paused the graph and handed the decision to a human; "
           "the ledger-write count next to it proves the paused actor wrote <em>nothing</em> to the SSoT while it waited. "
           "The governor verdict column is the raw map <code>governor/check</code> returned.")
      ["#" "scenario" "phase" "op" "subject" "graph" "governor verdict" "human decision" "disposition"]
      (map scenario-row receipts))

     (section
      "hard" (str "HARD governor refusals &mdash; " (count hard-facts)
                  " (" (count hard-rules) " distinct <code>governor/check</code> rules)")
      (str "A HARD violation cannot be approved away: the <code>:decide</code> node routes straight to <code>:hold</code> and the "
           "<code>:request-approval</code> node is never reached, so no human is ever asked. The right-hand column re-reads the "
           "store at render time to show the fact each rule fired on &mdash; it is the store's answer, not a restatement of the rule.")
      ["#" "scenario" "op" "subject" "rule" "governor detail" "ground truth in the store"]
      (map #(hard-row db (by-fact %) %) hard-facts))

     (section
      "phase-gate" (str "Rollout-gate holds &mdash; " (count phase-facts) " (NOT governor refusals)")
      (str "This is the look-alike the classifier has to keep apart. <code>sportsclub.phase/gate</code> holds an op that the "
           "current rollout phase has not enabled yet, and the <code>:decide</code> node writes that hold through "
           "<code>governor/hold-fact</code> &mdash; so the fact type is <code>:governor-hold</code> here too. What separates them is "
           "<code>:phase-reason</code> (only the phase gate sets one) and the empty <code>:violations</code>: the governor was clean, "
           "the calendar was not. Counting these as refusals would overstate what the governor actually caught.")
      ["#" "scenario" "phase" "op" ":phase-reason" ":violations" "classified as"]
      (map #(phase-hold-row (by-fact %) %) phase-facts))

     (section
      "rejection" (str "Approver rejections &mdash; " (count rej-facts) " (also NOT governor refusals)")
      (str "The second look-alike, and the sharper one. When a human rejects, <code>operation/build</code>'s "
           "<code>:request-approval</code> node <em>synthesises</em> a violation &mdash; <code>{:rule :approver-rejected}</code> &mdash; and merges it over "
           "<code>governor/hold-fact</code>. So this rejection fact <strong>carries a violation</strong> while the governor raised none. "
           "Any classifier keyed on <code>:violations</code> being non-empty would count this as a governor refusal. This one keys on "
           "<code>:t</code> first, which is <code>:approval-rejected</code> here.")
      ["#" "scenario" "op" "rejected by" "fact type" "carried :violations" "classified as"]
      (map #(rejection-row (by-fact %) %) rej-facts))

     (section
      "escalations" (str "Escalations to a human &mdash; " (count escalated) " runs actually paused")
      (str "Produced by <code>interrupt-before</code>. <code>:phase-approval</code> means the governor cleared the proposal but the "
           "rollout phase does not let that op auto-commit; <code>:actuation</code> means the op is in "
           "<code>governor/high-stakes</code> and always needs a human, at every phase, forever.")
      ["#" "scenario" "phase" "op" "escalation reason" "confidence" "ledger writes while paused" "human decision"]
      (map escalation-row escalated))

     (section
      "contract" "Op contract &mdash; derived from the code at render time"
      (str "Built from <code>phase/read-ops</code>, <code>phase/write-ops</code>, <code>phase/phases</code> and "
           "<code>governor/high-stakes</code> as this page was generated, so it cannot drift away from the actor. "
           "Note that <code>:actuation/finalize-membership-action</code> is a write op at phase 3 yet appears in <em>no</em> phase's "
           "<code>:auto</code> set &mdash; two independent layers (the phase table and the governor's high-stakes set) agree that "
           "finalizing a real member's suspension or expulsion is always a human call.")
      ["op" "kind" "writable in phases" "commit gate" "governor high-stakes?"]
      (map contract-row (op-contract)))

     (section
      "coverage" (str "Jurisdiction spec-basis coverage &mdash; "
                      (:covered cov) " of " (:requested cov) " seeded jurisdictions")
      (str "<code>facts/coverage</code> called on the jurisdictions the seeded members actually carry. "
           (esc (:note cov))
           " The missing entry below is exactly what the <code>:no-spec-basis</code> refusal above fired on.")
      ["ISO3" "status" "jurisdiction" "owner authority" "required evidence items" "official provenance"]
      (concat (map #(coverage-row % true) (:covered-jurisdictions cov))
              (map #(coverage-row % false) (:missing-jurisdictions cov))))

     (section
      "attribution" "Approver attribution &mdash; scanned, not assumed"
      (str "The demo submits approvals as <code>" (esc approver-id) "</code> while the executing actor is "
           "<code>" (esc executing-actor-id) "</code>. They are deliberately different strings: a ledger "
           "<code>:committed</code> fact carries <code>:actor</code>, which is the <em>executing</em> actor, and a renderer that read "
           "that as &ldquo;who approved this&rdquo; would print the right answer for the wrong reason if the two ids matched. "
           "Each row below is a render-time walk of the register the effect actually landed in, looking for approver-shaped keys and "
           "for the literal id. If someone changes the store, this table changes with it.")
      ["#" "scenario" "subject" "op" "effect" "register" "submitted approver" "approver-shaped keys found in register" "outcome"]
      (map attribution-row attribution))

     (prose-section
      "attribution-finding" "What that scan found in this build"
      (let [lost (filterv (complement :retained?) attribution)
            kept (filterv :retained? attribution)]
        (str "Of " (count attribution) " human-approved commits, "
             (if (seq kept)
               (str "<strong>" (count kept) "</strong> kept the approver ("
                    (str/join ", " (map code (distinct (map :register kept)))) ")")
               "<strong>none</strong> kept the approver")
             " and "
             (if (seq lost)
               (str "<strong>" (count lost) "</strong> lost it ("
                    (str/join ", " (map code (distinct (map :register lost)))) ")")
               "<strong>none</strong> lost it")
             ". Attribution here is therefore "
             (if (and (seq kept) (seq lost))
               (bad "per-effect and lossy")
               (if (seq lost) (bad "lost") (ok "complete")))
             " &mdash; a real, disclosed gap, not something this rendering task quietly patched inside the store."))
      (str "The audit ledger separately holds <strong>" (:commit-facts led-attr) "</strong> "
           (code :committed) " facts whose " (code :actor) " values are "
           (str/join ", " (map code (:distinct-actors led-attr)))
           ". Approver-shaped keys anywhere in the ledger: "
           (if (seq (:approver-shaped-keys led-attr))
             (esc (pr-str (:approver-shaped-keys led-attr)))
             (bad "none"))
           ". Does the ledger mention <code>" (esc approver-id) "</code> anywhere? "
           (if (:mentions-approver? led-attr) (ok "yes") (bad "no"))
           ". So the append-only audit trail records <em>which actor ran the operation</em> but not "
           "<em>which human authorised it</em> &mdash; and the two are not the same person even in this demo.")
      (str "Where it survives it survives because <code>store/commit-record!</code> persists the record's "
           (code :payload) " for those effects, and <code>operation/build</code>'s <code>:request-approval</code> node only adds "
           (code :approved-by) " to " (code :payload) " &mdash; never to " (code :value)
           ". Effects whose store branch reads " (code :value) ", or which recompute the record from the member entirely, "
           "cannot see it. This paragraph describes the mechanism; the table above is the evidence, and it is re-measured on every build."))

     (section
      "registers" "Durable registers after the run"
      (str "Read back through the <code>store/Store</code> protocol only. The verification and conduct-screen payloads are what the "
           "governor's <code>:evidence-incomplete</code> check reads on the next actuation attempt.")
      ["register" "entries" "contents"]
      [(row (code :verifications) (esc (count (:verifications regs)))
            (if (seq (:verifications regs))
              (str/join "<br>" (for [[k v] (:verifications regs)]
                                 (str (code k) " &rarr; " (code (:jurisdiction v))
                                      " &middot; " (esc (count (:checklist v))) " items"
                                      (when-let [a (:approved-by v)] (str " &middot; " (code :approved-by) "=" (code a))))))
              (dash)))
       (row (code :conduct-screens) (esc (count (:conduct-screens regs)))
            (if (seq (:conduct-screens regs))
              (str/join "<br>" (for [[k v] (:conduct-screens regs)]
                                 (str (code k) " &rarr; " (code :complaint-unresolved?) "="
                                      (if (:complaint-unresolved? v) (bad "true") (ok "false"))
                                      (when-let [a (:approved-by v)] (str " &middot; " (code :approved-by) "=" (code a))))))
              (dash)))
       (row (code :actions) (esc (count actions))
            (if (seq actions)
              (str/join "<br>" (for [a actions]
                                 (str (code (get a "record_id")) " &middot; " (code (get a "member_id"))
                                      " &middot; " (code (get a "jurisdiction"))
                                      " &middot; " (code :immutable) "=" (esc (get a "immutable")))))
              (dash)))])

     (section
      "members-final" "Member register after the run"
      (str "The same " (esc (count seed)) " seeded members, re-read after all " (esc (count receipts))
           " runs. " (esc (count actions)) " membership action(s) were finalized; the "
           (esc (count hard-facts)) " HARD refusals left their subjects untouched, "
           "which is the point &mdash; a HARD hold performs no SSoT mutation at all.")
      ["member" "jurisdiction" "action state" "action number" "status" "complaint unresolved?"]
      (map final-member-row (store/all-members db)))

     (section
      "ledger" (str "Append-only audit ledger &mdash; " (count ledger) " facts")
      (str "<code>store/ledger</code> in append order. Written only from the graph's two terminal nodes "
           "(<code>:commit</code> and <code>:hold</code>), one fact per run. Note the <code>:actor</code> column is uniformly "
           "<code>" (esc executing-actor-id) "</code>: it is the executing actor, never the approver.")
      ["#" "classified as" ":t" "op" "subject" ":actor" ":phase-reason" "violation rules" "summary"]
      (map-indexed ledger-row ledger))

     "</main>\n<footer>\n"
     "<p>Generated by <code>clojure -M:render-html</code> from <code>src/sportsclub/render_html.clj</code>. "
     "The generator drives <code>sportsclub.operation/build</code> over <code>sportsclub.store/seed-db</code> and refuses to write "
     "this file if the run yields zero HARD governor refusals or zero commits. No UUID, wall-clock value or date is rendered, so "
     "two builds of the same commit are byte-identical.</p>\n"
     "<p>Licence AGPL-3.0-or-later &middot; styling: "
     "<a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-dds</a> "
     "(&#12487;&#12472;&#12479;&#12523;&#24193;&#12487;&#12470;&#12452;&#12531;&#12471;&#12473;&#12486;&#12512;).</p>\n"
     "</footer>\n</body>\n</html>\n")))

;; ----------------------------- build-time invariant -------------------------

(defn assert-invariants!
  "A build-time invariant, not a convention. A console that shows no
  HARD governor refusal is not evidence that the governor works, so
  refuse to write the file at all rather than publish a happy-path
  page.

  Phase-gate holds and approver rejections deliberately do NOT satisfy
  it. That is the whole reason the classifier keys on the fact type:
  both of those are holds, one of them even carries a `:violations`
  entry, and a naive hold-count would let this invariant pass while the
  governor had never actually refused anything."
  [db]
  (let [ledger  (vec (store/ledger db))
        hard    (filterv hard-governor-hold? ledger)
        phase-h (filterv phase-gate-hold? ledger)
        rej     (filterv approver-rejection? ledger)
        commits (filterv commit-fact? ledger)]
    (when (empty? hard)
      (throw (ex-info
              (str "refusing to write the console: the run produced ZERO HARD governor refusals. "
                   "(" (count ledger) " ledger facts, " (count (filterv hold-fact? ledger))
                   " holds, of which " (count phase-h) " were rollout-gate holds and "
                   (count rej) " were approver rejections -- neither is a governor refusal.) "
                   "A page with no real refusal proves nothing about the governor.")
              {:ledger-facts (count ledger) :holds (count (filterv hold-fact? ledger))
               :phase-gate-holds (count phase-h) :approver-rejections (count rej)
               :hard-governor-holds 0})))
    (when (empty? commits)
      (throw (ex-info "refusing to write the console: ZERO commits, so the commit path is unproven."
                      {:ledger-facts (count ledger)})))
    {:hard-holds (count hard)
     :hard-rules (vec (sort (map str (distinct (mapcat rules-of hard)))))
     :phase-gate-holds (count phase-h)
     :approver-rejections (count rej)
     :commits (count commits)
     :ledger-facts (count ledger)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as run} (run-demo!)
        summary (assert-invariants! db)
        html (render run)]
    (when-let [dir (.getParentFile (java.io.File. ^String out))]
      (.mkdirs dir))
    (spit out html)
    (println "wrote" out (str "(" (count html) " bytes)"))
    (println "  scenarios          :" (count (:receipts run)))
    (println "  ledger facts       :" (:ledger-facts summary))
    (println "  commits            :" (:commits summary))
    (println "  HARD gov refusals  :" (:hard-holds summary) (pr-str (:hard-rules summary)))
    (println "  phase-gate holds   :" (:phase-gate-holds summary) "(not refusals)")
    (println "  approver rejections:" (:approver-rejections summary) "(not refusals)")))
