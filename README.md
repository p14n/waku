# Waku

A Clojure workflow orchestration library that provides persistent step execution with support for asynchronous operations.

## Overview

Waku enables you to build resilient workflows by automatically persisting the results of each step. If a workflow is interrupted or fails, it can resume from the last successfully completed step rather than starting over. This makes it ideal for long-running processes, data pipelines, and any workflow where you want to avoid repeating expensive operations.

## Features

- **Step Persistence**: Automatically stores the result of each workflow step
- **Resume Capability**: Workflows can resume from the last completed step
- **Async Support**: Built-in support for asynchronous operations using Manifold deferreds
- **Error Handling**: Integrates with fmnoise/flow for elegant error handling
- **Pluggable Storage**: Implement your own storage backend via the `StepStore` protocol

## Dependencies

Waku is built on top of:
- [Manifold](https://github.com/clj-commons/manifold) - For asynchronous programming
- [fmnoise/flow](https://github.com/fmnoise/flow) - For functional error handling

## Installation

Add to your `deps.edn`:

```clojure
{:deps {com.p14n/waku {:git/url "https://github.com/p14n/waku"
                       :sha "latest-sha"}}}
```

## Quick Start

### 1. Set up a Store

First, implement the `StepStore` protocol or use the provided atom-based implementation:

```clojure
(require '[com.p14n.waku.core :as waku])

(defrecord AtomStore [store]
  waku/StepStore
  (store-start! [_ wfname wfid step payload]
    (swap! store assoc-in ["workflows" wfname wfid step :start] payload))
  (store-result! [_ wfname wfid step payload]
    (swap! store assoc-in ["workflows" wfname wfid step :result] payload))
  (get-result [_ wfname wfid step]
    (get-in @store ["workflows" wfname wfid step :result]))
  ;; ... implement other methods
  )

(waku/set-store! (->AtomStore (atom {})))
```

### 2. Create a Workflow

Use `run-workflow` to define and execute workflows with persistent steps:

```clojure
(require '[com.p14n.waku.core :refer [run-workflow then!]]
         '[fmnoise.flow :refer [then else]])

(def result
  (run-workflow "data-processing"
    #(->> {:data [1 2 3 4 5]}
          (then! #(update % :data (partial map inc)))      ; Step 1: increment
          (then! #(update % :data (partial filter even?))) ; Step 2: filter evens
          (then! #(assoc % :sum (reduce + (:data %))))     ; Step 3: sum
          (else (constantly {:error "Processing failed"})))))

;; Returns:
;; {:workflow-name "data-processing"
;;  :workflow-id "uuid-string"
;;  :latest-step 3
;;  :result {:data [2 4 6], :sum 12}}
```

### 3. Resume Workflows

If the workflow is interrupted, running it again will resume from the last completed step:

```clojure
;; If step 2 completed but step 3 failed, re-running will skip steps 1-2
;; and start from step 3 with the stored result from step 2
(def resumed-result
  (run-workflow "data-processing" same-workflow-id
    #(->> {:data [1 2 3 4 5]}
          (then! #(update % :data (partial map inc)))      ; Skipped (cached)
          (then! #(update % :data (partial filter even?))) ; Skipped (cached)
          (then! #(assoc % :sum (reduce + (:data %))))     ; Executed
          )))
```

## API Reference

### Core Functions

#### `run-workflow`
```clojure
(run-workflow workflow-name workflow-steps-function)
(run-workflow workflow-name workflow-id workflow-steps-function)
```
Executes a workflow with the given name and steps. If `workflow-id` is not provided, a UUID is generated.

#### `then!`
```clojure
(then! f value)
```
Executes function `f` on `value` and stores the result. If the result for this step already exists in the store, returns the cached result instead of re-executing.

#### `then!*`
```clojure
(then!* f value)
```
Like `then!` but wraps the function execution in a future, returning a Manifold deferred.

#### `set-store!`
```clojure
(set-store! store-implementation)
```
Sets the global store implementation that will be used for persisting workflow steps.

### StepStore Protocol

Implement this protocol to provide your own storage backend:

```clojure
(defprotocol StepStore
  (store-start! [this wfname wfid step payload])
  (store-result! [this wfname wfid step payload])
  (store-result-token! [this wfname wfid step])
  (store-token-result! [this token payload])
  (get-steps [this wfname wfid])
  (get-result [this wfname wfid step]))
```

## Error Handling

Waku integrates with fmnoise/flow for error handling. Use `else` to handle failures:

```clojure
(run-workflow "error-handling-example"
  #(->> (ex-info "Something went wrong" {})
        (then! inc)                           ; This won't execute
        (else (constantly "Handled error")))) ; This will execute
```

## Async Operations

Waku supports Manifold deferreds for asynchronous operations:

```clojure
(require '[manifold.deferred :as d])

(run-workflow "async-example"
  #(->> (d/success-deferred 42)
        (then! #(* % 2))                      ; Works with deferreds
        (then!* #(Thread/sleep 1000) %)      ; Async execution
        ))
```

## Development

### Running Tests

```bash
clojure -M:test
```

### Linting

The project includes clj-kondo configuration for linting.

## License

[Add your license information here]

## Contributing

[Add contribution guidelines here]
