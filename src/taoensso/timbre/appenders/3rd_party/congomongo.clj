(ns taoensso.timbre.appenders.3rd-party.congomongo
  "MongoDB appender. Requires https://github.com/aboekhoff/congomongo."
  {:author "Emlyn Corrin (@emlyn)"}
  (:require [somnium.congomongo :as mongo]
            [taoensso.timbre    :as timbre]
            [taoensso.encore    :as encore]))

;; TODO Test port to Timbre v4

(defn- connect [{:keys [db server write-concern]}]
  (let [args (merge {:host "127.0.0.1" :port 27017} server)
        c    (mongo/make-connection db args)]
    (when write-concern
      (mongo/set-write-concern c write-concern))
    c))

(def ^:private conn_ (atom nil))
(defn- ensure-conn [config] (swap! conn_ #(or % (connect config))))

(defn- default-entry-fn [data]
  (let [{:keys [instant level hostname_
                context ?err ?ns-str ?file ?line msg_]} data]
    {:instant  instant
     :level    level
     :hostname (force hostname_)
     :context  context
     :?err     (when-let [err ?err] (str err))
     :?ns-str  ?ns-str
     :?file    ?file
     :?line    ?line
     :msg      (force msg_)}))

(defn- log-message [config collection entry-fn data]
  (let [entry (entry-fn data)]
    (mongo/with-mongo (ensure-conn config)
      (mongo/insert! collection entry))))

(defn congomongo-appender
  "Returns a congomongo MongoDB appender.
  (congomongo-appender
    {:db \"logs\"
     :collection \"myapp\"
     :write-concern :acknowledged
     :server {:host \"127.0.0.1\"
     :port 27017}})"
  [config]
  (let [{:keys [collection entry-fn]
         :or   {entry-fn default-entry-fn}} config]
    {:enabled?   true
     :async?     true
     :min-level  :warn
     :rate-limit [[1 1000]] ; 1/sec
     :output-fn  :inherit
     :fn (fn [data] (log-message config collection entry-fn data))}))
