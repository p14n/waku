(ns com.p14n.waku.postevent-test
  (:require
   [com.p14n.waku.core :as waku]))

(defrecord StepStorePostevent []
  waku/StepStore
  (store-workflow-start! [this wfname wfid payload])
  (store-step-start! [this wfname wfid step payload])
  (get-callback-details [this token])
  (store-step-result! [this wfname wfid step payload])
  (store-callback-token! [this wfname wfid step callback-function])
  (get-result [_ wfname wfid step]))


;if result of a sync operation, publish to table only
;if result of async operation, publish to broker.

;(waku/set-store! (->StepStoreAtom a))

;wf; name, id
;wfstep: id, step, begin idn, end idn
;wfcallback: token, function, id, step 

;idn bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
;id VARCHAR (255) NOT NULL,
;source VARCHAR (1024) NOT NULL, workflow name + step
;type VARCHAR (255) NOT NULL, begin/end
;datacontenttype VARCHAR (255), nippy/json
;dataschema VARCHAR (255),
;subject VARCHAR (255), workflow id
;data bytea, frozen data
;time TIMESTAMP WITH TIME ZONE default current_timestamp,
;traceparent VARCHAR (55),
;UNIQUE (id, source)
