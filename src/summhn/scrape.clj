(ns summhn.scrape
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [skyscraper.core :as skyscraper]
            [summhn.cleanup :as cleanup]
            [summhn.config :as config]
            [summhn.openai :as openai]
            [taoensso.timbre :as log]))

(def hn-api-prefix "https://hacker-news.firebaseio.com/v0/")

(def seed
  [{:url (str hn-api-prefix "topstories.json")
    :processor ::stories}])

(defn download-error-handler
  [error options context]
  (log/warnf "[download] Ignoring failed download: %s" error)
  (skyscraper/respond-with {:status 200, :headers {}, :body (.getBytes "")} options context))

(defn parse-json [headers body _ctx]
  (json/parse-string (skyscraper/parse-string headers body _ctx) true))

(defn maybe-parse-html [headers body _ctx]
  (if (str/includes? (str (headers "content-type")) "html")
    (skyscraper/parse-reaver headers body _ctx)
    :not-html))

(skyscraper/defprocessor ::stories
  :parse-fn parse-json
  :process-fn (fn [res _ctx]
                (for [item (take (config/get :num-stories) res)]
                  {:item item
                   :url (str hn-api-prefix "item/" item ".json")
                   :processor ::story-meta})))

(skyscraper/defprocessor ::story-meta
  :parse-fn parse-json
  :cache-template "hn/meta/:item"
  :process-fn (fn [res _ctx]
                (cond-> res
                  (:url res) (assoc :processor ::story-content
                                    :xurl (:url res)))))

(skyscraper/defprocessor ::story-content
  :parse-fn maybe-parse-html
  :cache-template "hn/story/:item"
  :process-fn (fn [res _ctx]
                (if (= res :not-html)
                  {:omit true}
                  (let [story (-> res cleanup/cleanup .root .text)
                        summary (when (and story (seq story))
                                  (try
                                    (openai/summarize story)
                                    (catch Exception e
                                      (log/warnf e "Summarize error: %s" (:item _ctx))
                                      nil)))]
                    {:story story, :summary summary}))))

(defn get-articles []
  (log/set-level! :info)
  (doall
   (reverse
    (skyscraper/scrape seed
                       :parallelism 1
                       :download-mode :sync
                       :download-error-handler download-error-handler
                       :html-cache true
                       :processed-cache true))))
