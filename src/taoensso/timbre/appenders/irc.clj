(ns taoensso.timbre.appenders.irc
  "IRC appender. Depends on https://github.com/flatland/irclj."
  {:author "Emlyn Corrin"}
  (:require [clojure.string  :as str]
            [irclj.core      :as irclj]
            [taoensso.timbre :as timbre]))

(def conn (atom nil))

(defn connect [{:keys [host port pass nick user name chan]
                :or   {port 6667}}]
  (let [conn (irclj/connect host port nick
                            :username  user
                            :real-name name
                            :pass      pass
                            :callbacks {})]
    (irclj/join conn chan)
    conn))

(defn ensure-conn [conf]
  (or @conn
      (reset! conn (connect conf))))

(defn send-message [{:keys [prefix throwable message chan] :as config}]
  (let [conn (ensure-conn config)
        lines (-> (str message (timbre/stacktrace throwable "\n"))
                  (str/split #"\n"))]
    (irclj/message conn chan prefix (first lines))
    (doseq [line (rest lines)]
      (irclj/message conn chan ">" line))))

(defn make-irc-appender
  "Sends IRC messages using irclj.
  Needs :irc config map in :shared-appender-config, e.g.:
   {:host \"irc.example.org\" :port 6667 :nick \"logger\"
    :name \"My Logger\" :chan \"#logs\"}"
  [& [appender-opts {:keys [irc-config]}]]
  (let [default-appender-opts
        {:enabled?  true
         :min-level :info
         :prefix-fn (fn [args] (-> args :level name str/upper-case))}]
    (merge default-appender-opts appender-opts
           {:fn
            (fn [{:keys [ap-config prefix throwable message]}]
              (when-let [irc-config (or irc-config (:irc ap-config))]
                (send-message
                 (assoc irc-config
                   :prefix    prefix
                   :throwable throwable
                   :message   message))))})))

(def irc-appender
  (make-irc-appender))
