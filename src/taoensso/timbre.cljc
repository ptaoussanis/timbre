(ns taoensso.timbre
  "Simple, flexible logging for Clojure/Script. No XML."
  {:author "Peter Taoussanis (@ptaoussanis)"}
  #?(:clj
     (:require
      [clojure.string     :as str]
      [io.aviso.exception :as aviso-ex]
      [taoensso.encore    :as enc :refer [have have? qb]]
      [taoensso.timbre.appenders.core :as core-appenders])

     :cljs
     (:require
      [clojure.string  :as str]
      [goog.i18n.DateTimeFormat :as dtf]
      [taoensso.encore :as enc :refer [] :refer-macros [have have?]]
      [taoensso.timbre.appenders.core :as core-appenders]))

  #?(:cljs
     (:require-macros
      [taoensso.timbre :as timbre-macros :refer []])))

(if (vector? taoensso.encore/encore-version)
  (enc/assert-min-encore-version [2 126 2])
  (enc/assert-min-encore-version  2.126))

;;;; Config

(def default-timestamp-opts
  "Controls (:timestamp_ data)"
  #?(:cljs {:pattern  :iso8601 #_"yy-MM-dd HH:mm:ss"}
     :clj
     {:pattern  :iso8601     #_"yyyy-MM-dd'T'HH:mm:ss.SSSX" #_"yy-MM-dd HH:mm:ss"
      :locale   :jvm-default #_(java.util.Locale. "en")
      :timezone :utc         #_(java.util.TimeZone/getTimeZone "Europe/Amsterdam")}))

(declare stacktrace)
(defn default-output-fn
  "Default (fn [data]) -> string output fn.
    Use`(partial default-output-fn <opts-map>)` to modify default opts."
  ([     data] (default-output-fn nil data))
  ([opts data] ; For partials
   (let [{:keys [no-stacktrace? stacktrace-fonts]} opts
         {:keys [level ?err #_vargs msg_ ?ns-str ?file hostname_
                 timestamp_ ?line]} data]
     (str
       (when-let [ts (force timestamp_)] (str ts " "))
       #?(:clj (force hostname_))  #?(:clj " ")
       (str/upper-case (name level))  " "
       "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] - "
       (force msg_)
       (when-not no-stacktrace?
         (when-let [err ?err]
           (str enc/system-newline (stacktrace err opts))))))))

;;; Alias core appenders here for user convenience
(declare default-err default-out)
#?(:clj  (enc/defalias         core-appenders/println-appender))
#?(:clj  (enc/defalias         core-appenders/spit-appender))
#?(:cljs (def println-appender core-appenders/println-appender))
#?(:cljs (def console-appender core-appenders/console-appender))

(def default-config
  "Default/example Timbre `*config*` value:

    {:min-level :debug #_[[\"taoensso.*\" :error] [\"*\" :debug]]
     :ns-filter #{\"*\"} #_{:deny #{\"taoensso.*\"} :allow #{\"*\"}}

     :middleware [] ; (fns [appender-data]) -> ?data, applied left->right

     :timestamp-opts default-timestamp-opts ; {:pattern _ :locale _ :timezone _}
     :output-fn      default-output-fn ; (fn [appender-data]) -> string

     :appenders
     #?(:clj
        {:println (println-appender {:stream :auto})
         ;; :spit (spit-appender    {:fname \"./timbre-spit.log\"})
         }

        :cljs
        (if (exists? js/window)
          {:console (console-appender {})}
          {:println (println-appender {})}))}

    See `*config*` for more info."

  {:min-level :debug #_[["taoensso.*" :error] ["*" :debug]]
   :ns-filter #{"*"} #_{:deny #{"taoensso.*"} :allow #{"*"}}

   :middleware [] ; (fns [appender-data]) -> ?data, applied left->right

   :timestamp-opts default-timestamp-opts ; {:pattern _ :locale _ :timezone _}
   :output-fn      default-output-fn ; (fn [appender-data]) -> string

   :appenders
   #?(:clj
      {:println (println-appender {:stream :auto})
       ;; :spit (spit-appender    {:fname "./timbre-spit.log"})
       }

      :cljs
      (if (exists? js/window)
        {:console (console-appender {})}
        {:println (println-appender {})}))})

(comment
  (set-config! default-config)
  (infof "Hello %s" "world :-)"))

(enc/defonce ^:dynamic *config*
  "This map controls all Timbre behaviour including:
    - When to log (via level and namespace filtering)
    - How  to log (which appenders to use)
    - What to log (config to control how data sent to appenders
                   will be formatted to output string)

  See `default-config` for default value (and example config).

  Modify this config with `binding`, `alter-var-root`, or with utils:
       `set-level!`,         `with-level`,
      `set-config!`,        `with-config`,
    `merge-config!`, `with-merged-config`.

  MAIN OPTIONS

    :min-level
      Logging will occur only if a logging call's level is >= this
      min-level. Possible values, in order:

        :trace  = level 0
        :debug  = level 1 ; Default min-level
        :info   = level 2
        :warn   = level 3
        :error  = level 4 ; Error type
        :fatal  = level 5 ; Error type
        :report = level 6 ; High general-purpose (non-error) type

      It's also possible to set the min-level based on the namespace
      by providing a vector that maps `ns-pattern`s to min-levels, e.g.:
      `[[#{\"taoensso.*\"} :error] ... [#{\"*\"} :debug]]`.

      Example `ns-pattern`s:
        #{}, \"*\", \"foo.bar\", \"foo.bar.*\", #{\"foo\" \"bar.*\"},
        {:allow #{\"foo\" \"bar.*\"} :deny #{\"foo.*.bar.*\"}}.

    :ns-filter
      Logging will occur only if a logging call's namespace is permitted
      by this ns-filter. Possible values:

        - Arbitrary (fn may-log-ns? [ns]) predicate fn.
        - An `ns-pattern` (see :min-level docs above).

      Useful for turning off logging in noisy libraries, etc.

    :middleware
      Vector of simple (fn [appender-data]) -> ?new-data fns (applied left->right)
      that transform the data map dispatched to appender fns. If any middleware
      returns nil, NO dispatch will occur (i.e. the event will be filtered).

      Useful for layering advanced functionality. Similar to Ring middleware.

    :timestamp-opts ; Config map, see `default-timestamp-opts`
    :output-fn      ; (fn [appender-data]) -> string, see `default-output-fn`

    :appenders ; {<appender-id> <appender-map>}

      Where each appender-map has keys:
        :enabled?        ; Must be truthy to log
        :min-level       ; Optional *additional* appender-specific min-level
        :ns-filter       ; Optional *additional* appender-specific ns-filter

        :async?          ; Dispatch using agent? Useful for slow appenders (clj only)
        :rate-limit      ; [[<ncalls-limit> <window-msecs>] ...], or nil
                         ; Appender will noop after exceeding given maximum number
                         ; of calls within given rolling window/s.
                         ; e.g. [[100 (encore/ms :mins 1)] [1000 (encore/ms :hours 1)]]
                         ; will limit noop after:
                         ;   - >100  calls in 1 rolling minute, or
                         ;   - >1000 calls in 1 rolling hour

        :output-fn       ; Optional override for inherited (fn [appender-data]) -> string
        :timestamp-opts  ; Optional override for inherited config map
        :fn              ; (fn [appender-data]) -> side-effects, with keys described below

  APPENDER DATA
    An appender's fn takes a single data map with keys:
      :config          ; Entire active config map
      :appender-id     ; Id of appender currently dispatching
      :appender        ; Entire map of appender currently dispatching
      :instant         ; Platform date (java.util.Date or js/Date)
      :level           ; Call's level keyword (e.g. :info) (>= active min-level)
      :error-level?    ; Is level e/o #{:error :fatal}?
      :?ns-str         ; String,  or nil
      :?file           ; String,  or nil
      :?line           ; Integer, or nil ; Waiting on CLJ-865
      :?err            ; First-arg platform error, or nil
      :?meta           ; First-arg map when it has ^:meta metadata, used as a
                         way of passing advanced per-call options to appenders
      :vargs           ; Vector of raw args provided to logging call
      :output_         ; Forceable - final formatted output string created
                       ; by calling (output-fn <this-data-map>)
      :msg_            ; Forceable - args as a string
      :timestamp_      ; Forceable - string
      :hostname_       ; Forceable - string (clj only)
      :output-fn       ; (fn [data]) -> formatted output string
                       ; (see `default-output-fn` for details)
      :context         ; `*context*` value at log time (see `with-context`)
      :spying?         ; Is call occuring via the `spy` macro?

      **NB** - any keys not specifically documented here should be
      considered private / subject to change without notice.

  COMPILE-TIME LEVEL/NS ELISION
    To control :min-level and :ns-filter at compile-time, use:

      - `taoensso.timbre.min-level.edn`  JVM property (read as edn)
      - `taoensso.timbre.ns-pattern.edn` JVM property (read as edn)

      - `TAOENSSO_TIMBRE_MIN_LEVEL_EDN`  env var      (read as edn)
      - `TAOENSSO_TIMBRE_NS_PATTERN_EDN` env var      (read as edn)"

  default-config)

(defmacro with-config        [config & body] `(binding [*config*                            ~config ] ~@body))
(defmacro with-merged-config [config & body] `(binding [*config* (enc/nested-merge *config* ~config)] ~@body))

(declare swap-config!)
(defn     set-config! [m] (swap-config! (fn [_old] m)))
(defn   merge-config! [m] (swap-config! (fn [ old] (enc/nested-merge old m))))
(defn    swap-config! [f & args]
  #?(:cljs (set!                   *config* (apply f *config* args))
     :clj  (apply alter-var-root #'*config* f args)))

(defn      set-level! [level] (swap-config! (fn [m] (assoc m :min-level level))))
(defmacro with-level  [level & body]
  `(binding [*config* (assoc *config* :min-level ~level)] ~@body))

(comment (set-level! :info) *config*)

;;;; Level filtering
;; Terminology note: we loosely distinguish between call/form and min levels,
;; though there's no motivation for a semantic (domain) difference between the
;; two as in Tufte.

(let [err "Invalid Timbre logging level: should be e/o #{:trace :debug :info :warn :error :fatal :report}"
      level->int
      #(case %
         :trace  0
         :debug  1
         :info   2
         :warn   3
         :error  4
         :fatal  5
         :report 6 ; High-level non-error type
         nil)]

  (defn- valid-level?     [x] (if (level->int x) true false))
  (defn- valid-level      [x] (if (level->int x) x (throw (ex-info err {:given x :type (type x)}))))
  (defn- valid-level->int [x] (or (level->int x)   (throw (ex-info err {:given x :type (type x)})))))

(let [valid-level->int valid-level->int]
  (defn #?(:clj level>= :cljs ^:boolean level>=)
    "Implementation detail."
    [x y] (>= ^long (valid-level->int x) ^long (valid-level->int y))))

(comment (qb 1e6 (level>= :info :trace))) ; 89.77

;;;; Namespace filtering
;; Terminology note: we distinguish loosely between `ns-filter` (which may be a
;; fn or `ns-pattern`) and `ns-pattern` (subtype of `ns-filter`).

(let [fn?         fn?
      compile     (enc/fmemoize (fn [x] (enc/compile-str-filter x)))
      conform?*   (enc/fmemoize (fn [x ns] ((compile x) ns)))
      ;; conform? (enc/fmemoize (fn [x ns] (if (fn? x) (x ns) ((compile x) ns))))
      conform?
      (fn [ns-filter ns]
        (if (fn? ns-filter)
          (ns-filter           ns) ; Intentionally uncached, can be handy
          (conform?* ns-filter ns)))]

  (defn- #?(:clj may-log-ns? :cljs ^boolean may-log-ns?)
    "Implementation detail."
    [ns-filter ns] (if (conform? ns-filter ns) true false))

  (def ^:private ns->?min-level
    "[[<ns-pattern> <min-level>] ... [\"*\" <default-min-level>]], ns -> ?min-level"
    (enc/fmemoize
      (fn [specs ns]
        (enc/rsome
          (fn [[ns-pattern min-level]]
            (when (conform?* ns-pattern ns)
              (valid-level min-level)))
          specs)))))

(comment
  (enc/qb 1e6 ; [145.78 275.69]
    (may-log-ns? "*" "taoensso.timbre")
    (ns->?min-level [[#{"taoensso.*" "foo.bar"} :info] ["*" :debug]] "foo.bar")
    (ns->?min-level [["ns.1" :info] ["ns.2" :debug]] "ns.2")))

;;;; Combo filtering

(let [valid-level    valid-level
      ns->?min-level ns->?min-level]

  (defn- get-min-level [default x ns]
    (valid-level
      (or
        (if (vector? x) (ns->?min-level x ns) x)
        default))))

(comment
  (get-min-level :report [["foo" :info]] *ns*)
  (let [ns *ns*]
    (enc/qb 1e6 ; [128.1 191.52]
      (get-min-level :report :info     ns)
      (get-min-level :report [["*" 0]] ns))))

(let [;; Legacy API unfortunately treated empty colls as allow-all
      leglist (fn [x] (when x (if (#{[] #{}} x) nil x)))]
  (defn- legacy-ns-filter [ns-whitelist ns-blacklist]
    (let [ns-whitelist (leglist ns-whitelist)
          ns-blacklist (leglist ns-blacklist)]
      (when (or ns-whitelist ns-blacklist)
        {:allow ns-whitelist :deny ns-blacklist}))))

(comment (legacy-ns-filter [] ["foo"]))

(let [level>=          level>=
      may-log-ns?      may-log-ns?
      get-min-level    get-min-level
      legacy-ns-filter legacy-ns-filter]

  (defn #?(:clj may-log? :cljs ^:boolean may-log?)
    "Implementation detail.
    Returns true iff level and ns are runtime unfiltered."
    ([                  level                ] (may-log? :report level nil     nil))
    ([                  level ?ns-str        ] (may-log? :report level ?ns-str nil))
    ([                  level ?ns-str ?config] (may-log? :report level ?ns-str nil))
    ([default-min-level level ?ns-str ?config]
     (let [config (or ?config *config*) ; NB may also be appender map
           min-level
           (get-min-level default-min-level
             (or
               (get config :min-level)
               (get config :level) ; Legacy
               )
             ?ns-str)]

       (if (level>= level min-level)
         (if-let [ns-filter
                  (or
                    (get config :ns-filter)
                    (legacy-ns-filter ; Legacy
                      (get config :ns-whitelist)
                      (get config :ns-blacklist)))]

           (if (may-log-ns? ns-filter ?ns-str) true false)
           true)
         false)))))

(comment (qb 1e5 (may-log? :info))) ; 122.3

;;;; Compile-time filtering

#?(:clj
   (def ^:private compile-time-min-level
     (when-let [level
                (or
                  (enc/read-sys-val "taoensso.timbre.min-level.edn" "TAOENSSO_TIMBRE_MIN_LEVEL_EDN")
                  (enc/read-sys-val "TIMBRE_LEVEL")     ; Legacy
                  (enc/read-sys-val "TIMBRE_LOG_LEVEL") ; Legacy
                  )]

       (let [level (if (string? level) (keyword level) level)] ; Legacy
         (valid-level level)
         (println (str "Compile-time (elision) Timbre min-level: " level))
         level))))

#?(:clj
   (def ^:private compile-time-ns-filter
     (let [ns-pattern
           (or
             (enc/read-sys-val "taoensso.timbre.ns-pattern.edn" "TAOENSSO_TIMBRE_NS_PATTERN_EDN")
             (enc/read-sys-val "TIMBRE_NS_PATTERN") ; Legacy
             (legacy-ns-filter ; Legacy
               (enc/read-sys-val "TIMBRE_NS_WHITELIST")
               (enc/read-sys-val "TIMBRE_NS_BLACKLIST")))]

       (let [ns-pattern ; Legacy
             (if (map? ns-pattern)
               {:allow (or (:allow ns-pattern) (:whitelist ns-pattern))
                :deny  (or (:deny  ns-pattern) (:blacklist ns-pattern))}
               ns-pattern)]

         (when ns-pattern (println (str "Compile-time (elision) Timbre ns-pattern: " ns-pattern)))
         (or   ns-pattern "*")))))

#?(:clj
   (defn -elide?
     "Returns true iff level or ns are compile-time filtered.
     Called only at macro-expansiom time."
     [level-form ns-str-form]
     (not
       (and
         (or ; Level okay
           (nil? compile-time-min-level)
           (not (valid-level? level-form)) ; Not a compile-time level const
           (level>= level-form compile-time-min-level))

         (or ; Namespace okay
           (not (string? ns-str-form)) ; Not a compile-time ns-str const
           (may-log-ns? compile-time-ns-filter ns-str-form))))))

;;;; Utils

(defn- str-join [xs]
  (enc/str-join " "
    (map
      (fn [x]
        (let [x (enc/nil->str x)] ; Undefined, nil -> "nil"
          (cond
            (record?          x) (pr-str x)
            ;; (enc/lazy-seq? x) (pr-str x) ; Dubious?
            :else x))))
    xs))

(comment
  (defrecord MyRec [x])
  (str-join ["foo" (MyRec. "foo")]))

#?(:clj
   (enc/defonce ^:private get-agent
     (enc/fmemoize (fn [appender-id] (agent nil :error-mode :continue)))))

(comment (get-agent :my-appender))

(defonce ^:private get-rate-limiter
  (enc/fmemoize (fn [appender-id specs] (enc/limiter specs))))

(comment (def rf (get-rate-limiter :my-appender [[10 5000]])))

;;;; Internal logging core

(def ^:dynamic *context* "General-purpose dynamic logging context" nil)
(defmacro  with-context
  "Executes body so that given arbitrary data will be passed (as `:context`)
  to appenders for any enclosed logging calls.

  (with-context
    {:user-name \"Stu\"} ; Will be incl. in data dispatched to appenders
    (info \"User request\"))

  See also `with-context+`."

  [context & body] `(binding [*context* ~context] ~@body))

(defmacro with-context+
  "Like `with-context`, but merges given context into current context."
  [context & body]
  `(binding [*context* (conj (or *context* {}) ~context)]
     ~@body))

(comment (with-context+ {:foo1 :bar1} (with-context+ {:foo2 :bar2} *context*)))

(defn- parse-vargs
  "vargs -> [?err ?meta ?msg-fmt api-vargs]"
  [?err msg-type vargs]
  (let [auto-error? (enc/kw-identical? ?err :auto)
        fmt-msg?    (enc/kw-identical? msg-type :f)
        [v0] vargs]

    (if (and auto-error? (enc/error? v0))
      (let [?err     v0
            ?meta    nil
            vargs    (enc/vrest vargs)
            ?msg-fmt (if fmt-msg? (let [[v0] vargs] v0) nil)
            vargs    (if fmt-msg? (enc/vrest vargs) vargs)]

        [?err ?meta ?msg-fmt vargs])

      (let [?meta    (if (and (map? v0) (:meta (meta v0))) v0 nil)
            ?err     (or (:err ?meta) (if auto-error? nil ?err))
            ?meta    (dissoc ?meta :err)
            vargs    (if ?meta    (enc/vrest vargs) vargs)
            ?msg-fmt (if fmt-msg? (let [[v0] vargs] v0) nil)
            vargs    (if fmt-msg? (enc/vrest vargs) vargs)]

        [?err ?meta ?msg-fmt vargs]))))

(comment
  (let [ex (Exception. "ex")]
    (qb 10000
      (parse-vargs :auto :f ["fmt" :a :b :c])
      (parse-vargs :auto :p [ex    :a :b :c])
      (parse-vargs :auto :p [^:meta {:foo :bar} :a :b :c])
      (parse-vargs :auto :p [       {:foo :bar} :a :b :c])
      (parse-vargs :auto :p [ex])
      (parse-vargs :auto :p [^:meta {:err ex}   :a :b :c])))
  ;; [2.79 2.51 6.13 1.65 1.94 6.2]
  (infof                                 "Hi %s" "steve")
  (infof ^:meta {:hash :bar}             "Hi %s" "steve")
  (infof ^:meta {:err (Exception. "ex")} "Hi %s" "steve"))

(declare get-hostname)

(defn- get-timestamp [timestamp-opts instant]
  #?(:clj
     (let [{:keys [pattern locale timezone]} timestamp-opts]
       ;; iso8601 example: 2020-09-14T08:31:17.040Z (UTC)
       (.format ^java.text.SimpleDateFormat
         (enc/simple-date-format* pattern locale timezone)
         instant))

     :cljs
     (let [{:keys [pattern]} timestamp-opts]
       (if (enc/kw-identical? pattern :iso8601)
         (.toISOString (js/Date. instant)) ; e.g. 2020-09-14T08:29:49.711Z (UTC)
         ;; Pattern can also be be `goog.i18n.DateTimeFormat.Format`, etc.
         (.format
           (goog.i18n.DateTimeFormat. pattern)
           instant)))))

(comment (get-timestamp default-timestamp-opts (enc/now-udt)))

(defn -log! "Core low-level log fn. Implementation detail!"

  ;; Backward-compatible arities for convenience of AOT tools, Ref.
  ;; https://github.com/fzakaria/slf4j-timbre/issues/20
  ([config level ?ns-str ?file ?line msg-type ?err vargs_ ?base-data            ] (-log! config level ?ns-str ?file ?line msg-type ?err vargs_ ?base-data nil         false))
  ([config level ?ns-str ?file ?line msg-type ?err vargs_ ?base-data callsite-id] (-log! config level ?ns-str ?file ?line msg-type ?err vargs_ ?base-data callsite-id false))
  ([config level ?ns-str ?file ?line msg-type ?err vargs_ ?base-data callsite-id spying?]
   (when (may-log? :report level ?ns-str config)
     (let [instant (enc/now-dt*)
           context *context*
           vargs   @vargs_

           [?err ?meta ?msg-fmt vargs]
           (parse-vargs ?err msg-type vargs)

           data ; Pre-middleware
           (conj
             (or ?base-data {})
             {:instant instant
              :level   level
              :context context
              :config  config  ; Entire config!
              :?ns-str ?ns-str
              :?file   ?file
              :?line   ?line
              #?(:clj :hostname_) #?(:clj (delay (get-hostname)))
              :error-level? (#{:error :fatal} level)
              :?err     ?err
              :?err_    (delay ?err) ; Deprecated
              :?msg-fmt ?msg-fmt     ; Undocumented
              :?meta    ?meta
              :vargs    vargs
              :spying?  spying?})

           ?data ; Post middleware
           (reduce ; Apply middleware: data->?data
             (fn [acc mf]
               (let [result (mf acc)]
                 (if (nil? result)
                   (reduced nil)
                   result)))
             data
             (:middleware config))]

       (when-let [data ?data] ; Not filtered by middleware
         (let [{:keys [vargs]} data
               data (assoc data :vargs_ (delay vargs)) ; Deprecated
               data
               (enc/assoc-nx data
                 :msg_
                 (delay
                   (case msg-type
                     nil ""
                     :p  (str-join vargs)
                     :f  #_(enc/format* (have string? ?msg-fmt) vargs)
                     (do
                       (when-not (string? ?msg-fmt)
                         (throw
                           (ex-info "Timbre format-style logging call without a format pattern (string)"
                             #_data
                             {:level    level
                              :location (str (or ?ns-str ?file "?") ":"
                                             (or ?line         "?"))})))

                       (enc/format* ?msg-fmt vargs))))

                 ;; Uniquely identifies a particular logging call for
                 ;; rate limiting, etc.
                 :hash_
                 (delay
                   (hash
                     ;; Nb excl. instant
                     [callsite-id      ; Only useful for direct macro calls
                      ?msg-fmt
                      (get ?meta :hash ; Explicit hash provided
                        vargs)])))

               ;; Optimization: try maximize output+timestamp sharing
               ;; between appenders
               output-fn1 (enc/fmemoize (get config :output-fn default-output-fn))
               timestamp-opts1 (conj default-timestamp-opts (get config :timestamp-opts))
               get-timestamp_ ; (fn [timestamp-opts]) -> Shared delay
               (enc/fmemoize
                 (fn [opts]
                   (delay (get-timestamp opts (get data :instant)))))]

           (reduce-kv
             (fn [_ id appender]
               (when (and (:enabled? appender) (may-log? :trace level ?ns-str appender))

                 (let [rate-limit-specs (:rate-limit appender)
                       rate-limit-okay?
                       (or
                         (empty? rate-limit-specs)
                         (let [rl-fn (get-rate-limiter id rate-limit-specs)]
                           (not (rl-fn (force (:hash_ data))))))]

                   (when rate-limit-okay?
                     (let [{:keys [async?] apfn :fn} appender

                           output-fn
                           (let [f (:output-fn appender)]
                             (if (or (nil? f) (enc/kw-identical? f :inherit))
                               output-fn1
                               f))

                           timestamp_
                           (let [opts (:timestamp-opts appender)]
                             (if (or (nil? opts) (enc/kw-identical? opts :inherit))
                               (get-timestamp_       timestamp-opts1)
                               (get-timestamp_ (conj timestamp-opts1 opts))))

                           output_
                           (delay
                             (output-fn (assoc data :timestamp_ timestamp_)))

                           data
                           (conj data
                             {:appender-id id
                              :appender    appender
                              :output-fn   output-fn
                              :output_     output_
                              :timestamp_  timestamp_})

                           ?data ; Final data prep before going to appender
                           (if-let [mfn (:middleware-fn appender)]
                             (mfn data) ; Deprecated, undocumented
                             data)]

                       (when-let [data ?data] ; Not filtered by middleware

                         ;; NB Unless `async?`, we currently allow appenders
                         ;; to throw since it's not particularly obvious
                         ;; how/where we should report problems. Throwing
                         ;; early seems preferable to just silently dropping
                         ;; errors. In effect, we currently require appenders
                         ;;  to take responsibility over appropriate trapping.

                         #?(:cljs (apfn data)
                            :clj
                            (if async?
                              (send-off (get-agent id) (fn [_] (apfn data)))
                              (apfn data)))))))))
             nil
             (:appenders config))))))
   nil))

(comment
  (-log! *config* :info nil nil nil :p :auto
    (delay [(do (println "hi") :x) :y]) nil "callsite-id" false))

(defn- fline [and-form] (:line (meta and-form)))

(defmacro log! ; Public wrapper around `-log!`
  "Core low-level log macro. Useful for tooling, etc.

    * `level`    - must eval to a valid logging level
    * `msg-type` - must eval to e/o #{:p :f nil}
    * `opts`     - ks e/o #{:config :?err :?ns-str :?file :?line :?base-data :spying?}

  Supports compile-time elision when compile-time const vals
  provided for `level` and/or `?ns-str`."
  [level msg-type args & [opts]]
  (have [:or nil? sequential?] args) ; To allow -> (delay [~@args])
  (let [{:keys [?ns-str] :or {?ns-str (str *ns*)}} opts]
    ;; level, ns may/not be compile-time consts:
    (when-not #?(:clj (-elide? level ?ns-str) :cljs false)
      (let [{:keys [config ?err ?file ?line ?base-data spying?]
             :or   {config 'taoensso.timbre/*config*
                    ?err   :auto ; => Extract as err-type v0
                    ?file  #?(:clj *file* :cljs nil)
                    ;; NB waiting on CLJ-865:
                    ?line (fline &form)}} opts

            ?file (when (not= ?file "NO_SOURCE_PATH") ?file)

            ;; Identifies this particular macro expansion; note that this'll
            ;; be fixed for any fns wrapping `log!` (notably `tools.logging`,
            ;; `slf4j-timbre`, etc.):
            callsite-id
            (hash [level msg-type args ; Unevaluated args (arg forms)
                   ?ns-str ?file ?line (rand)])]

        `(-log! ~config ~level ~?ns-str ~?file ~?line ~msg-type ~?err
           (delay [~@args]) ~?base-data ~callsite-id ~spying?)))))

(comment
  (do           (log! :info :p ["foo"]))
  (macroexpand '(log! :info :p ["foo"]))
  (macroexpand '(log! :info :p ["foo"] {:?line 42})))

;;;; Benchmarking

(comment
  (set-level! :debug)
  (may-log? :trace)
  (with-level :trace (log? :trace))
  (qb 1e4
    (may-log? :trace)
    (may-log? :trace "foo")
    (tracef "foo")
    (when false "foo"))
  ;; [1.38 1.42 2.08 0.26]

  (defmacro with-sole-appender [appender & body]
    `(with-config (assoc *config* :appenders {:appender ~appender}) ~@body))

  (with-sole-appender {:enabled? true :fn (fn [data] nil)}
    (qb 1e4 (info "foo"))) ; ~74.58 ; Time to delays ready

  (with-sole-appender {:enabled? true :fn (fn [data] (force (:output_ data)))}
    (qb 1e4 (info "foo"))) ; ~136.68 ; Time to output ready
  )

;;;; Main public API-level stuff
;; TODO Have a bunch of cruft here trying to work around CLJ-865 to some extent

;;; Log using print-style args
(defmacro log*  [config level & args] `(log! ~level  :p ~args ~{:?line (fline &form) :config config}))
(defmacro log          [level & args] `(log! ~level  :p ~args ~{:?line (fline &form)}))
(defmacro trace              [& args] `(log! :trace  :p ~args ~{:?line (fline &form)}))
(defmacro debug              [& args] `(log! :debug  :p ~args ~{:?line (fline &form)}))
(defmacro info               [& args] `(log! :info   :p ~args ~{:?line (fline &form)}))
(defmacro warn               [& args] `(log! :warn   :p ~args ~{:?line (fline &form)}))
(defmacro error              [& args] `(log! :error  :p ~args ~{:?line (fline &form)}))
(defmacro fatal              [& args] `(log! :fatal  :p ~args ~{:?line (fline &form)}))
(defmacro report             [& args] `(log! :report :p ~args ~{:?line (fline &form)}))

;;; Log using format-style args
(defmacro logf* [config level & args] `(log! ~level  :f ~args ~{:?line (fline &form) :config config}))
(defmacro logf         [level & args] `(log! ~level  :f ~args ~{:?line (fline &form)}))
(defmacro tracef             [& args] `(log! :trace  :f ~args ~{:?line (fline &form)}))
(defmacro debugf             [& args] `(log! :debug  :f ~args ~{:?line (fline &form)}))
(defmacro infof              [& args] `(log! :info   :f ~args ~{:?line (fline &form)}))
(defmacro warnf              [& args] `(log! :warn   :f ~args ~{:?line (fline &form)}))
(defmacro errorf             [& args] `(log! :error  :f ~args ~{:?line (fline &form)}))
(defmacro fatalf             [& args] `(log! :fatal  :f ~args ~{:?line (fline &form)}))
(defmacro reportf            [& args] `(log! :report :f ~args ~{:?line (fline &form)}))

(comment
  (infof "hello %s" "world")
  (infof (Exception.) "hello %s" "world")
  (infof (Exception.)))

(defmacro -log-errors [?line & body]
  `(enc/catching (do ~@body) e#
     (do
       #_(error e#) ; CLJ-865
       (log! :error :p [e#] ~{:?line ?line}))))

(defmacro -log-and-rethrow-errors [?line & body]
  `(enc/catching (do ~@body) e#
     (do
       #_(error e#) ; CLJ-865
       (log! :error :p [e#] ~{:?line ?line})
       (throw e#))))

(defmacro -logged-future [?line & body] `(future (-log-errors ~?line ~@body)))

(defmacro log-errors             [& body] `(-log-errors             ~(fline &form) ~@body))
(defmacro log-and-rethrow-errors [& body] `(-log-and-rethrow-errors ~(fline &form) ~@body))
(defmacro logged-future          [& body] `(-logged-future          ~(fline &form) ~@body))

#?(:clj
   (defn handle-uncaught-jvm-exceptions!
     "Sets JVM-global DefaultUncaughtExceptionHandler."
     [& [handler]]
     (let [handler
           (or handler
             (fn [throwable ^Thread thread]
               (errorf throwable "Uncaught exception on thread: %s"
                 (.getName thread))))]

       (Thread/setDefaultUncaughtExceptionHandler
         (reify Thread$UncaughtExceptionHandler
           (uncaughtException [this thread throwable] (handler throwable thread)))))))

(comment
  (log-errors             (/ 0))
  (log-and-rethrow-errors (/ 0))
  (logged-future          (/ 0))
  (handle-uncaught-jvm-exceptions!))

(defmacro -spy [?line config level name expr]
  `(-log-and-rethrow-errors ~?line
     (let [result# ~expr]
       ;; Subject to elision:
       ;; (log* ~config ~level ~name "=>" result#) ; CLJ-865
       (log! ~level :p [~name "=>" result#]
         ~{:?line ?line :config config :spying? true})

       ;; NOT subject to elision:
       result#)))

(defmacro spy
  "Evaluates named expression and logs its result. Always returns the result.
  Defaults to :debug logging level and unevaluated expression as name."
  ([                  expr] `(-spy ~(fline &form) *config* :debug '~expr ~expr))
  ([       level      expr] `(-spy ~(fline &form) *config* ~level '~expr ~expr))
  ([       level name expr] `(-spy ~(fline &form) *config* ~level  ~name ~expr))
  ([config level name expr] `(-spy ~(fline &form) ~config  ~level  ~name ~expr)))

(defmacro get-env [] `(enc/get-env))

(comment
  ((fn foo [x y] (get-env)) 5 10)
  (with-config
    (assoc example-config :appenders
      {:default {:enabled? true :fn (fn [m] (println #_(keys m) (:spying? m)))}})
    (info "foo")
    (spy  "foo")))

#?(:clj
   (defn refer-timbre
     "Shorthand for:
     (require '[taoensso.timbre :as timbre
                :refer (log  trace  debug  info  warn  error  fatal  report
                        logf tracef debugf infof warnf errorf fatalf reportf
                        spy get-env log-env)])"
     []
     (require '[taoensso.timbre :as timbre
                :refer (log  trace  debug  info  warn  error  fatal  report
                         logf tracef debugf infof warnf errorf fatalf reportf
                         spy get-env log-env)])))

;;;; Misc public utils

#?(:clj
   (defn color-str [color & xs]
     (let [ansi-color #(format "\u001b[%sm"
                         (case % :reset  "0"  :black  "30" :red   "31"
                               :green  "32" :yellow "33" :blue  "34"
                               :purple "35" :cyan   "36" :white "37"
                               "0"))]
       (str (ansi-color color) (apply str xs) (ansi-color :reset)))))

#?(:clj (def default-out (java.io.OutputStreamWriter. System/out)))
#?(:clj (def default-err (java.io.PrintWriter.        System/err)))
(defmacro with-default-outs [& body]
  `(binding [*out* default-out, *err* default-err] ~@body))

#?(:clj
   (do ; Hostname stuff
     (defn get-?hostname "Returns live local hostname, or nil." []
       (try (.getHostName (java.net.InetAddress/getLocalHost))
            (catch java.net.UnknownHostException _ nil)))

     (let [unknown "UnknownHost"]
       (def get-hostname "Returns cached hostname string."
         (enc/memoize (enc/ms :mins 1)
           (fn []
             (try
               (let [p (promise)]
                 ;; Android doesn't like hostname calls on the main thread.
                 ;; Using `future` would start the Clojure agent threadpool though,
                 ;; which can slow down application shutdown w/o a `(shutdown-agents)`
                 ;; call.
                 (.start (Thread. (fn [] (deliver p (get-?hostname)))))
                 (or (deref p 5000 nil) unknown))
               (catch Exception _ unknown))))))))

(comment (get-hostname))

#?(:clj
   (def ^:private default-stacktrace-fonts
     (or
       (enc/read-sys-val "taoensso.timbre.default-stacktrace-fonts.edn" "TAOENSSO_TIMBRE_DEFAULT_STACKTRACE_FONTS_EDN")
       (enc/read-sys-val "TIMBRE_DEFAULT_STACKTRACE_FONTS") ; Legacy
       nil)))

(defn stacktrace
  ([err     ] (stacktrace err nil))
  ([err opts]
   #?(:cljs (or (.-stack err) (str err)) ; TODO Alternatives?
      :clj
      (let [stacktrace-fonts ; {:stacktrace-fonts nil->{}}
            (if-let [e (find opts :stacktrace-fonts)]
              (let [st-fonts (val e)]
                (if (nil? st-fonts)
                  {}
                  st-fonts))
              default-stacktrace-fonts)]

        (if-let [fonts stacktrace-fonts]
          (binding [aviso-ex/*fonts* fonts]
            (do (aviso-ex/format-exception err)))
          (do   (aviso-ex/format-exception err)))))))

(comment (stacktrace (Exception. "Boo") {:stacktrace-fonts {}}))

(defmacro sometimes "Handy for sampled logging, etc."
  [probability & body]
   `(do (assert (<= 0 ~probability 1) "Probability: 0 <= p <= 1")
        (when (< (rand) ~probability) ~@body)))

;;;; Deprecated

(enc/deprecated
  #?(:cljs (def console-?appender core-appenders/console-appender))
  (def ordered-levels [:trace :debug :info :warn :error :fatal :report])
  (def log? may-log?)
  (def example-config "DEPRECATED, prefer `default-config`" default-config)
  (defn logging-enabled? [level compile-time-ns] (may-log? level (str compile-time-ns)))
  (defn str-println      [& xs] (str-join xs))
  (defmacro with-log-level      [level  & body] `(with-level  ~level  ~@body))
  (defmacro with-logging-config [config & body] `(with-config ~config ~@body))
  (defmacro logp [& args] `(log ~@args))
  (defmacro log-env
    ([                 ] `(log-env :debug))
    ([       level     ] `(log-env ~level "&env"))
    ([       level name] `(log-env *config* ~level ~name))
    ([config level name] `(log* ~config ~level ~name "=>" (get-env)))))
