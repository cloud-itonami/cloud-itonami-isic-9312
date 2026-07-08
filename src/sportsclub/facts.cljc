(ns sportsclub.facts
  "Per-jurisdiction sports-club member-safeguarding and membership-
  governance regulatory catalog -- the G2-style spec-basis table the
  Membership Governor checks every eligibility/verify proposal against
  ('did the advisor cite an OFFICIAL public source for this
  jurisdiction's member-safeguarding and membership-governance
  requirements, or did it invent one?').

  This blueprint's own Trust Controls ('member personal data stays
  outside Git') and its own actuation ('finalizing a membership
  suspension or expulsion') point at two genuinely real, distinct
  regulatory concerns for THIS vertical: (1) member/volunteer
  safeguarding clearance for club officials in contact with minor
  members -- a well-documented global concern (US Center for
  SafeSport, UK DBS checks, Japan's JSPO safeguarding guideline,
  Germany's erweitertes Führungszeugnis) -- and (2) nonprofit
  membership-organization due-process requirements before a member
  suspension/expulsion is finalized (a real, jurisdiction-specific
  legal requirement, not a club-invented courtesy).

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official safeguarding/
  nonprofit-governance regulator (see `:provenance`); they are a
  STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  membership-conduct-record/safeguarding-clearance-record/
  disciplinary-hearing-record/appeal-notice-record evidence set
  submitted in some form; `:legal-basis` / `:owner-authority` /
  `:provenance` are the G2 citation the governor requires before any
  :eligibility/verify proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "公益財団法人日本スポーツ協会 (Japan Sport Association, JSPO)"
          :legal-basis "一般社団法人及び一般財団法人に関する法律 第25条 (社員の除名 -- member-expulsion due process); JSPO「安全・安心なスポーツ環境の確保のためのガイドライン」"
          :national-spec "会員の除名/資格停止に関する適正手続き基準"
          :provenance "https://www.japan-sports.or.jp/"
          :required-evidence ["会員行動記録 (membership-conduct record)"
                              "セーフガーディング資格確認記録 (safeguarding-clearance record)"
                              "懲戒審理記録 (disciplinary-hearing record)"
                              "異議申立通知記録 (appeal-notice record)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Center for SafeSport / State Nonprofit Corporation Law"
          :legal-basis "Protecting Young Victims from Sexual Abuse and Safe Sport Authorization Act of 2017; California Corporations Code §7341 (nonprofit mutual benefit corporation member expulsion due process)"
          :national-spec "SafeSport Code minimum standards; state nonprofit member-expulsion notice-and-hearing requirements"
          :provenance "https://uscenterforsafesport.org/"
          :required-evidence ["Membership-conduct record"
                              "Safeguarding-clearance record"
                              "Disciplinary-hearing record"
                              "Appeal-notice record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Disclosure and Barring Service (DBS) / Sport England Safeguarding"
          :legal-basis "Safeguarding Vulnerable Groups Act 2006; natural-justice principles for member expulsion (Charity Commission guidance)"
          :national-spec "DBS enhanced-check requirements for roles working with children/vulnerable adults; notice-and-hearing requirements before member expulsion"
          :provenance "https://www.gov.uk/government/organisations/disclosure-and-barring-service"
          :required-evidence ["Membership-conduct record"
                              "Safeguarding-clearance record"
                              "Disciplinary-hearing record"
                              "Appeal-notice record"]}
   "DEU" {:name "Germany"
          :owner-authority "Deutscher Olympischer Sportbund (DOSB) / Landesjugendämter"
          :legal-basis "BGB §35 (Vereinsrecht -- Anhörungsrecht vor Ausschluss); erweitertes Führungszeugnis Pflicht für Tätigkeiten mit Minderjährigen (Bundeskinderschutzgesetz)"
          :national-spec "DOSB \"Sicherheit im Sport\" Präventionsstandards; Vereinssatzung Ausschlussverfahren"
          :provenance "https://www.dosb.de/"
          :required-evidence ["Mitgliederverhaltensprotokoll (membership-conduct record)"
                              "Führungszeugnis-Nachweis (safeguarding-clearance record)"
                              "Disziplinaranhörungsprotokoll (disciplinary-hearing record)"
                              "Widerspruchsmitteilung (appeal-notice record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to finalize a
  membership action on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-9312 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `sportsclub.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
