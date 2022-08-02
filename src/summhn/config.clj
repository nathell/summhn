(ns summhn.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io])
  (:refer-clojure :exclude [get]))

(defn read-config []
  (aero/read-config (io/resource "config.edn")))

(def config (read-config))

(defn get [& path]
  (get-in config path))
