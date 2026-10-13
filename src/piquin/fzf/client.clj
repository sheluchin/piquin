(ns piquin.fzf.client
  "Minimal HTTP client for fzf's --listen API."
  (:require
   [cheshire.core :as cjson]
   [clojure.java.shell :as shell]
   [org.httpkit.client :as http]))

(defonce ^:private active-port (atom nil))

(defn listen-port
  "Port of the fzf that piquin.fzf.runtime/run! is currently driving."
  []
  @active-port)

(defn set-listen-port! [port] (reset! active-port port))

(defn free-port
  "Ask the OS for an unused local port. Each run! gets its own, so two
  pickers (or a stale process from an earlier run) never share one."
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn get-fzf-state
  "Returns fzf's current state as a Clojure map: {:query :current :selected
  :matches :totalCount ...}. fzf must have been launched with --listen=PORT."
  ([] (get-fzf-state (listen-port)))
  ([port]
   (-> @(http/get (str "http://localhost:" port))
       :body
       (cjson/parse-string true))))

(defn post-action!
  "POST an fzf action in-process (no curl spawn); cheap enough to call on a
  timer. Swallows errors, e.g. when fzf isn't listening yet."
  ([action] (post-action! (listen-port) action))
  ([port action]
   (try
     @(http/post (str "http://127.0.0.1:" port) {:body action :timeout 500})
     (catch Exception _ nil))))

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
