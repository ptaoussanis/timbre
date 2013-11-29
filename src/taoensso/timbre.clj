(ns taoensso.timbre "Simple, flexible, all-Clojure logging. No XML!"
  {:author "Peter Taoussanis"}
  (:require [clojure.string        :as str]
            [io.aviso.exception    :as aviso-ex]
            [taoensso.timbre.utils :as utils])
  (:import  [java.util Date Locale]
            [java.text SimpleDateFormat]))

;;;; Public utils

(defn str-println
  "Like `println` but prints all objects to output stream as a single
  atomic string. This is faster and avoids interleaving race conditions."
  [& xs] (print (str (str/join \space (filter identity xs)) \newline))
         (flush))

(defn color-str [color & xs]
  (let [ansi-color #(format "\u001b[%sm"
                      (case % :reset  "0"  :black  "30" :red   "31"
                              :green  "32" :yellow "33" :blue  "34"
                              :purple "35" :cyan   "36" :white "37"
                              "0"))]
    (str (ansi-color color) (apply str xs) (ansi-color :reset))))

(def default-out (java.io.OutputStreamWriter. System/out))
(def default-err (java.io.PrintWriter.        System/err))

(defmacro with-default-outs
  "Evaluates body with Clojure's default *out* and *err* bindings."
  [& body] `(binding [*out* default-out *err* default-err] ~@body))

(defmacro with-err-as-out "Evaluates body with *err* bound to *out*."
  [& body] `(binding [*err* *out*] ~@body))

(defn stacktrace "Default stacktrace formatter for use by appenders, etc."
  [throwable & [separator]]
  (when throwable
    (str separator (aviso-ex/format-exception throwable))))

;;;; Logging levels
;; Level precendence: compile-time > dynamic > atom

(def level-compile-time
  "Constant, compile-time logging level determined by the `TIMBRE_LOG_LEVEL`
  environment variable. When set, overrules dynamically-configurable logging
  level as a performance optimization (e.g. for use in performance sensitive
  production environments)."
  (keyword (System/getenv "TIMBRE_LOG_LEVEL")))

