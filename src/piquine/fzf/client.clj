(ns piquine.fzf.client
  "Minimal HTTP client for fzf's --listen API."
  (:require
   [cheshire.core :as cjson]
   [clojure.java.shell :as shell]
   [org.httpkit.client :as http]))

(defonce fzf-listen-port "9878")

(defn get-fzf-state
  "Returns fzf's current state as a Clojure map: {:query :current :selected
  :matches :totalCount ...}. fzf must have been launched with --listen=PORT."
  ([] (get-fzf-state fzf-listen-port))
  ([port]
   (-> @(http/get (str "http://localhost:" port))
       :body
       (cjson/parse-string true))))

(defn send-fzf-command
  "POST an fzf action (or chain like 'pos(1)+toggle') to the listen port."
  [port command-str]
  (try
    (when (seq command-str)
      (let [result (shell/sh "curl" "-XPOST" (str "127.0.0.1:" port) "-d" command-str)]
        (when-not (zero? (:exit result))
          (println (str "Error sending command to fzf: " (:err result))))))
    (catch Exception e
      (println (str "Exception sending command to fzf: " (.getMessage e))))))
