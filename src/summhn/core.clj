(ns summhn.core
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [skyscraper.core :as skyscraper]
            [taoensso.timbre :refer [warnf]]
            [hiccup2.core :as hiccup]))

(defn postprocess [el]
  (let [companion (kotlin.reflect.full.KClasses/getCompanionObjectInstance
                   (kotlin.jvm.JvmClassMappingKt/getKotlinClass
                    com.chimbori.crux.extractors.PostprocessHelpers))]
    (com.chimbori.crux.extractors.PostprocessHelpers$Companion/.postprocess$Crux companion el)))

(defn cleanup [doc]
  (let [nodes (com.chimbori.crux.extractors.ExtractionHelpersKt/getNodes doc)
        best (->> nodes
                  (sort-by #(com.chimbori.crux.extractors.ExtractionHelpersKt/getWeight %) >)
                  first)]
    (postprocess best)))

(def openai-key
  "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")

(defn parse-response [resp]
  (some-> resp
          :body
          (json/parse-string keyword)
          :choices
          first
          :text
          str/trim))

(def max-length 3000)

(defn index-seq
  ([s c] (index-seq s c 0))
  ([s c i] (let [idx (.indexOf s c i)]
             (when-not (= idx -1)
               (lazy-seq
                (cons idx
                      (index-seq s c (inc idx))))))))

(defn start [article]
  (let [indices (map inc
                     (sort (concat (index-seq article ".")
                                   (index-seq article "?")
                                   (index-seq article "!"))))
        n (or (last (take-while #(< % max-length) indices))
              (min max-length (count article)))]
    (subs article 0 n)))

(defn openai-call [{:keys [temperature max-tokens prompt]
                    :or {temperature 0
                         max-tokens 100}}]
  (parse-response
   (http/post
    "https://api.openai.com/v1/completions"
    {:content-type :json
     :accept       :json
     :headers      {"Authorization" (str "Bearer " openai-key)}
     :body         (json/encode {:model       "text-davinci-002"
                                 :prompt      prompt
                                 :temperature temperature
                                 :max_tokens  max-tokens})})))

(defn download-error-handler
  [error options context]
  (warnf "[download] Ignoring failed download: %s" error)
  (skyscraper/respond-with {:headers {}, :body ""} options context))

(defn parse-json [headers body _ctx]
  (json/parse-string (skyscraper/parse-string headers body _ctx) true))

(defn maybe-parse-html [headers body _ctx]
  (if (str/includes? (str (headers "content-type")) "html")
    (skyscraper/parse-reaver headers body _ctx)
    :not-html))

(defn summarize [story]
  (openai-call {:temperature 0.4
                :prompt (str "Summarize the following article in one sentence: " (start story) "\n\n")}))

(def hn-api-prefix "https://hacker-news.firebaseio.com/v0/")

(def num-stories 30)

(skyscraper/defprocessor ::stories
  :parse-fn parse-json
  :process-fn (fn [res _ctx]
                (for [item (take num-stories res)]
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
                  (let [story (-> res cleanup .root .text)
                        summary (when (and story (seq story))
                                  (try
                                    (summarize story)
                                    (catch Exception e
                                      (warnf "Summarize error: %s" (:item _ctx))
                                      nil)))]
                    {:story story, :summary summary}))))

(def seed
  [{:url (str hn-api-prefix "topstories.json")
    :processor ::stories}])

(taoensso.timbre/set-level! :info)

(defn get-articles []
  (doall
   (reverse
    (skyscraper/scrape seed
                       :parallelism 1
                       :download-mode :sync
                       :download-error-handler download-error-handler
                       :html-cache true
                       :processed-cache true
                       ))))

(defn layout [content]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Autosummarized HN"]
    [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/bulma@0.9.4/css/bulma.min.css"}]
    [:base {:href "https://danieljanus.pl/autosummarized-hn/"}]]
   [:body
    [:nav.navbar {:role "navigation" :aria-label "main navigation"}
     [:div.navbar-brand
      [:h1.title.px-5.py-4
       [:a {:href "index.html"} "Autosummarized HN"]]]
     [:div.navbar-menu.is-active
      [:div.navbar-start
       [:a.navbar-item {:href "about.html"} "About"]
       [:a.navbar-item {:href "previously"} "Previously"]]
      [:div.navbar-end
       [:div.navbar-item "Generated on " (str (java.util.Date.))]]]]
    [:section.section
     content]]])

(defn generate [items]
  (layout
   [:div.container.px-3
      [:p.block.is-size-6
       [:em "All summaries have been generated automatically by GPT-3. No responsibility is claimed for their contents nor its accuracy."]]
      [:ol.items
       (for [{:keys [title score by xurl item summary] :as entry} items
             :let [hn-url (str "https://news.ycombinator.com/item?id=" item)]]
         [:li.item.block
          [:p.title.is-4.mb-0 [:a {:href (or xurl hn-url)} title]]
          [:p.block score " points by " by " | " [:a {:href hn-url} "view on HN"]]
          [:p.subtitle.is-5 summary]])]]))

(defn about []
  (layout
   [:div.container.px-3
       [:p.title "FAQ"]
       [:ul
        [:li.block
         [:p.is-size-4 "What is this?"]
         [:p
          [:a {:href "https://news.ycombinator.com/"} "Hacker News"]
          ", but with one-sentence summaries automatically generated by "
          [:a {:href "https://openai.com/"} "GPT-3"]
          "."]]
        [:li.block
         [:p.is-size-4 "Why?"]
         [:p "Because I can. And because it might actually save me (and perhaps someone else?) time, by helping to decide what to read."]]
        [:li.block
         [:p.is-size-4 "How?"]
         [:p "Every now and then (currently every 2 hours), the script gets the top 30 HN posts (with the help of HN API), then downloads those that point to HTML pages, cleans them up (with "
          [:a {:href "https://github.com/chimbori/crux/"} "Crux"]
          ", and then for each of them, tells GPT-3: "
          [:em "Summarize the following article in one sentence: [article]."]
          " That's it, really. To keep the request/response sizes within API limits, I trim the article at sentence boundary at around 3000 characters."]]
        [:li.block
         [:p.is-size-4 "What's the tech stack?"]
         [:p "A Clojure script gets periodically run by Cron and generates static HTMLs. I cache the articles as well as output from GPT-3, so hopefully I'm not going to go bankrupt."]]
        [:li.block
         [:p.is-size-4 "What's your OpenAI bill?"]
         [:p "Dunno yet. Depends on how often the HN stories change. I've capped my bill at $20, so if it stops working it's probably because I hit the limit."]]
        [:li.block
         [:p.is-size-4 "Can it be updated more often?"]
         [:p "Sure. " [:a {:href "mailto:dj@danieljanus.pl"} "Wanna sponsor the OpenAI bill?"]]]
        [:li.block
         [:p.is-size-4 "Is there source code?"]
         [:p "There will be, but not now. It's a thing hacked together in like 4 hours, and the code is really quick and dirty."]]
        [:li.block
         [:p.is-size-4 "Whodunit?"]
         [:p [:a {:href "https://danieljanus.pl"} "Daniel Janus."]]]]]))

(def out-dir "out/")

(defn run [opts]
  (let [timestamp (str (java.time.Instant/now))
        data (get-articles)]
    (spit (str out-dir "index.html") (hiccup/html (generate data)))
    (spit (str out-dir "previously/" timestamp ".html") (hiccup/html (generate data)))
    (spit (str out-dir "about.html") (hiccup/html (about)))))
