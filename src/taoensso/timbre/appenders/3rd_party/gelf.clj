(ns taoensso.timbre.appenders.3rd-party.gelf
  "Appender that sends GELF messages to a centralized logging server
  (e.g. Graylog). Requires https://github.com/Graylog2/gelfclient."
  {:author "Dave Owens (@davewo)"}
  (:require [taoensso.timbre :as timbre])
  (:import
   [org.graylog2.gelfclient
    GelfConfiguration
    GelfMessageBuilder
    GelfTransports
    GelfMessageLevel]
   [java.net InetSocketAddress]))

(let [gelf-levels
      {:info  GelfMessageLevel/INFO
       :warn  GelfMessageLevel/WARNING
       :error GelfMessageLevel/ERROR
       :fatal GelfMessageLevel/CRITICAL}]

  (defn timbre-to-gelf-level [level]
    (get gelf-levels level GelfMessageLevel/WARNING)))

(defn make-gelf-transport
  "Returns a new GelfTransport object, capable of sending a GelfMessage to a
  remote server. Params:
    `host`     - IP address or hostname string of the remote logging server
    `port`     - TCP or UDP port on which the server listens
    `protocol` - e/o #{:tcp :udp}"
  [host port protocol]
  {:pre [(#{:udp :tcp} protocol)]}
  (let [protocols {:udp GelfTransports/UDP :tcp GelfTransports/TCP}
        transport (protocol protocols)
        config    (-> (GelfConfiguration. (InetSocketAddress. host port))
                      (.transport transport))]
    (GelfTransports/create config)))

(defn data->gelf-message
  [data]
  (let [{:keys [msg_ hostname_
                level instant
                context ?err ?ns-str
                ?file ?line]}
        data

        log-level   (timbre-to-gelf-level level)
        msg         (or (and (not-empty (force msg_))
                             (force msg_))
                        (and ?err
                             (.getMessage ?err))
                        "EMPTY MSG")
        msg-builder (-> (GelfMessageBuilder. msg (force hostname_))
                        (.level log-level)
                        (.timestamp (.getTime instant)))]
    (cond-> msg-builder
      ?err    (.fullMessage (timbre/stacktrace ?err {:stacktrace-fonts {}}))
      context (.additionalField "context" context)
      ?ns-str (.additionalField "namespace" ?ns-str)
      ?file   (.additionalField "file" ?file)
      ?line   (.additionalField "line" ?line))
    (.build msg-builder)))

(defn gelf-appender
  "Returns a Timbre appender that sends gelf messages to a remote host. Params:
    `gelf-server` - IP address or hostname string of the remote logging server
    `port`        - TCP or UDP port on which the server listens
    `protocol`    - e/o #{:tcp :udp}, defaults to :udp"
  ([gelf-server port         ] (gelf-appender gelf-server port :udp))
  ([gelf-server port protocol]
   (let [tranport (make-gelf-transport gelf-server port protocol)]
     {:enabled?       true
      :async?         false
      :min-level      nil
      :rate-limit     nil
      :output-fn      :inherit
      :gelf-transport tranport
      :fn
      (fn [data]
        (.send (get-in data [:appender :gelf-transport])
               (data->gelf-message data)))})))
