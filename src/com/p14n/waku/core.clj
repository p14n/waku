(ns com.p14n.waku.core
  "Workflow orchestration library with persistent step execution.

  Provides workflow execution with automatic step persistence and resume capability.
  Supports both synchronous and asynchronous operations using Manifold deferreds."
  (:require
   [fmnoise.flow :as flow :refer [then]]
   [manifold.deferred :as d]
   [clojure.tools.logging :as log])
  (:import
   [java.util UUID]))

;; Note: Deferred values are passed through without transformation in the flow protocol.
;; This allows the workflow system to handle them specially in then! and related functions.
(extend-protocol flow/Flow
  manifold.deferred.Deferred
  (?ok [this _] this)
  (?err [this f] (f (ex-info "Deferred" {})))
  (?throw [this] (throw (ex-info "Deferred" {}))))

(defprotocol StepStore
  "Protocol for persisting workflow step execution state.

  Implementations should provide durable storage for workflow steps and results,
  enabling workflows to resume from the last completed step after interruption."

  (store-workflow-start! [this wfname wfid payload]
    "Store the initial payload for a workflow execution.")

  (store-step-start! [this wfname wfid step payload]
    "Store the input payload for a workflow step before execution.")

  (get-callback-details [this token]
    "Retrieve workflow details associated with a callback token.
    Returns [wfname wfid step callback-function initial-payload].")

  (store-step-result! [this wfname wfid step payload]
    "Store the result of a completed workflow step.")

  (store-callback-token! [this wfname wfid step callback-function]
    "Create and store a callback token for async operations.
    Returns the generated token string.")

  (get-result [_ wfname wfid step]
    "Retrieve the stored result for a specific workflow step.
    Returns nil if no result exists."))

(def store-atom
  "Global atom holding the current StepStore implementation."
  (atom nil))

(def callback-atom
  "Global atom holding registered callback workflow functions."
  (atom {}))

(defn set-store!
  "Set the global StepStore implementation.

  Must be called before running any workflows.

  Example:
    (set-store! (->MyStoreImpl))"
  [s]
  (reset! store-atom s))

(defn register-callback-workflow!
  "Register a workflow function for callback-based execution.

  Required for workflows that use callback tokens (then!*> with create-callback-token!).

  Parameters:
    wf-name - Unique name for the workflow
    workflow-function - Function that takes initial payload and returns workflow steps

  Example:
    (register-callback-workflow! \"payment-flow\"
      (fn [payload] (->> payload (then! process-payment))))"
  [wf-name workflow-function]
  (swap! callback-atom assoc wf-name workflow-function))

(defn lookup-callback-workflow
  "Look up a registered callback workflow function by name.

  Throws an exception if the workflow is not registered."
  [wfname]
  (or (get @callback-atom wfname)
      (throw (ex-info (str "No workflow function registered for callback - you must call register-callback-workflow! for " wfname)
                      {:workflow-name wfname}))))

(def ^:dynamic *current-workflow*
  "Dynamic var holding current workflow context [workflow-name workflow-id step-counter].

  Bound during workflow execution. Do not set directly."
  nil)

(def ^:dynamic create-callback-token!
  "Dynamic var holding callback token creation function.

  Bound during step execution in then!*> contexts. Used to create tokens for
  async callbacks that need to resume workflow execution.

  Usage:
    (create-callback-token!)           ; Create token with no transformation
    (create-callback-token! transform) ; Create token with result transformation"
  nil)

(defn run-workflow
  "Execute a workflow with persistent step execution.

  Workflows can be resumed by providing the same workflow-id. Steps that have
  already completed will return their cached results instead of re-executing.

  Parameters:
    workflow-name - Unique name identifying the workflow type
    workflow-id - (optional) Unique ID for this workflow execution. Auto-generated if not provided.
    workflow-steps-function - Zero-argument function that defines the workflow steps

  Returns:
    Map with keys:
      :workflow-name - The workflow name
      :result - The final result (may be a deferred)
      :workflow-id - The workflow ID (only if steps were executed)
      :latest-step - The last step number executed (only if steps were executed)

  Example:
    (run-workflow \"data-pipeline\"
      #(->> data
            (then! validate)
            (then! transform)
            (then! save)))"
  ([workflow-name workflow-steps-function]
   (run-workflow workflow-name (str (UUID/randomUUID)) workflow-steps-function))
  ([workflow-name workflow-id workflow-steps-function]
   (binding [*current-workflow* [workflow-name workflow-id (atom 0)]]
     (let [r (workflow-steps-function)
           s (deref (nth *current-workflow* 2))]
       ;; If result is a deferred, schedule workflow re-execution when it completes
       ;; This allows subsequent steps to execute after async operations finish
       (when (d/deferred? r)
         (d/on-realized r
                        (fn [_]
                          (log/debug "Deferred workflow step completed, resuming workflow"
                                     {:workflow-name workflow-name :workflow-id workflow-id})
                          (run-workflow workflow-name workflow-id workflow-steps-function))
                        (fn [error]
                          (log/error error "Error in deferred workflow step"
                                     {:workflow-name workflow-name :workflow-id workflow-id}))))
       (merge
        {:workflow-name workflow-name
         :result r}
        (when (> s 0)
          {:workflow-id workflow-id
           :latest-step s}))))))

(defn run-callback-workflow
  "Execute a callback-enabled workflow with initial payload.

  Similar to run-workflow but designed for workflows that will be resumed via
  callbacks. The workflow function must be registered via register-callback-workflow!

  Parameters:
    workflow-name - Name of the registered workflow
    workflow-id - (optional) Unique ID for this execution
    initial-payload - Initial data passed to the workflow function

  Returns:
    Same as run-workflow

  Example:
    (register-callback-workflow! \"payment\" payment-workflow-fn)
    (run-callback-workflow \"payment\" {:amount 100 :user-id 123})"
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

(defn run-workflow-from-callback
  "Resume a workflow from a callback token.

  Used to continue workflow execution after an external async operation completes.
  The token identifies which workflow and step to resume from.

  Parameters:
    token - Callback token created via create-callback-token!
    payload - Result data from the callback operation

  Returns:
    Same as run-workflow

  Example:
    ;; In workflow:
    (then!*> (fn [_]
               (let [token (create-callback-token! parse-response)]
                 (send-async-request token))))

    ;; Later, when callback arrives:
    (run-workflow-from-callback token response-data)"
  [token payload]
  (let [store @store-atom
        [wfname wfid step callback-function initial-payload] (get-callback-details store token)
        result (if callback-function
                 (callback-function payload)
                 payload)
        workflow-function (lookup-callback-workflow wfname)]
    (store-step-result! store wfname wfid step result)
    (run-workflow wfname wfid #(workflow-function initial-payload))))

(defn make-store-callback-token-fn
  "Create a callback token creation function bound to a specific workflow step.

  Internal function used by execute-step to bind create-callback-token! dynamic var."
  [store workflow-name workflow-id workflow-step]
  (fn cct
    ([] (cct nil))
    ([callback-function]
     (store-callback-token! store workflow-name workflow-id workflow-step callback-function))))

(defn- execute-step
  "Execute a workflow step and store its result.

  Internal function that handles step execution, result storage, and deferred handling."
  [store workflow-name workflow-id workflow-step f value]
  (store-step-start! store workflow-name workflow-id workflow-step value)
  (binding [create-callback-token! (make-store-callback-token-fn store workflow-name workflow-id workflow-step)]
    (let [v (flow/?ok value f)]
      (if (d/deferred? v)
        (d/on-realized v
                       (partial store-step-result! store workflow-name workflow-id workflow-step)
                       (fn [error]
                         (log/error error "Error in deferred step execution"
                                    {:workflow-name workflow-name
                                     :workflow-id workflow-id
                                     :step workflow-step})))
        (store-step-result! store workflow-name workflow-id workflow-step v))
      v)))

(defn then!
  "Execute a function on a value with step persistence and caching.

  This is the core workflow step function. It:
  1. Checks if this step has already been executed (cache hit)
  2. If cached, returns the previous result without re-executing
  3. If not cached, executes the function and stores the result
  4. Handles deferred values by passing them through
  5. Skips execution if the value is a failure

  Must be called within a run-workflow context.

  Parameters:
    f - Function to apply to the value
    value - Input value (can be regular value, deferred, or failure)

  Returns:
    Result of applying f to value, or cached result if available

  Example:
    (run-workflow \"process-data\"
      #(->> data
            (then! validate)    ; Step 1: cached on replay
            (then! transform)   ; Step 2: cached on replay
            (then! save)))      ; Step 3: only this executes on replay if 1-2 completed"
  ([f] (partial then! f))
  ([f value]
   (if (flow/fail? value)
     value
     (let [[workflow-name workflow-id last-step] *current-workflow*
           workflow-step (swap! last-step inc)
           store @store-atom]
       (when (nil? store)
         (throw (ex-info "No store set. Have you called set-store?" {})))
       (when (or (nil? workflow-name)
                 (nil? workflow-id))
         (throw (ex-info "No workflow name or id. Have you called run-workflow?" {})))
       (if (d/deferred? value)
         ;; Deferred values are passed through - they'll be handled by run-workflow
         (flow/?ok value f)
         (let [previous (get-result store workflow-name workflow-id workflow-step)]
           (if (and previous (not (flow/fail? previous)))
             (do
               (log/debug "Using cached result for step"
                          {:workflow-name workflow-name
                           :workflow-id workflow-id
                           :step workflow-step})
               previous)
             (execute-step store workflow-name workflow-id workflow-step f value))))))))

(defn then!*
  "Execute a function asynchronously with step persistence.

  Like then! but wraps the function execution in a future, returning a deferred.
  Useful for CPU-intensive operations that should run in a separate thread.

  Parameters:
    f - Function to execute asynchronously
    value - Input value

  Returns:
    Manifold deferred that will contain the result

  Example:
    (->> data
         (then!* expensive-computation)  ; Runs in background thread
         (then! save-result))"
  ([f] (partial then!* f))
  ([f value]
   (then!
    (fn [v]
      (let [df (d/deferred)]
        (future (d/success! df (f v)))
        df))
    value)))

(defn then!*>
  "Execute a function asynchronously with callback token support.

  Like then!* but also binds create-callback-token! so the function can
  create callback tokens for external async operations.

  Use this when your step needs to:
  1. Initiate an external async operation (API call, webhook, etc.)
  2. Create a callback token to resume the workflow later

  Parameters:
    f - Function to execute (has access to create-callback-token!)
    value - Input value

  Returns:
    Manifold deferred

  Example:
    (then!*> (fn [order]
               (let [token (create-callback-token! parse-payment-response)]
                 (initiate-payment order token))))"
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

(defn then-until!
  "Repeatedly execute a function with step persistence until a condition is met.

  Each iteration is a separate workflow step, so progress is preserved.
  Be careful with infinite loops - ensure the check function will eventually return true.

  Parameters:
    f - Function to apply on each iteration
    check - Predicate function that returns true when done
    value - Initial value

  Returns:
    Final value when check returns true

  Example:
    (->> 0
         (then-until! inc #(= % 10)))  ; Counts from 0 to 10, each step cached"
  [f check value]
  (loop [v (then! f value)]
    (if (check v)
      v
      (recur (then! f v)))))
