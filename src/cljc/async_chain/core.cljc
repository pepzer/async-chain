;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns async-chain.core
  #?@(:clj [(:require [clojure.core.async :as a :refer [go]])]
      :cljs [(:require [cljs.core.async :as a])
             (:require-macros [cljs.core.async.macros :refer [go]])]))

(defprotocol IChain
  (-success [this result-id result])
  (-failure [this error-id error])
  (-update [this step-id]))

(defrecord ChainSignal
    [chain-id
     result-id
     result
     error-id
     error
     step-id])

(extend-protocol IChain
  ChainSignal
  (-success [this result-id result]
    (assoc this :result-id result-id :result result :step-id result-id))

  (-failure [this error-id error]
    (assoc this :error-id error-id :error error :step-id error-id))

  (-update [this step-id]
    (assoc this :step-id step-id)))

(defn chan? [ch]
  #?(:clj (instance? clojure.core.async.impl.channels.ManyToManyChannel ch)
     :cljs (instance? cljs.core.async.impl.channels.ManyToManyChannel ch)))

(defn- chain-wait
  ([chain-ch wait-fn] (chain-wait chain-ch :chain/wait wait-fn))
  ([chain-ch wait-id wait-fn]
   (go (when-let [{:keys [error] :as signal} (a/<! chain-ch)]
         (if error
           (do
             (a/put! chain-ch (-update signal wait-id))
             nil)
           (let [new-result (try
                              (let [res (wait-fn)]
                                (if (chan? res)
                                  (a/<! res)
                                  res))
                              #?(:clj (catch Throwable e
                                        {:chain/error e})
                                 :cljs (catch js/Object e
                                         {:chain/error e})))]

             (if (or (and (associative? new-result)
                          (:chain/error new-result))
                     #?(:clj (instance? Throwable new-result)
                        :cljs (instance? js/Error new-result)))
               (a/put! chain-ch (-failure signal wait-id new-result))
               (a/put! chain-ch (-success signal wait-id new-result)))
             new-result))))))

(defn- chain-go
  ([chain-ch async-fn] (chain-go chain-ch :chain/go async-fn))
  ([chain-ch async-id async-fn]
   (go (let [async-ch (try
                        (async-fn)
                        #?(:clj (catch Throwable e
                                  (go {:chain/error e}))
                           :cljs (catch js/Object e
                                   (go {:chain/error e}))))
             [alts-res alts-ch] (a/alts! [chain-ch async-ch])]

         (cond
           (= alts-ch chain-ch)
           (if (:error alts-res)
             (do (a/put! chain-ch (-update alts-res async-id))
                 (a/<! (a/<! async-ch)))

             (let [async-res (a/<! (a/<! async-ch))]
               (if (or (and (associative? async-res)
                            (:chain/error async-res))
                       #?(:clj (instance? Throwable async-res)
                          :cljs (instance? js/Error async-res)))

                 (do
                   (a/put! chain-ch (-failure alts-res async-id async-res))
                   async-res)

                 (do
                   (a/put! chain-ch (-success alts-res async-id async-res))
                   async-res))))

           (= alts-ch async-ch)
           (let [async-res (a/<! alts-res)
                 signal (a/<! chain-ch)]
             (if (:error signal)
               (do
                 (a/put! chain-ch (-update signal async-id))
                 async-res)

               (if (or (and (associative? async-res)
                            (:chain/error async-res))
                       #?(:clj (instance? Throwable async-res)
                          :cljs (instance? js/Error async-res)))
                 (do
                   (a/put! chain-ch (-failure signal async-id async-res))
                   async-res)

                 (do
                   (a/put! chain-ch (-success signal async-id async-res))
                   async-res))))

           :else
           (throw #?(:clj (Exception. "Unknown channel!")
                     :cljs (js/Error. "Unknown channel!"))))))))

(defn- chain-fork
  ([chain-ch fork-fn] (chain-fork chain-ch :chain/fork fork-fn))
  ([chain-ch fork-id fork-fn]
   (go (when-let [{:keys [error] :as signal} (a/<! chain-ch)]
         (a/put! chain-ch (-update signal fork-id))
         (when-not error
           (fork-fn))
         (:result signal)))))

(defn- chain-end
  ([chain-ch handler] (chain-end chain-ch :chain/end handler))
  ([chain-ch handler-id handler]
   (go (when-let [signal (a/<! chain-ch)]
         (handler handler-id signal)))))

(defn init-chain!
  "Initialize the chain, create the channel and send the initial ChainSignal.

  For details on the arguments refer to the chain macro doc.
  "
  ([] (init-chain! :async-chain nil nil))
  ([chain-id] (init-chain! chain-id nil nil))
  ([chain-id log-fn] (init-chain! chain-id log-fn nil))
  ([chain-id log-fn tran-fn]
   (let [side-fn (or (and (fn? log-fn) (fn [v] (log-fn v) v))
                     nil)
         transducer (cond
                      (and side-fn (fn? tran-fn)) (map (comp side-fn tran-fn))
                      side-fn (map side-fn)
                      (fn? tran-fn) (map tran-fn)
                      :else nil)
         chain-ch (a/chan 1 transducer)]
     (a/put! chain-ch (map->ChainSignal {:chain-id chain-id
                                         :step-id :chain/init}))
     [chain-ch
      chain-wait
      chain-go
      chain-fork
      chain-end])))

(defn <-error
  "Extract the error field from the ChainSignal and return it.

  Could return nil if the chain terminated correctly.
  This handler is for use with the :chain/end directive, refer to the chain doc.
  "
  [_ {:keys [error]}]
  error)

(defn <-result
  "Extract the result from the ChainSignal and return it, or the error if any.

  This handler is for use with the :chain/end directive, refer to the chain doc.
  "
  [_ {:keys [error result] :as signal}]
  (or error result))
