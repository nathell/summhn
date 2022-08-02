(ns summhn.core
  (:require [summhn.scrape :as scrape]
            [summhn.output :as output])
  (:import java.time.Instant))

(defn run [_opts]
  (let [timestamp (str (Instant/now))
        content (output/generate (scrape/get-articles))]
    (output/save! "index.html" content)
    (output/save! (str "previously/" timestamp ".html") content)
    (output/save! "about.html" (output/about))))
