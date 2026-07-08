(ns sportsclub.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [sportsclub.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sato Kenji" (:member-name (store/member s "member-1"))))
      (is (= "JPN" (:jurisdiction (store/member s "member-1"))))
      (is (= 30 (:days-since-suspension-notice (store/member s "member-1"))))
      (is (false? (:disciplinary-complaint-unresolved? (store/member s "member-1"))))
      (is (= 5 (:days-since-suspension-notice (store/member s "member-3"))))
      (is (true? (:disciplinary-complaint-unresolved? (store/member s "member-4"))))
      (is (false? (:membership-action-finalized? (store/member s "member-1"))))
      (is (= ["member-1" "member-2" "member-3" "member-4"]
             (mapv :id (store/all-members s))))
      (is (nil? (store/conduct-screen-of s "member-1")))
      (is (nil? (store/verify-of s "member-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/action-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/member-action-already-finalized? s "member-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :member/upsert
                                 :value {:id "member-1" :member-name "Sato Kenji"}})
        (is (= "Sato Kenji" (:member-name (store/member s "member-1"))))
        (is (= 30 (:days-since-suspension-notice (store/member s "member-1"))) "unrelated field preserved"))
      (testing "verification / conduct-screen payloads commit and read back"
        (store/commit-record! s {:effect :verification/set :path ["member-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/verify-of s "member-1")))
        (store/commit-record! s {:effect :conduct-screen/set :path ["member-1"]
                                 :payload {:member-id "member-1" :complaint-unresolved? false}})
        (is (= {:member-id "member-1" :complaint-unresolved? false} (store/conduct-screen-of s "member-1"))))
      (testing "membership-action finalization drafts a record and advances the sequence"
        (store/commit-record! s {:effect :member/mark-action-finalized :path ["member-1"]})
        (is (= "JPN-MSA-000000" (get (first (store/action-history s)) "record_id")))
        (is (= "membership-action-draft" (get (first (store/action-history s)) "kind")))
        (is (true? (:membership-action-finalized? (store/member s "member-1"))))
        (is (= 1 (count (store/action-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/member-action-already-finalized? s "member-1")))
        (is (false? (store/member-action-already-finalized? s "member-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/member s "nope")))
    (is (= [] (store/all-members s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/action-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-members s {"x" {:id "x" :member-name "n"
                                :days-since-suspension-notice 30 :minimum-appeal-window-days 14
                                :disciplinary-complaint-unresolved? false
                                :membership-action-finalized? false :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:member-name (store/member s "x"))))))
