(ns taoensso.timbre-tests
  (:require
   [clojure.test    :as test  :refer [is]]
   [clojure.string  :as str]
   [taoensso.encore :as enc]
   [taoensso.timbre :as timbre]))

(comment
  (remove-ns      'taoensso.timbre-tests)
  (test/run-tests 'taoensso.timbre-tests))

;; TODO Additional tests would be nice!

;;;; Utils

(defn apn
  "Returns a test appender."
  ([ ] (apn nil))
  ([m]
   (let [p (promise)]
     (conj
       {:enabled? true
        :fn (fn [data] (deliver p data))
        :p p}
       m))))

(comment (apn {:min-level :info :ns-filter "*"}))

(defmacro log
  "Executes an easily-configured log call and returns ?data sent to test appender."
  [ns level m-cnf m-apn args]
  `(let [appender# (apn ~m-apn)]
     (binding [timbre/*config*
               (conj timbre/default-config ~m-cnf
                 {:appenders {:test-appender appender#}})]

       (timbre/log! ~level :p ~args {:?ns-str ~ns})
       (deref (:p appender#) 0 nil))))

(comment (log *ns* :info {:min-level :trace} {} ["x"]))

(def err? enc/error?)

;;;; Tests

(test/deftest levels
  (test/testing "Levels.global/basic"
    (is (map? (log "ns" :trace {:min-level :trace} {} [])) "call >= min")
    (is (nil? (log "ns" :trace {:min-level :info}  {} [])) "call <  min")
    (is (map? (log "ns" :info  {:min-level :info}  {} [])) "call >= min")

    (is (not (:error-level? (log "ns" :info  {:min-level :info}  {} []))))
    (is      (:error-level? (log "ns" :error {:min-level :error} {} []))))

  (test/testing "Levels.global/by-ns"
    (is (nil? (log "ns.2" :info   {:min-level [["ns.1" :info]                         ]} {} [])) "call <  default")
    (is (map? (log "ns.2" :report {:min-level [["ns.1" :info]                         ]} {} [])) "call >= default")
    (is (map? (log "ns.2" :info   {:min-level [["ns.1" :warn] ["ns.2"           :info]]} {} [])) "call >= match")
    (is (map? (log "ns.2" :info   {:min-level [["ns.1" :warn] [#{"ns.3" "ns.2"} :info]]} {} [])) "call >= match")
    (is (map? (log "ns.2" :info   {:min-level [["ns.1" :warn] ["*"              :info]]} {} [])) "call >= *")
    (is (map? (log "ns.1" :info   {:min-level [["ns.1" :info] ["*"              :warn]]} {} [])) "Ordered pattern search"))

  (test/testing "Levels.appender/basic"
    (is (map? (log "ns" :info {:min-level :info}   {:min-level :info}   [])) "call >= both global and appender")
    (is (nil? (log "ns" :info {:min-level :report} {:min-level :info}   [])) "call <  global")
    (is (nil? (log "ns" :info {:min-level :info}   {:min-level :report} [])) "call <  appender"))

  (test/testing "Levels.appender/by-ns"
    (is (map? (log "ns" :info {:min-level [["ns" :info]]}  {:min-level :info}          [])) "call >= both global and appender")
    (is (map? (log "ns" :info {:min-level [["ns" :info]]}  {:min-level [["ns" :info]]} [])) "call >= both global and appender")
    (is (nil? (log "ns" :info {:min-level [["ns" :warn]]}  {:min-level [["ns" :info]]} [])) "call <  global")
    (is (nil? (log "ns" :info {:min-level [["ns" :info]]}  {:min-level [["ns" :warn]]} [])) "call <  appender")))

(test/deftest namespaces
  (test/testing "Namespaces/global"
    (is (map? (log "ns.1.a" :report {:min-level :trace :ns-filter "ns.1.*"}                         {} [])))
    (is (nil? (log "ns.1.b" :report {:min-level :trace :ns-filter "ns.2.*"}                         {} [])))
    (is (nil? (log "ns.1.c" :report {:min-level :trace :ns-filter {:allow "ns.1.*" :deny "ns.1.c"}} {} [])) ":deny match"))

  (test/testing "Namespaces/appender"
    (is (map? (log "ns.1.a" :report {:min-level :trace :ns-filter "ns.1.*"} {:ns-filter "ns.1.*"} [])) "both global and appender allowed")
    (is (nil? (log "ns.1.a" :report {:min-level :trace :ns-filter "ns.2.*"} {:ns-filter "ns.1.*"} [])) "global   denied")
    (is (nil? (log "ns.1.a" :report {:min-level :trace :ns-filter "ns.1.*"} {:ns-filter "ns.2.*"} [])) "appender denied")))

(test/deftest special-args
  (test/testing "Special-args/errors"
    (is (nil?      (:?err  (log "ns" :report {} {} ["foo"                  ]))))
    (is (err?      (:?err  (log "ns" :report {} {} [(Exception. "ex") "foo"]))) "First-arg ex -> :?err")
    (is (err?      (:?err  (log "ns" :report {} {} [(Exception. "ex")      ]))) "First-arg ex -> :?err")
    (is (= ["foo"] (:vargs (log "ns" :report {} {} [(Exception. "ex") "foo"]))) "First-arg ex dropped from vargs")
    (is (= []      (:vargs (log "ns" :report {} {} [(Exception. "ex")      ]))) "First-arg ex dropped from vargs"))

  (test/testing "Special-args/meta"
    (is (nil?      (:?meta (log "ns" :report {} {} [               "foo"]))))
    (is (nil?      (:?meta (log "ns" :report {} {} [       {:a :A} "foo"]))))
    (is (map?      (:?meta (log "ns" :report {} {} [^:meta {:a :A} "foo"]))) "First-arg ^:meta {} -> :?meta")
    (is (= ["foo"] (:vargs (log "ns" :report {} {} [^:meta {:a :A} "foo"]))) "First-arg ^:meta {} dropped from vargs")))
