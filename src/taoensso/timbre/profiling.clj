(ns taoensso.timbre.profiling
  "Logging profiler for Timbre, adapted from clojure.contrib.profile."
  {:author "Peter Taoussanis"}
  (:require [clojure.tools.macro   :as macro]
            [taoensso.timbre       :as timbre]
            [taoensso.timbre.utils :as utils]))

(def ^:dynamic *pdata* "{::pname [time1 time2 ...]}" nil)

(defmacro pspy
  "Profile spy. When in the context of a *pdata* binding, records execution time
  of named body. Always returns the body's result."
  [name & body]
  `(if-not *pdata* (do ~@body)
     (let [name# (utils/fq-keyword ~name)
           start-time# (System/nanoTime)]
       (try (do ~@body)
            (finally
              (let [elapsed# (- (System/nanoTime) start-time#)]
                (swap! *pdata* #(assoc % name# (conj (% name# []) elapsed#)))))))))

(defmacro p [name & body] `(pspy ~name ~@body)) ; Alias

(declare pdata-stats format-pdata)

(defmacro with-pdata [level & body]
  `(if-not (timbre/logging-enabled? ~level ~(str *ns*))
     {:result (do ~@body)}
     (binding [*pdata* (atom {})]
       {:result (p ::clock-time ~@body)
        :stats  (pdata-stats @*pdata*)})))

(defmacro profile
  "When logging is enabled, executes named body with profiling enabled. Body
  forms wrapped in (pspy) will be timed and time stats logged. Always returns
  body's result.

  Note that logging appenders will receive both a formatted profiling string AND
  the raw profiling stats under a special :profiling-stats key (useful for
  queryable db logging)."
  [level name & body]
  `(let [{result# :result stats# :stats} (with-pdata ~level ~@body)]
     (when stats#
       (timbre/log* {:profile-stats stats#} :format ~level
                    "Profiling: %s\n%s" (utils/fq-keyword ~name)
                    (format-pdata stats#)))
     result#))

(defmacro sampling-profile
  "Like `profile`, but only enables profiling with given probability."
  [level probability name & body]
  `(do (assert (<= 0 ~probability 1) "Probability: 0<=p<=1")
       (if-not (< (rand) ~probability) (do ~@body)
               (profile ~level ~name ~@body))))

(defn pdata-stats
  "{::pname [time1 time2 ...] ...} => {::pname {:min <min-time> ...} ...}
  For performance, stats are calculated once only after all data have been
  collected."
  [pdata]
  (reduce-kv
   (fn [m pname times]
     (let [count (max 1 (count times))
           time  (reduce + times)
           mean  (long (/ time count))
           mad   (long (/ (reduce + (map #(Math/abs (long (- % mean)))
                                         times)) ; Mean absolute deviation
                          count))]
       (assoc m pname {:count count
                       :min   (apply min times)
                       :max   (apply max times)
                       :mean  mean
                       :mad   mad
                       :time  time})))
   {} (or pdata {})))

(defn format-pdata [stats & [sort-field]]
  (let [clock-time (-> stats ::clock-time :time) ; How long entire profile body took
        stats          (dissoc stats ::clock-time)
        accounted      (reduce + (map :time (vals stats)))
        max-name-width (apply max (map (comp count str)
                                       (conj (keys stats) "Accounted Time")))
        pattern   (str "%" max-name-width "s %6d %9s %10s %9s %9s %7d %1s%n")
        s-pattern (.replace pattern \d \s)
        perc      #(Math/round (/ %1 %2 0.01))
        ft (fn [nanosecs]
             (let [pow     #(Math/pow 10 %)
                   ok-pow? #(>= nanosecs (pow %))
                   to-pow  #(utils/round-to %2 (/ nanosecs (pow %1)))]
               (cond (ok-pow? 9) (str (to-pow 9 1) "s")
                     (ok-pow? 6) (str (to-pow 6 0) "ms")
                     (ok-pow? 3) (str (to-pow 3 0) "μs")
                     :else       (str nanosecs     "ns"))))]

    (with-out-str
      (printf s-pattern "Name" "Calls" "Min" "Max" "MAD" "Mean" "Time%" "Time")
      (doseq [pname (->> (keys stats)
                         (sort-by #(- (get-in stats [% (or sort-field :time)]))))]
        (let [{:keys [count min max mean mad time]} (stats pname)]
          (printf pattern pname count (ft min) (ft max) (ft mad)
                  (ft mean) (perc time clock-time) (ft time))))

      (printf s-pattern "Clock Time" "" "" "" "" "" 100 (ft clock-time))
      (printf s-pattern "Accounted Time" "" "" "" "" ""
              (perc accounted clock-time) (ft accounted)))))

(defmacro defnp "Like `defn` but wraps body in `p` macro."
  {:arglists '([name doc-string? attr-map? [params] prepost-map? body])}
  [name & sigs]
  (let [[name [params & sigs]] (macro/name-with-attributes name sigs)
        prepost-map (when (and (map? (first sigs)) (next sigs)) (first sigs))
        body (if prepost-map (next sigs) sigs)]
    `(defn ~name ~params ~prepost-map
       (p ~(clojure.core/name name)
          ~@body))))

(comment (defnp foo "Docstring "[x] "boo" (* x x))
         (macroexpand '(defnp foo "Docstring "[x] "boo" (* x x)))
         (profile :info :defnp-test (foo 5)))

(comment
  (profile :info :sleepy-threads
           (dotimes [n 5]
             (Thread/sleep 100) ; Unaccounted
             (p :1ms  (Thread/sleep 1))
             (p :2s   (Thread/sleep 2000))
             (p :50ms (Thread/sleep 50))
             (p :rand (Thread/sleep (if (> 0.5 (rand)) 10 500)))
             (p :10ms (Thread/sleep 10))
             "Result"))

  (p :hello "Hello, this is a result") ; Falls through (no *pdata* context)

  (defn my-fn
    []
    (let [nums (vec (range 1000))]
      (+ (p :fast-sleep (Thread/sleep 1) 10)
         (p :slow-sleep (Thread/sleep 2) 32)
         (p :add  (reduce + nums))
         (p :sub  (reduce - nums))
         (p :mult (reduce * nums))
         (p :div  (reduce / nums)))))

  (profile :info :Arithmetic (dotimes [n 100] (my-fn)))

  (sampling-profile :info 0.2 :sampling-test (p :string "Hello!")))