(def ^:dynamic *level-dynamic* nil)
(defmacro with-log-level
  "Allows thread-local config logging level override. Useful for dev & testing."
  [level & body] `(binding [*level-dynamic* ~level] ~@body))

(def level-atom (atom :debug))
(defn set-level! [level] (reset! level-atom level))

;;;

(def ^:private levels-ordered [:trace :debug :info :warn :error :fatal :report])
(def ^:private levels-scored  (assoc (zipmap levels-ordered (next (range))) nil 0))

(defn error-level? [level] (boolean (#{:error :fatal} level))) ; For appenders, etc.

(defn- level-checked-score [level]
  (or (levels-scored level)
      (throw (Exception. (format "Invalid logging level: %s" level)))))

(def ^:private levels-compare (memoize (fn [x y] (- (level-checked-score x)
                                                   (level-checked-score y)))))

(declare config)
(defn- level-sufficient? [level]
  (>= (levels-compare level
        (or level-compile-time
            *level-dynamic*
            (:current-level @config) ; DEPRECATED, here for backwards comp
            @level-atom)) 0))

;;;; Default configuration and appenders

(def example-config
  "APPENDERS
     An appender is a map with keys:
      :doc, :min-level, :enabled?, :async?, :limit-per-msecs, :fn

     An appender's fn takes a single map with keys:
      :level, :throwable
      :args,          ; Raw logging macro args (as given to `info`, etc.).
      :message,       ; Stringified logging macro args, or nil.
      :default-output ; Output of `fmt-output-fn`, used by built-in appenders.
      :ap-config      ; `shared-appender-config`.
      :profile-stats  ; From `profile` macro.
      And also: :instant, :timestamp, :hostname, :ns, :error?

   MIDDLEWARE
     Middleware are fns (applied right-to-left) that transform the map
     dispatched to appender fns. If any middleware returns nil, no dispatching
     will occur (i.e. the event will be filtered).

  The `example-config` code contains more details.
  See also `set-config!`, `merge-config!`, `set-level!`."

  {;;; Control log filtering by namespace patterns (e.g. ["my-app.*"]).
   ;;; Useful for turning off logging in noisy libraries, etc.
   :ns-whitelist []
   :ns-blacklist []

   ;; Fns (applied right-to-left) to transform/filter appender fn args.
   ;; Useful for obfuscating credentials, pattern filtering, etc.
   :middleware []

   ;;; Control :timestamp format
   :timestamp-pattern "yyyy-MMM-dd HH:mm:ss ZZ" ; SimpleDateFormat pattern
   :timestamp-locale  nil ; A Locale object, or nil

   :prefix-fn ; DEPRECATED, here for backwards comp
   (fn [{:keys [level timestamp hostname ns]}]
     (str timestamp " " hostname " " (-> level name str/upper-case)
          " [" ns "]"))

   ;; Default output formatter used by built-in appenders. Custom appenders
   ;; may (but are not required to use) its output (:default-output).
   :fmt-output-fn
   (fn [{:keys [level throwable message timestamp hostname ns]}]
     ;; <timestamp> <hostname> <LEVEL> [<ns>] - <message> <throwable>
     (format "%s %s %s [%s] - %s%s"
       timestamp hostname (-> level name str/upper-case) ns (or message "")
       (or (stacktrace throwable "\n") "")))

   :shared-appender-config {} ; Provided to all appenders via :ap-config key
   :appenders
   {:standard-out
    {:doc "Prints to *out*/*err*. Enabled by default."
     :min-level nil :enabled? true :async? false :limit-per-msecs nil
     :fn (fn [{:keys [error? default-output]}]
           (binding [*out* (if error? *err* *out*)]
             (str-println default-output)))}

    :spit
    {:doc "Spits to `(:spit-filename :shared-appender-config)` file."
     :min-level nil :enabled? false :async? false :limit-per-msecs nil
     :fn (fn [{:keys [ap-config default-output]}]
           (when-let [filename (:spit-filename ap-config)]
             (try (spit filename default-output :append true)
                  (catch java.io.IOException _))))}}})

(utils/defonce* config (atom example-config))
(defn set-config!   [ks val] (swap! config assoc-in ks val))
(defn merge-config! [& maps] (apply swap! config utils/merge-deep maps))

;;;; Appender-fn decoration

(defn- wrap-appender-fn
  "Wraps compile-time appender fn with additional runtime capabilities
  controlled by compile-time config."
  [{apfn :fn :keys [async? limit-per-msecs] :as appender}]
  (let [limit-per-msecs (or (:max-message-per-msecs appender)
                            limit-per-msecs)] ; Backwards comp
    (->> ; Wrapping applies per appender, bottom-to-top
     apfn

     ;; Rate limit support
     ((fn [apfn]
        (if-not limit-per-msecs apfn
          (let [timers (atom {})] ; {:hash last-appended-time-msecs ...}
            (fn [{ns :ns [x1 & _] :args :as apfn-args}]
              (let [now    (System/currentTimeMillis)
                    hash   (str ns "/" x1) ; TODO Alternatives?
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
        (if-not async? apfn
          (let [agent (agent nil :error-mode :continue)]
            (fn [apfn-args] (send-off agent (fn [_] (apfn apfn-args)))))))))))

(defn- make-timestamp-fn
  "Returns a unary fn that formats instants using given pattern string and an
  optional Locale."
  ;; Thread safe SimpleDateTime soln. from instant.clj, Ref. http://goo.gl/CEBJnQ
  [^String pattern ^Locale locale]
  (let [format (proxy [ThreadLocal] [] ; For thread safety
                   (initialValue []
                     (if locale
                       (SimpleDateFormat. pattern locale)
                       (SimpleDateFormat. pattern))))]
    (fn [^Date instant] (.format ^SimpleDateFormat (.get format) instant))))

(comment ((make-timestamp-fn "yyyy-MMM-dd" nil) (Date.)))

(def ^:private get-hostname
  (utils/memoize-ttl 60000
    (fn []
      (let [p (promise)]
        (future ; Android doesn't like this on the main thread
          (deliver p
            (try (.. java.net.InetAddress getLocalHost getHostName)
                 (catch java.net.UnknownHostException _
                   "UnknownHost"))))
        @p))))

(defn- wrap-appender-juxt
  "Wraps compile-time appender juxt with additional runtime capabilities
  (incl. middleware) controlled by compile-time config. Like `wrap-appender-fn`
  but operates on the entire juxt at once."
  [juxtfn]
  (->> ; Wrapping applies per juxt, bottom-to-top
   juxtfn

   ;; Post-middleware stuff
   ((fn [juxtfn]
      ;; Compile-time:
      (let [{ap-config :shared-appender-config
             :keys [timestamp-pattern timestamp-locale
                    prefix-fn fmt-output-fn]} @config
            timestamp-fn (make-timestamp-fn timestamp-pattern timestamp-locale)]
        (fn [juxtfn-args]
          ;; Runtime:
          (when-let [{:keys [instant msg-type args]} juxtfn-args]
            (let [juxtfn-args (if-not msg-type juxtfn-args ; tools.logging
                                (-> juxtfn-args
                                    (dissoc :msg-type)
                                    (assoc  :message
                                      (when-not (empty? args)
                                        (case msg-type
                                          :format    (apply format    args)
                                          :print-str (apply print-str args)
                                          :nil    nil)))))]
              (juxtfn
               (merge juxtfn-args
                 {:timestamp      (timestamp-fn instant)
                  ;; DEPRECATED, here for backwards comp:
                  :prefix         (when-let [f prefix-fn]     (f juxtfn-args))
                  :default-output (when-let [f fmt-output-fn] (f juxtfn-args))}))))))))

   ;; Middleware transforms/filters support
   ((fn [juxtfn]
      ;; Compile-time:
      (if-let [middleware (seq (:middleware @config))]
        (let [composed-middleware
              (apply comp (map (fn [mf] (fn [args] (when args (mf args))))
                               middleware))]
          (fn [juxtfn-args]
            ;; Runtime:
            (when-let [juxtfn-args (composed-middleware juxtfn-args)]
              (juxtfn juxtfn-args))))
        juxtfn)))

   ;; Pre-middleware stuff
   ((fn [juxtfn]
      ;; Compile-time:
      (let [{ap-config :shared-appender-config} @config]
        (fn [juxtfn-args]
          ;; Runtime:
          (juxtfn (merge juxtfn-args {:ap-config ap-config
                                      :hostname  (get-hostname)}))))))))

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
                  (and enabled? (>= (levels-compare level min-level) 0))))
       (into {})))

(comment (relevant-appenders :debug)
         (relevant-appenders :trace))

(defn- cache-appenders-juxt! []
  (->>
   (zipmap
    levels-ordered
    (->> levels-ordered
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

;;;; Logging macros

(defmacro logging-enabled?
  "Returns true iff current logging level is sufficient and current namespace
  unfiltered. The namespace test is runtime, the logging-level test compile-time
  iff a compile-time logging level was specified."
  [level]
  (if level-compile-time
    (when (level-sufficient? level)
      `(@ns-filter-cache ~(str *ns*)))
    `(and (level-sufficient? ~level) (@ns-filter-cache ~(str *ns*)))))

(comment (def compile-time-level :info)
         (def compile-time-level nil)
         (macroexpand-1 '(logging-enabled? :debug)))

(defn send-to-appenders! "Implementation detail - subject to change."
  [;; Args provided by both Timbre, tools.logging:
   level base-appender-args log-vargs ns throwable message
   ;; Additional args provided by Timbre only:
   & [juxt-fn msg-type file line]]
  (when-let [juxt-fn (or juxt-fn (@appenders-juxt-cache level))]
    (juxt-fn
     (conj (or base-appender-args {})
       {:instant   (Date.)
        :ns        ns
        :file      file ; No tools.logging support
        :line      line ; No tools.logging support
        :level     level
        :error?    (error-level? level)
        :args      log-vargs ; No tools.logging support
        :throwable throwable
        :message   message  ; Timbre: nil,  tools.logging: nil or string
        :msg-type  msg-type ; Timbre: nnil, tools.logging: nil
        }))
    nil))

(defmacro log* "Implementation detail - subject to change."
  [msg-type level base-appender-args & log-args]
  {:pre [(#{:nil :print-str :format} msg-type)]}
  `(when (logging-enabled? ~level)
     (when-let [juxt-fn# (@appenders-juxt-cache ~level)]
       (let [[x1# & xn# :as xs#] (vector ~@log-args)
             has-throwable?# (instance? Throwable x1#)
             log-vargs# (vec (if has-throwable?# xn# xs#))]
         (send-to-appenders!
          ~level
          ~base-appender-args
          log-vargs#
          ~(str *ns*)
          (when has-throwable?# x1#)
          nil ; Timbre generates msg only after middleware
          juxt-fn#
          ~msg-type
          (let [file# ~*file*] (when (not= file# "NO_SOURCE_PATH") file#))
          ;; TODO Waiting on http://dev.clojure.org/jira/browse/CLJ-865:
          ~(:line (meta &form)))))))

(defmacro log "Logs using print-style args."
  {:arglists '([level & message] [level throwable & message])}
  [level & sigs] `(log* :print-str ~level {} ~@sigs))

(defmacro logf "Logs using format-style args."
  {:arglists '([level fmt & fmt-args] [level throwable fmt & fmt-args])}
  [level & sigs] `(log* :format ~level {} ~@sigs))

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
         ~(str "Logs at " level " level using print-style args.")
         ~'{:arglists '([& message] [throwable & message])}
         [& sigs#] `(log ~~level ~@sigs#))

       (defmacro ~(symbol (str level-name "f"))
         ~(str "Logs at " level " level using format-style args.")
         ~'{:arglists '([fmt & fmt-args] [throwable fmt & fmt-args])}
         [& sigs#] `(logf ~~level ~@sigs#)))))

(defmacro ^:private def-loggers []
  `(do ~@(map (fn [level] `(def-logger ~level)) levels-ordered)))

(def-loggers) ; Actually define a logger for each logging level

(defn refer-timbre
  "Shorthand for:
  (require
    '[taoensso.timbre :as timbre
      :refer (log  trace  debug  info  warn  error  fatal  report
              logf tracef debugf infof warnf errorf fatalf reportf
              spy logged-future with-log-level)])
  (require '[taoensso.timbre.utils :refer (sometimes)])
  (require
    '[taoensso.timbre.profiling :as profiling :refer (pspy profile defnp)])"
  []
  (require
   '[taoensso.timbre :as timbre
     :refer (log  trace  debug  info  warn  error  fatal  report
             logf tracef debugf infof warnf errorf fatalf reportf
             spy logged-future with-log-level)])
  (require '[taoensso.timbre.utils :refer (sometimes)])
  (require
   '[taoensso.timbre.profiling :as profiling :refer (pspy profile defnp)]))

;;;; Deprecated

(defmacro logp "DEPRECATED: Use `log` instead."
  {:arglists '([level & message] [level throwable & message])}
  [& sigs] `(log ~@sigs)) ; Alias

(defmacro s "DEPRECATED: Use `spy` instead."
  {:arglists '([expr] [level expr] [level name expr])}
  [& args] `(spy ~@args))

(def red    "DEPRECATED: Use `color-str` instead." (partial color-str :red))
(def green  "DEPRECATED: Use `color-str` instead." (partial color-str :green))
(def yellow "DEPRECATED: Use `color-str` instead." (partial color-str :yellow))

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
  (spy :info (* 6 5 4 3 2 1))
  (spy :info :factorial6 (* 6 5 4 3 2 1))
  (info (Exception. "noes!") "bar")
  (spy (/ 4 0))

  (with-log-level :trace (trace "foo"))
  (with-log-level :debug (trace "foo"))

  ;; Middleware
  (info {:name "Robert Paulson" :password "Super secret"})
  (set-config! [:middleware] [])
  (set-config! [:middleware]
    [(fn [{:keys [hostname message args] :as ap-args}]
       (if (= hostname "filtered-host") nil ; Filter
         (assoc ap-args :args
           ;; Replace :password vals in any map args:
           (mapv (fn [arg] (if-not (map? arg) arg
                            (if-not (contains? arg :password) arg
                              (assoc arg :password "****"))))
                 args))))]))
