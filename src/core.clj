(ns core
  (:require [fmnoise.flow :as flow :refer [then else]]
            [manifold.deferred :as d]))

(extend-protocol flow/Flow
  manifold.deferred.Deferred
  (?ok [this _] this)
  (?err [this f] (f (ex-info "Deferred" {})))
  (?throw [this] (throw (ex-info "Deferred" {}))))

(defprotocol StepStore
  (store-start! [this wfname wfid step payload])
  (store-result! [this wfname wfid step payload])
  (get-steps [this wfname wfid])
  (get-result [_ wfname wfid step]))

(defrecord StepStoreAtom [store]
  StepStore
  (store-start! [_ wfname wfid step payload]
    (swap! store assoc-in [wfname wfid step :start] payload))
  (store-result! [_ wfname wfid step payload]
    (swap! store assoc-in [wfname wfid step :result] payload))
  (get-steps [_ wfname wfid]
    (get-in @store [wfname wfid]))
  (get-result [_ wfname wfid step]
    (get-in @store [wfname wfid step :result])))

(def a (atom {}))
(def store (->StepStoreAtom a))

(def workflow-name "workflow")
(def workflow-id "wfid")
(def workflow-step "step")

(defn then!
  ([f] (partial then f))
  ([f value]
   (if (d/deferred? value)
     (flow/?ok value f)
     (let [previous (get-result store workflow-name workflow-id workflow-step)]
       (if previous
         previous
         (do
           (store-start! store workflow-name workflow-id workflow-step value)
           (let [v (flow/?ok value f)]
             (if (d/deferred? v)
               (d/on-realized v (partial store-result! store workflow-name workflow-id workflow-step)
                              println)
               (store-result! store workflow-name workflow-id workflow-step v))
             v)))))))

(defn then!*
  ([f] (partial then!* f))
  ([f value]
   (then!
    (fn [v]
      (let [df (d/deferred)]
        (future (d/success! df (f v)))
        df))
    value)))

(defn x [x]
  (Thread/sleep 1000)
  (println x)
  x)

;(d/deferred 1)
;(d/success-deferred 1)
;(ex-info "Nope" {})

(->> 1;(ex-info "Nope" {})
     (then inc)
     (then!* x)
     (then inc)
     (else (constantly "Er")))

@a
(reset! a {})
