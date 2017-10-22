(ns async-chain.dev
  (:require [async-chain.core :as core]
            [figwheel.client :as fw]))

(defn -main []
  (fw/start { }))

(set! *main-cli-fn* -main)
