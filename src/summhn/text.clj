(ns summhn.text)

(defn- index-seq
  ([s c] (index-seq s c 0))
  ([s c i] (let [idx (.indexOf s c i)]
             (when-not (= idx -1)
               (lazy-seq
                (cons idx
                      (index-seq s c (inc idx))))))))

(defn start
  "Returns the string `s` trimmed at up to max-length characters
  at an apparent sentence boundary."
  [s max-length]
  (let [indices (map inc
                     (sort (concat (index-seq s ".")
                                   (index-seq s "?")
                                   (index-seq s "!"))))
        n (or (last (take-while #(< % max-length) indices))
              (min max-length (count s)))]
    (subs s 0 n)))
