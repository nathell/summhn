(ns summhn.openai
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [summhn.config :as config]
            [summhn.text :as text]))

(defn- parse-response [resp]
  (some-> resp
          :body
          (json/parse-string keyword)
          :choices
          first
          :text
          str/trim))

(defn- openai-call [{:keys [temperature max-tokens prompt]
                     :or {temperature 0
                          max-tokens 100}}]
  (parse-response
   (http/post
    "https://api.openai.com/v1/completions"
    {:content-type :json
     :accept       :json
     :headers      {"Authorization" (str "Bearer " (config/get :openai :key))}
     :body         (json/encode {:model       (config/get :openai :model)
                                 :prompt      prompt
                                 :temperature temperature
                                 :max_tokens  max-tokens})})))

(defn summarize [story]
  (openai-call {:temperature (config/get :openai :temperature)
                :prompt      (format (config/get :openai :prompt)
                                     (text/start story (config/get :max-article-length)))
                :max-tokens  (config/get :openai :max-tokens)}))
