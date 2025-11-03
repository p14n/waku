(ns com.p14n.waku.core-test
  (:require
   [com.p14n.waku.core :as waku :refer [then! then!* then!*>]]
   [fmnoise.flow :refer [else then]]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as d])
  (:import
   [java.util UUID]))

(defrecord StepStoreAtom [store]
  waku/StepStore
  (store-workflow-start! [this wfname wfid payload]
    (swap! store assoc-in ["initial-payloads" wfname wfid] payload))
  (store-step-start! [_ wfname wfid step payload]
    (swap! store assoc-in ["workflows" wfname wfid step :start] payload))
  (store-step-result! [_ wfname wfid step payload]
    (swap! store assoc-in ["workflows" wfname wfid step :result] payload))
  (store-callback-token! [_ wfname wfid step callback-function]
    (let [token (str (UUID/randomUUID))]
      (swap! store assoc-in ["tokens" token] [wfname wfid step callback-function])
      token))
  (get-callback-details [this token]
    (let [[wfname wfid step callback-function] (get-in @store ["tokens" token])
          initial-payload (get-in @store ["initial-payloads" wfname wfid])]
      [wfname wfid step callback-function initial-payload]))
  (get-result [_ wfname wfid step]
    (get-in @store ["workflows" wfname wfid step :result])))

(def a (atom {}))

(waku/set-store! (->StepStoreAtom a))

(defn reset-store! []
  (reset! a {}))

(defn delayed [x]
  (Thread/sleep 50)
  x)

(deftest simple-value-update
  (testing "Value updates and correctly updates the store"
    (reset-store!)
    (let [{:keys [result
                  latest-step
                  workflow-id
                  workflow-name]}
          (waku/run-workflow "test"
                             #(->> 1
                                   (then inc)
                                   (then! inc)))]
      (is (= 3 result))
      (is (= 1 latest-step))
      (is (= "test" workflow-name))
      (is (string? workflow-id))))

  (testing "Failure skips store"
    (reset-store!)
    (let [{:keys [result
                  latest-step
                  workflow-id
                  workflow-name]}
          (waku/run-workflow "test"
                             #(->> (ex-info "Nope" {})
                                   (then inc)
                                   (then! inc)
                                   (else (constantly "Er"))))]
      (is (= "Er" result))
      (is (= "test" workflow-name))
      (is (nil? latest-step))
      (is (nil? workflow-id))
      (is (= {} @a)))))

(deftest async-ops
  (testing "Async operation delivers correct result on second run"
    (reset-store!)
    (let [wf #(->> 1
                   (then!* delayed)
                   (then inc))
          first-run (waku/run-workflow "test" wf)
          _ (Thread/sleep 100)
          second-run (waku/run-workflow "test" (:workflow-id first-run) wf)]
      (is (d/deferrable? (:result first-run)))
      (is (= 1 (:latest-step first-run)))
      (is (= 2 (:result second-run)))
      (is (= "test" (:workflow-name second-run)))))

  (testing "Async operation delivers correct result in background"
    (reset-store!)
    (let [wf #(->> 1
                   (then!* delayed)
                   (then! inc))
          first-run (waku/run-workflow "test" wf)
          _ (Thread/sleep 100)
          _ (println "MONKEY" "test" (:workflow-id first-run) 2)
          second-step-result (waku/get-result @waku/store-atom "test" (:workflow-id first-run) 2)]
      (is (d/deferrable? (:result first-run)))
      (is (= 1 (:latest-step first-run)))
      (is (= 2 second-step-result))))

  (testing "Async callback operation assigns new token and calls value"
    (reset-store!)
    (let [token (atom nil)
          initial-values (atom [])
          wf #(->> %
                   (then (fn [v] (swap! initial-values conj %)))
                   (then!*> (fn [_] (reset! token (waku/create-callback-token!))))
                   (then inc))
          _ (waku/register-callback-workflow! "test" wf)
          first-run (waku/run-callback-workflow "test" 0)
          _ (Thread/sleep 100)
          callback-run (waku/run-workflow-from-callback @token 45)]
      (is (d/deferrable? (:result first-run)))
      (is (not (nil? @token)))
      (is (= 46 (:result callback-run)))
      (is (= [0 0] @initial-values))))

  (testing "Async callback operation operates result function"
    (reset-store!)
    (let [token (atom nil)
          result-function (fn [v] (->> v (inc) (str v " was input, output: ")))
          wf #(->> %
                   (then!*> (fn [_] (reset! token (waku/create-callback-token! result-function)))))
          _ (waku/register-callback-workflow! "test" wf)
          first-run (waku/run-callback-workflow "test" 0)
          _ (Thread/sleep 100)
          callback-run (waku/run-workflow-from-callback @token 45)]
      (is (d/deferrable? (:result first-run)))
      (is (not (nil? @token)))
      (is (= "45 was input, output: 46" (:result callback-run))))))