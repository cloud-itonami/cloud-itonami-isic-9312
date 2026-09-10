(ns sportsclub.registry-test
  (:require [clojure.test :refer [deftest is]]
            [sportsclub.registry :as r]))

;; ----------------------------- appeal-window-still-open? -----------------------------

(deftest not-open-when-window-elapsed
  (is (not (r/appeal-window-still-open?
            {:days-since-suspension-notice 30 :minimum-appeal-window-days 14})))
  (is (not (r/appeal-window-still-open?
            {:days-since-suspension-notice 14 :minimum-appeal-window-days 14}))))

(deftest open-when-window-not-yet-elapsed
  (is (r/appeal-window-still-open?
       {:days-since-suspension-notice 5 :minimum-appeal-window-days 14})))

(deftest missing-fields-are-not-treated-as-open
  (is (not (r/appeal-window-still-open? {})))
  (is (not (r/appeal-window-still-open? {:days-since-suspension-notice 5}))))

;; ----------------------------- register-membership-action -----------------------------

(deftest action-is-a-draft-not-a-real-action
  (let [result (r/register-membership-action "member-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest action-assigns-action-number
  (let [result (r/register-membership-action "member-1" "JPN" 7)]
    (is (= (get result "action_number") "JPN-MSA-000007"))
    (is (= (get-in result ["record" "member_id"]) "member-1"))
    (is (= (get-in result ["record" "kind"]) "membership-action-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest action-validation-rules
  (is (thrown? Exception (r/register-membership-action "" "JPN" 0)))
  (is (thrown? Exception (r/register-membership-action "member-1" "" 0)))
  (is (thrown? Exception (r/register-membership-action "member-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-membership-action "member-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-membership-action "member-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-MSA-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-MSA-000001" (get-in hist2 [1 "record_id"])))))
