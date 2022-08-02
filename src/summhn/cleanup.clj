(ns summhn.cleanup
  (:import [com.chimbori.crux.extractors ExtractionHelpersKt PostprocessHelpers PostprocessHelpers$Companion]
           [kotlin.jvm JvmClassMappingKt]
           [kotlin.reflect.full KClasses]))

;; I don't really like Crux's API design (they complect the document extraction
;; with downloading, and their URL handling permeates the whole thing), so
;; here's some ugly Kotlin interop that reaches out to Crux's innards and
;; does what I need from it.

(defn- postprocess [el]
  (let [companion (KClasses/getCompanionObjectInstance
                   (JvmClassMappingKt/getKotlinClass PostprocessHelpers))]
    (PostprocessHelpers$Companion/.postprocess$Crux companion el)))

(defn cleanup
  "Takes a JSoup document and returns another one containing only
  the salient content."
  [doc]
  (let [nodes (ExtractionHelpersKt/getNodes doc)
        best (->> nodes
                  (sort-by #(ExtractionHelpersKt/getWeight %) >)
                  first)]
    (postprocess best)))
