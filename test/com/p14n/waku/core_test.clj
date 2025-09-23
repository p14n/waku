(ns com.p14n.waku.core-test
  (:require
   [com.p14n.waku.core :as waku :refer [then! then!*]]
   [fmnoise.flow :refer [else then]]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as d])
  (:import
   [java.util UUID]))

(defrecord StepStoreAtom [store]
  waku/StepStore
  (store-start! [_ wfname wfid step payload]
    (swap! store assoc-in ["workflows" wfname wfid step :start] payload))
  (store-token-result! [_ token payload]
    (let [[wfname wfid step] (get @store ["tokens" token])]
      (swap! store assoc-in ["workflows" wfname wfid step :result] payload)))
  (store-result! [_ wfname wfid step payload]
    (swap! store assoc-in ["workflows" wfname wfid step :result] payload))
  (store-result-token! [_ wfname wfid step]
    (let [token (str (UUID/randomUUID))]
      (swap! store assoc-in ["tokens" token] [wfname wfid step])
      token))
  (get-steps [_ wfname wfid]
    (get-in @store ["workflows" wfname wfid]))
  (get-result [_ wfname wfid step]
    (get-in @store ["workflows" wfname wfid step :result])))

(def a (atom {}))

(waku/set-store! (->StepStoreAtom a))

(defn reset-store! []
  (reset! a {}))

(defn delayed [x]
  (Thread/sleep 50)
  x)

;(d/deferred 1)
;(d/success-deferred 1)


#_(->> 1 ;(ex-info "Nope" {})
       (then inc)
       (then!* x)
       (then inc)
       (then (partial println "Done>>>>"))
       (else (constantly "Er")))


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
      (is (= {} @a))))

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
      (is (= 2 (:result second-run))))))
