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
  (store-workflow-start! [this wfname wfid payload])
  (store-step-start! [this wfname wfid step payload])
  (get-callback-details [this token])
  (store-step-result! [this wfname wfid step payload])
  (store-callback-token! [this wfname wfid step callback-function])
  (get-result [_ wfname wfid step]))

(def store-atom (atom nil))
(def callback-atom (atom {}))

(defn set-store! [s]
  (reset! store-atom s))

(defn register-callback-workflow! [wf-name workflow-function]
  (swap! callback-atom assoc wf-name workflow-function))

(defn lookup-callback-workflow [wfname]
  (or (get @callback-atom wfname)
      (throw (ex-info (str "No workflow function registered for callback - you must call register-callback-workflow! for " wfname)
                      {:workflow-name wfname}))))

(def ^:dynamic *current-workflow* nil)
(def ^:dynamic create-callback-token! nil)

(defn run-workflow
  ([workflow-name workflow-steps-function]
   (run-workflow workflow-name (str (UUID/randomUUID)) workflow-steps-function))
  ([workflow-name workflow-id workflow-steps-function]
   (binding [*current-workflow* [workflow-name workflow-id (atom 0)]]
     (let [r (workflow-steps-function)
           s (deref (nth *current-workflow* 2))]
       (when (d/deferred? r)
         (d/on-realized r
                        (fn [_]
                          (println "Deferred realized HHHHHHHHHHHHHH")
                          (run-workflow workflow-name workflow-id workflow-steps-function))
                        println))
       (merge
        {:workflow-name workflow-name
         :result r}
        (when (> s 0)
          {:workflow-id workflow-id
           :latest-step s}))))))

(defn run-callback-workflow
  ([workflow-name initial-payload]
   (let [workflow-id (str (UUID/randomUUID))
         store @store-atom]
     (store-workflow-start! store workflow-name workflow-id initial-payload)
     (run-callback-workflow workflow-name workflow-id initial-payload)))
  ([workflow-name workflow-id initial-payload]
   (binding [*current-workflow* [workflow-name workflow-id (atom 0)]]
     (let [workflow-steps-function (lookup-callback-workflow workflow-name)
           r (workflow-steps-function initial-payload)
           s (deref (nth *current-workflow* 2))]
       (merge
        {:workflow-name workflow-name
         :result r}
        (when (> s 0)
          {:workflow-id workflow-id
           :latest-step s}))))))

(defn run-workflow-from-callback [token payload]
  (let [store @store-atom
        [wfname wfid step callback-function initial-payload] (get-callback-details store token)
        result (if callback-function
                 (callback-function payload)
                 payload)
        workflow-function (lookup-callback-workflow wfname)]
    (store-step-result! store wfname wfid step result)
    (run-workflow wfname wfid #(workflow-function initial-payload))))

(defn make-store-callback-token-fn [store workflow-name workflow-id workflow-step]
  (fn cct
    ([] (cct nil))
    ([callback-function]
     (store-callback-token! store workflow-name workflow-id workflow-step callback-function))))

(defn- execute-step [store workflow-name workflow-id workflow-step f value]
  (store-step-start! store workflow-name workflow-id workflow-step value)
  (binding [create-callback-token! (make-store-callback-token-fn store workflow-name workflow-id workflow-step)]
    (let [v (flow/?ok value f)]
      (if (d/deferred? v)
        (d/on-realized v (partial store-step-result! store workflow-name workflow-id workflow-step)
                       println)
        (store-step-result! store workflow-name workflow-id workflow-step v))
      v)))

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
           (println workflow-name workflow-id workflow-step "PREVIOUS" previous)
           (if previous
             previous
             (execute-step store workflow-name workflow-id workflow-step f value))))))))

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
                          create-callback-token! (make-store-callback-token-fn @store-atom workflow-name workflow-id @step)]
                  (d/success! df (f v))))
        df))
    value)))
