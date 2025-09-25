(ns com.p14n.waku.core
  (:require
   [fmnoise.flow :as flow :refer [then]]
   [manifold.deferred :as d])
  (:import
   [java.util UUID]))


#_(extend-protocol flow/Flow
    manifold.deferred.Deferred
    (?ok [this f] (if (d/realized? this)
                    (f (deref this))
                    this))
    (?err [this f] (if (and (d/realized? this)
                            (instance? Throwable (deref this)))
                     (f (deref this))
                     this))
    (?throw [this] (if (and (d/realized? this)
                            (instance? Throwable (deref this)))
                     (throw (deref this))
                     this)))


(extend-protocol flow/Flow
  manifold.deferred.Deferred
  (?ok [this _] this)
  (?err [this f] (f (ex-info "Deferred" {})))
  (?throw [this] (throw (ex-info "Deferred" {}))))

(defprotocol StepStore
  (store-start! [this wfname wfid step payload])
  (store-callback-result!
    [this token payload])
  (store-result!
    [this wfname wfid step payload])
  (store-callback-token! [this wfname wfid step callback-function])
  (get-steps [this wfname wfid])
  (get-result [_ wfname wfid step]))

(def store-atom (atom nil))

(defn set-store! [s]
  (reset! store-atom s))

(def ^:dynamic *current-workflow* nil)
(def ^:dynamic create-callback-token! nil)

(defn run-workflow
  ([workflow-name workflow-steps-function]
   (run-workflow workflow-name (str (UUID/randomUUID)) workflow-steps-function))
  ([workflow-name workflow-id workflow-steps-function]
   (binding [*current-workflow* [workflow-name workflow-id (atom 0)]]
     (let [r (workflow-steps-function)
           s (deref (nth *current-workflow* 2))]
       (merge
        {:workflow-name workflow-name
         :result r}
        (when (> s 0)
          {:workflow-id workflow-id
           :latest-step s}))))))

(defn make-store-callback-token-fn [store workflow-name workflow-id workflow-step]
  (fn cct
    ([] (cct nil))
    ([callback-function]
     (store-callback-token! store workflow-name workflow-id workflow-step callback-function))))

(defn then!
  ([f] (partial then! f))
  ([f value]
   (if (flow/fail? value)
     value
     (let [[workflow-name workflow-id last-step] *current-workflow*
           workflow-step (swap! last-step inc)
           store @store-atom]
       (when (nil? store)
         (throw (ex-info "No store set.  Have you called set-store?" {})))
       (when (or (nil? workflow-name)
                 (nil? workflow-id))
         (throw (ex-info "No workflow name or id.  Have you called run-workflow?" {})))
       (if (d/deferred? value)
         (flow/?ok value f)
         (let [previous (get-result store workflow-name workflow-id workflow-step)]
           (if previous
             previous
             (do
               (store-start! store workflow-name workflow-id workflow-step value)
               (binding [create-callback-token! (make-store-callback-token-fn store workflow-name workflow-id workflow-step)]
                 (let [v (flow/?ok value f)]
                   (if (d/deferred? v)
                     (d/on-realized v (partial store-result! store workflow-name workflow-id workflow-step)
                                    println)
                     (store-result! store workflow-name workflow-id workflow-step v))
                   v))))))))))

(defn then!*
  ([f] (partial then!* f))
  ([f value]
   (then!
    (fn [v]
      (let [df (d/deferred)]
        (future (d/success! df (f v)))
        df))
    value)))

(defn then!*>
  ([f] (partial then!*> f))
  ([f value]
   (then!
    (fn [v]
      (let [df (d/deferred)
            [workflow-name workflow-id step] *current-workflow*]
        (future (binding [*current-workflow* [workflow-name workflow-id step]
                          create-callback-token! (make-store-callback-token-fn @store-atom workflow-name workflow-id step)]
                  (d/success! df (f v))))
        df))
    value)))
