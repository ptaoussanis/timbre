(ns taoensso.timbre
  "Simple, flexible, all-Clojure logging. No XML!"
  {:author "Peter Taoussanis"}
  (:require [clojure.string        :as str]
            [clj-stacktrace.repl   :as stacktrace]
            [taoensso.timbre.utils :as utils])
  (:import  [java.util Date Locale]
            [java.text SimpleDateFormat]))

;;;; Public utils

(defn str-println
  "Like `prn` but prints all objects to output stream as a single atomic
  string. Strings are printed with print, other objects are printed with
  pr. This is faster and avoids interleaving race conditions. "
  [& xs]
  (print (str/join \space (map #(if (string? %) % (with-out-str pr %)) xs)) \newline)
  (flush))

(defn color-str [color & xs]
  (let [ansi-color #(str "\u001b[" (case % :reset  "0"  :black  "30" :red   "31"
                                           :green  "32" :yellow "33" :blue  "34"
                                           :purple "35" :cyan   "36" :white "37"
                                           "0") "m")]
    (str (ansi-color color) (apply str xs) (ansi-color :reset))))

(def red    (partial color-str :red))
(def green  (partial color-str :green))
(def yellow (partial color-str :yellow))

(def default-out (java.io.OutputStreamWriter. System/out))
(def default-err (java.io.PrintWriter.        System/err))

(defmacro with-default-outs
  "Evaluates body with Clojure's default *out* and *err* bindings."
  [& body] `(binding [*out* default-out *err* default-err] ~@body))

(defmacro with-err-as-out
  "Evaluates body with *err* bound to *out*."
  [& body] `(binding [*err* *out*] ~@body))

(defn stacktrace [throwable & [separator]]
  (when throwable
    (str separator throwable ; (str throwable) incl. ex-data for Clojure 1.4+
         "\n\n" (stacktrace/pst-str throwable))))

;;;; Default configuration and appenders

(def ^:dynamic *current-level* nil)
(defmacro with-log-level
  "Allows thread-local config logging level override. Useful for dev & testing."
  [level & body] `(binding [*current-level* ~level] ~@body))

(utils/defonce* config
  "This map atom controls everything about the way Timbre operates.

    APPENDERS
      An appender is a map with keys:
        :doc, :min-level, :enabled?, :async?, :limit-per-msecs, :fn

      An appender's fn takes a single map argument with keys:
        :level, :throwable
        :message,        ; Stringified logging macro args, or nil
        :args,           ; Raw logging macro args (`info`, etc.)
        :ap-config       ; `shared-appender-config`
        :prefix          ; Output of `prefix-fn`
        :profiling-stats ; From `profile` macro
        And also: :instant, :timestamp, :hostname, :ns, :error?

    MIDDLEWARE
      Middleware are fns (applied right-to-left) that transform the map argument
      dispatched to appender fns. If any middleware returns nil, no dispatching
      will occur (i.e. the event will be filtered).

  See source code for examples. See `set-config!`, `merge-config!`, `set-level!`
  for convenient config editing."
  (atom {:current-level :debug ; See also `with-log-level`

         ;;; Control log filtering by namespace patterns (e.g. ["my-app.*"]).
         ;;; Useful for turning off logging in noisy libraries, etc.
         :ns-whitelist []
         :ns-blacklist []

         ;; Fns (applied right-to-left) to transform/filter appender fn args.
         ;; Useful for obfuscating credentials, pattern filtering, etc.
         :middleware []

         ;;; Control :timestamp format
         :timestamp-pattern "yyyy-MMM-dd HH:mm:ss ZZ" ; SimpleDateFormat pattern
         :timestamp-locale  nil ; A Locale object, or nil

         ;; Control :prefix format
         :prefix-fn
         (fn [{:keys [level timestamp hostname ns]}]
           (str timestamp " " hostname " " (-> level name str/upper-case)
                " [" ns "]"))

         ;; Will be provided to all appenders via :ap-config key
         :shared-appender-config {}

         :appenders
         {:standard-out
          {:doc "Prints to *out* or *err* as appropriate. Enabled by default."
           :min-level nil :enabled? true :async? false :limit-per-msecs nil
           :fn (fn [{:keys [error? prefix throwable message]}]
                 (binding [*out* (if error? *err* *out*)]
                   (str-println prefix "-" message (stacktrace throwable))))}

          :spit
          {:doc "Spits to (:spit-filename :shared-appender-config) file."
           :min-level nil :enabled? false :async? false :limit-per-msecs nil
           :fn (fn [{:keys [ap-config prefix throwable message]}]
                 (when-let [filename (:spit-filename ap-config)]
                   (try (spit filename
                              (with-out-str
                                (str-println prefix "-" message
                                             (stacktrace throwable)))
                              :append true)
                        (catch java.io.IOException _))))}}}))

(defn set-config!   [ks val] (swap! config assoc-in ks val))
(defn merge-config! [& maps] (apply swap! config utils/merge-deep maps))
(defn set-level!    [level]  (set-config! [:current-level] level))

;;;; Define and sort logging levels

(def ^:private ordered-levels [:trace :debug :info :warn :error :fatal :report])
(def ^:private scored-levels  (assoc (zipmap ordered-levels (next (range))) nil 0))

(defn error-level? [level] (boolean (#{:error :fatal} level)))

(defn- checked-level-score [level]
  (or (scored-levels level)
      (throw (Exception. (str "Invalid logging level: " level)))))

(def compare-levels
  (memoize (fn [x y] (- (checked-level-score x) (checked-level-score y)))))

(defn sufficient-level?
  [level] (>= (compare-levels level (or *current-level* (:current-level @config))) 0))

;;;; Appender-fn decoration

(defn- wrap-appender-fn
  "Wraps compile-time appender fn with additional runtime capabilities
  controlled by compile-time config."
  [{apfn :fn :keys [async? limit-per-msecs prefix-fn] :as appender}]
  (let [limit-per-msecs (or (:max-message-per-msecs appender)
                            limit-per-msecs)] ; Backwards-compatibility
    (->> ; Wrapping applies per appender, bottom-to-top
     apfn

     ;; Per-appender prefix-fn support (cmp. default prefix-fn)
     ;; TODO Currently undocumented, candidate for removal
     ((fn [apfn]
        (if-not prefix-fn
          apfn
          (fn [apfn-args]
            (apfn (assoc apfn-args
                    :prefix (prefix-fn apfn-args)))))))

     ;; Rate limit support
     ((fn [apfn]
        (if-not limit-per-msecs
          apfn
          (let [timers (atom {})] ; {:hash last-appended-time-msecs ...}
            (fn [{ns :ns [x1 & _] :args :as apfn-args}]
              (let [now    (System/currentTimeMillis)
                    hash   (str ns "/" x1)
                    limit? (fn [last-msecs]
                             (and last-msecs (<= (- now last-msecs)
                                                 limit-per-msecs)))]

                (when-not (limit? (@timers hash))
                  (apfn apfn-args)
                  (swap! timers assoc hash now))

                (when (< (rand) 0.001) ; Occasionally garbage collect
                  (when-let [expired-timers (->> (keys @timers)
                                                 (remove #(limit? (@timers %)))
                                                 (seq))]
                    (apply swap! timers dissoc expired-timers)))))))))

     ;; Async (agent) support
     ((fn [apfn]
        (if-not async?
          apfn
          (let [agent (agent nil :error-mode :continue)]
            (fn [apfn-args] (send-off agent (fn [_] (apfn apfn-args)))))))))))

(defn- make-timestamp-fn
  "Returns a unary fn that formats instants using given pattern string and an
  optional Locale."
  [^String pattern ^Locale locale]
  (let [format (if locale
                 (SimpleDateFormat. pattern locale)
                 (SimpleDateFormat. pattern))]
    (fn [^Date instant] (.format ^SimpleDateFormat format instant))))

(comment ((make-timestamp-fn "yyyy-MMM-dd" nil) (Date.)))

(def get-hostname
  (utils/memoize-ttl
   60000 (fn [] (try (.. java.net.InetAddress getLocalHost getHostName)
                    (catch java.net.UnknownHostException _
                      "UnknownHost")))))

(defn- wrap-appender-juxt
  "Wraps compile-time appender juxt with additional runtime capabilities
  (incl. middleware) controlled by compile-time config. Like `wrap-appender-fn`
  but operates on the entire juxt at once."
  [juxtfn]
  (->> ; Wrapping applies per juxt, bottom-to-top
   juxtfn

   ;; Middleware transforms/filters support
   ((fn [juxtfn]
      (if-let [middleware (seq (:middleware @config))]
        (let [composed-middleware
              (apply comp (map (fn [mf] (fn [args] (when args (mf args))))
                               middleware))]
          (fn [juxtfn-args]
            (when-let [juxtfn-args (composed-middleware juxtfn-args)]
              (juxtfn juxtfn-args))))
        juxtfn)))

   ;; Add compile-time stuff to runtime appender args
   ((fn [juxtfn]
      (let [{ap-config :shared-appender-config
             :keys [timestamp-pattern timestamp-locale prefix-fn]} @config

             timestamp-fn (make-timestamp-fn timestamp-pattern timestamp-locale)]
        (fn [{:keys [instant] :as juxtfn-args}]
          (let [juxtfn-args (merge juxtfn-args {:ap-config ap-config
                                                :timestamp (timestamp-fn instant)
                                                :hostname  (get-hostname)})]
            (juxtfn (assoc juxtfn-args :prefix (prefix-fn juxtfn-args))))))))))

;;;; Caching

;;; Appender-fns

(def appenders-juxt-cache
  "Per-level, combined level-relevant appender-fns to allow for fast runtime
  appender-fn dispatch:
  {:level (wrapped-juxt wrapped-appender-fn wrapped-appender-fn ...) or nil
    ...}"
  (atom {}))

(defn- relevant-appenders [level]
  (->> (:appenders @config)
       (filter #(let [{:keys [enabled? min-level]} (val %)]
                  (and enabled? (>= (compare-levels level min-level) 0))))
       (into {})))

(comment (relevant-appenders :debug)
         (relevant-appenders :trace))

(defn- cache-appenders-juxt! []
  (->>
   (zipmap
    ordered-levels
    (->> ordered-levels
         (map (fn [l] (let [rel-aps (relevant-appenders l)]
                       ;; Return nil if no relevant appenders
                       (when-let [ap-ids (keys rel-aps)]
                         (->> ap-ids
                              (map #(wrap-appender-fn (rel-aps %)))
                              (apply juxt)
                              (wrap-appender-juxt))))))))
   (reset! appenders-juxt-cache)))

;;; Namespace filter

(def ns-filter-cache "@ns-filter-cache => (fn relevant-ns? [ns] ...)"
  (atom (constantly true)))

(defn- ns-match? [ns match]
  (-> (str "^" (-> (str match) (.replace "." "\\.") (.replace "*" "(.*)")) "$")
      re-pattern (re-find (str ns)) boolean))

(defn- cache-ns-filter! []
  (->>
   (let [{:keys [ns-whitelist ns-blacklist]} @config]
     (memoize
      (fn relevant-ns? [ns]
        (and (or (empty? ns-whitelist)
                 (some (partial ns-match? ns) ns-whitelist))
             (or (empty? ns-blacklist)
                 (not-any? (partial ns-match? ns) ns-blacklist))))))
   (reset! ns-filter-cache)))

;;; Prime initial caches and re-cache on config change

(cache-appenders-juxt!)
(cache-ns-filter!)

(add-watch
 config "config-cache-watch"
 (fn [key ref old-state new-state]
   (when (not= (dissoc old-state :current-level)
               (dissoc new-state :current-level))
     (cache-appenders-juxt!)
     (cache-ns-filter!))))

;;;; Define logging macros

(defn logging-enabled?
  "Returns true when current logging level is sufficient and current namespace
  is unfiltered."
  [level] (and (sufficient-level? level) (@ns-filter-cache *ns*)))

(defmacro log*
  "Implementation detail - subject to change..
  Prepares given arguments for, and then dispatches to all level-relevant
  appender-fns. "

  ;; For tools.logging.impl/Logger support
  ([base-appender-args level log-vargs ns throwable message juxt-fn]
     `(when-let [juxt-fn# (or ~juxt-fn (@appenders-juxt-cache ~level))]
        (juxt-fn#
         (conj (or ~base-appender-args {})
           {:instant   (Date.)
            :ns        ~ns
            :level     ~level
            :error?    (error-level? ~level)
            :args      ~log-vargs ; No native tools.logging support
            :throwable ~throwable
            :message   ~message}))
        nil))

  ([base-appender-args level log-args message-fn]
     `(when-let [juxt-fn# (@appenders-juxt-cache ~level)]
        (let [[x1# & xn# :as xs#] (vector ~@log-args)
              has-throwable?# (instance? Throwable x1#)
              log-vargs# (vec (if has-throwable?# xn# xs#))]
          (log* ~base-appender-args
                ~level
                log-vargs#
                ~(str *ns*)
                (when has-throwable?# x1#)
                (when-let [mf# ~message-fn]
                  (when-not (empty? log-vargs#)
                    (apply mf# log-vargs#)))
                juxt-fn#)))))

(defmacro log
  "When logging is enabled, actually logs given arguments with level-relevant
  appender-fns using print-style :message."
  {:arglists '([level & message] [level throwable & message])}
  [level & sigs]
  `(when (logging-enabled? ~level)
     (log* {} ~level ~sigs print-str)))

(defmacro logf
  "When logging is enabled, actually logs given arguments with level-relevant
  appender-fns using format-style :message."
  {:arglists '([level fmt & fmt-args] [level throwable fmt & fmt-args])}
  [level & sigs]
  `(when (logging-enabled? ~level)
     (log* {} ~level ~sigs format)))

(defmacro log-errors [& body] `(try ~@body (catch Throwable t# (error t#))))
(defmacro log-and-rethrow-errors [& body]
  `(try ~@body (catch Throwable t# (error t#) (throw t#))))

(defmacro logged-future [& body] `(future (log-errors ~@body)))

(comment (log-errors (/ 0))
         (log-and-rethrow-errors (/ 0))
         (logged-future (/ 0)))

(defmacro spy
  "Evaluates named expression and logs its result. Always returns the result.
  Defaults to :debug logging level and unevaluated expression as name."
  ([expr] `(spy :debug ~expr))
  ([level expr] `(spy ~level '~expr ~expr))
  ([level name expr]
     `(log-and-rethrow-errors
       (let [result# ~expr] (log ~level ~name result#) result#))))

(defmacro ^:private def-logger [level]
  (let [level-name (name level)]
    `(do
       (defmacro ~(symbol level-name)
         ~(str "Log given arguments at " level " level using print-style args.")
         ~'{:arglists '([& message] [throwable & message])}
         [& sigs#] `(log ~~level ~@sigs#))

       (defmacro ~(symbol (str level-name "f"))
         ~(str "Log given arguments at " level " level using format-style args.")
         ~'{:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
         [& sigs#] `(logf ~~level ~@sigs#)))))

(defmacro ^:private def-loggers []
  `(do ~@(map (fn [level] `(def-logger ~level)) ordered-levels)))

(def-loggers) ; Actually define a logger for each logging level

(defn refer-timbre
  "Shorthand for:
  (require '[taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal report spy with-log-level)])"
  []
  (require '[taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal report spy with-log-level)]))

;;;; Deprecated

(defmacro logp "DEPRECATED: Use `log` instead."
  {:arglists '([level & message] [level throwable & message])}
  [& sigs] `(log ~@sigs)) ; Alias

(defmacro s "DEPRECATED: Use `spy` instead."
  {:arglists '([expr] [level expr] [level name expr])}
  [& args] `(spy ~@args))

;;;; Dev/tests

(comment
  (info)
  (info "a")
  (info "a" "b" "c")
  (info "a" (Exception. "b") "c")
  (info (Exception. "a") "b" "c")
  (log (or nil :info) "Booya")

  (info  "a%s" "b")
  (infof "a%s" "b")

  (set-config! [:ns-blacklist] [])
  (set-config! [:ns-blacklist] ["taoensso.timbre*"])

  (info "foo" "bar")
  (trace (Thread/sleep 5000))
  (time (dotimes [n 10000] (trace "This won't log"))) ; Overhead 5ms/10ms
  (time (dotimes [n 5] (info "foo" "bar")))
  (spy (* 6 5 4 3 2 1))
  (spy :debug :factorial6 (* 6 5 4 3 2 1))
  (info (Exception. "noes!") "bar")
  (spy (/ 4 0))

  (with-log-level :trace (trace "foo"))
  (with-log-level :debug (trace "foo"))

  ;; Middleware
  (info {:name "Robert Paulson" :password "Super secret"})
  (set-config!
   [:middleware]
   [(fn [{:keys [hostname message] :as args}]
      (cond (= hostname "filtered-host") nil ; Filter
            (map? message)
            (if (contains? message :password)
              (assoc args :message (assoc message :password "*****"))
              args)
            :else args))]))