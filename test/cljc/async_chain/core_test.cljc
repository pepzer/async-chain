;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns async-chain.core-test
  (:require #?@(:clj [[clojure.core.async :as a :refer [go]]
                      [async-chain.core :as ac]
                      [async-chain.macros :refer [chain]]
                      [clojure.test :refer [deftest run-tests is testing]]]
                :cljs [[cljs.core.async :as a]
                       [async-chain.core :as ac]
                       [cljs.test :refer [deftest run-tests is testing]]])
            [clojure.string :as cs])
  #?(:cljs (:use-macros [cljs.core.async.macros :only [go]]
                        [async-chain.macros :only [chain]])))

(defn- echo-async [s]
  (go (str s)))

(defn- echo-sync [s]
  (str s))

(defn- error-async []
  (go
    #?(:clj (Exception. "mock exception")
       :cljs (js/Error. "mock error"))))

(deftest test-let
  (testing "chain-let"
    (let [cmd "ls"
          ls (echo-sync cmd)
          lines (cs/split-lines ls)
          result (first lines)
          c-res (chain []
                       (let [c-ls (echo-async cmd)
                             c-lines (cs/split-lines c-ls)
                             c-result (first c-lines)]
                         c-result))]
      #?(:clj (is (= result (a/<!! c-res)) "chain-let res")
         :cljs (go (is (= result (a/<! c-res)) "chain-let res"))))))

(deftest test-do
  (testing "chain-do"
    (let [cmd "ls"
          result (do :foo
                     nil
                     (echo-sync cmd))
          c-result (chain []
                          (do :foo
                              nil
                              (echo-async cmd)))]
      #?(:clj (is (= result (a/<!! c-result)) "chain-do res")
         :cljs (go (is (= result (a/<! c-result)) "chain-do res"))))))

(deftest test-->
  (testing "chain-->"
    (let [msg "foobar"
          result (-> msg
                     echo-sync
                     keyword)
          c-result (chain []
                          (-> msg
                              echo-async
                              keyword))
          msg2 "foobar2"
          result2 (-> msg2
                      (str "bar")
                      (echo-sync)
                      (keyword))
          c-result2 (chain []
                           (-> msg2
                               (str "bar")
                               (echo-async)
                               (keyword)))]
      #?@(:clj [(is (= result (a/<!! c-result)) "chain--> res #1")
                (is (= result2 (a/<!! c-result2)) "chain--> res #2")]
          :cljs [(go (is (= result (a/<! c-result)) "chain--> res #1"))
                 (go (is (= result2 (a/<! c-result2)) "chain--> res #2"))]))))

(deftest test-->>
  (testing "chain-->>"
    (let [msg "foobar"
          result (->> msg
                      (str "echo ")
                      (echo-sync)
                      vec
                      (map cs/upper-case)
                      (map keyword))
          c-result (chain []
                          (->> msg
                               (str "echo ")
                               echo-async
                               vec
                               (map cs/upper-case)
                               (map keyword)))]
      #?(:clj (is (= result (a/<!! c-result)) "chain-->> res")
         :cljs (go (is (= result (a/<! c-result)) "chain-->> res"))))))

(deftest test-cond->
  (testing "chain-cond->"
    (let [expr (range 10)
          result (cond-> expr
                   :always (list 'echo)
                   :always reverse
                   (vector? expr) #?(:clj (and (throw (Exception. "test-cond->")))
                                     :cljs (and (throw (js/Error. "test-cond->"))))
                   :always pr-str
                   :always (cs/replace #"\(|\)" "")
                   :always echo-sync)

          c-result (chain []
                          (cond-> expr
                            :always (list 'echo)
                            :always reverse
                            (vector? expr) #?(:clj (and (throw (Exception. "test-cond->")))
                                              :cljs (and (throw (js/Error. "test-cond->"))))
                            :always pr-str
                            :always (cs/replace #"\(|\)" "")
                            :always echo-async))]
      #?(:clj (is (= result (a/<!! c-result)) "chain-cond-> res")
         :cljs (go (is (= result (a/<! c-result)) "chain-cond-> res"))))))

(deftest test-cond->>
  (testing "chain-cond->>"
    (let [expr (range 10)
          result (cond->> expr
                   :always (take 5)
                   :always (cons "echo ")
                   nil reverse
                   :always (apply str)
                   (vector? expr) #?(:clj (and (throw (Exception. "test-cond->")))
                                     :cljs (and (throw (js/Error. "test-cond->>"))))
                   true echo-sync)
          c-result (chain []
                          (cond->> expr
                            :always (take 5)
                            :always (cons "echo ")
                            nil reverse
                            :always (apply str)
                            (vector? expr) #?(:clj (and (throw (Exception. "test-cond->")))
                                              :cljs (and (throw (js/Error. "test-cond->>"))))
                            true echo-sync))]
      #?(:clj (is (= result (a/<!! c-result)) "chain-cond->> res")
         :cljs (go (is (= result (a/<! c-result)) "chain-cond->> res"))))))

(deftest test-composition
  (testing "composition"
    (let [cmd1 "foobar"
          cmd2 "ls"
          cmd3 "foo2"
          result (let [res1 (echo-sync cmd1)
                       res2 (echo-sync cmd2)
                       res3 (->> res1
                                 (str cmd3)
                                 echo-sync)]
                   (cond->> res1
                     (> (count res2)
                        (count res3)) cs/upper-case
                     (< (count res1)
                        (count res3)) (str cmd3 cmd2)
                     :always echo-sync))
          c-result (chain []
                          (let [res1 (echo-async cmd1)
                                res2 (echo-async cmd2)
                                res3 (chain []
                                            (->> res1
                                                 (str cmd3)
                                                 echo-async))]
                            (chain []
                                   (cond->> res1
                                     (> (count res2)
                                        (count res3)) cs/upper-case
                                     (< (count res1)
                                        (count res3)) (str cmd3 cmd2)
                                     :always echo-async))))]
      #?(:clj (is (= result (a/<!! c-result)) "chain-cond->> res")
         :cljs (go (is (= result (a/<! c-result)) "chain-cond->> res"))))))

(deftest test-execution-log
  (let [cmd1 "foobar"
        cmd2 "ls"
        cmd3 "foo2"
        result (let [res1 (echo-sync cmd1)
                     res2 (echo-sync cmd2)
                     res3 (->> res1
                               (str cmd3)
                               echo-sync)]
                 (cond->> res1
                   (> (count res2)
                      (count res3)) cs/upper-case
                   (< (count res1)
                      (count res3)) (str cmd3 cmd2)
                   :always echo-sync
                   :always (list res1 res3)))
        exec-log (atom [])
        exec-target [:chain/init :exec-cmd2 :exec-cmd1
                     :cmd1 :cmd2 :fork :exec-fork
                     "foo2foobar" :->>]
        signal (atom nil)
        signal-target {:chain-id :let
                       :result-id :->>
                       :result "foo2foobar"
                       :error-id nil
                       :error nil
                       :step-id :->>}
        c-result (chain [:let #(swap! exec-log conj
                                      (:step-id %))]
                        (let [res1 (:chain/wait :cmd1 (do (swap! exec-log
                                                                 conj :exec-cmd1)
                                                          (echo-async cmd1)))
                              res2 (:chain/go :cmd2 (do (swap! exec-log
                                                               conj :exec-cmd2)
                                                        (echo-async cmd2)))
                              _ (:chain/fork :fork (do (echo-async "side-effect")
                                                       (swap! exec-log
                                                              conj :exec-fork)))
                              res3 (:chain/wait :->>
                                                (chain []
                                                       (->> res1
                                                            (str cmd3)
                                                            echo-async
                                                            (:chain/end
                                                             (fn [_ {:keys [result]}]
                                                               (swap! exec-log
                                                                      conj result)
                                                               result)))))
                              signal-let (:chain/end (fn [_ s] s))]
                          (reset! signal signal-let)
                          (chain []
                                 (cond->> res1
                                   (> (count res2)
                                      (count res3)) cs/upper-case
                                   (< (count res1)
                                      (count res3)) (str cmd3 cmd2)
                                   :always echo-async
                                   :always (list res1 res3)))))]

    #?(:clj (when-let [a-result (a/<!! c-result)]
              (is (= signal-target (into {} @signal))
                  "test-log chain signal")
              (is (= exec-target @exec-log)
                  "test-log execution log")
              (is (= result a-result)
                  "test-log result"))

       :cljs (go (when-let [a-result (a/<! c-result)]
                   (is (= signal-target (into {} @signal))
                       "test-log chain signal")
                   (is (= exec-target @exec-log)
                       "test-log execution log")
                   (is (= result a-result)
                       "test-log result"))))))

(deftest test-abort-chain
  (let [cmd1 "foobar"
        cmd2 "ls"
        exec-log (atom [])
        exec-target [:chain/init :exec-exit1 :exec-cmd1 :cmd1 :exit1 :fork]
        signal-target {:chain-id :let
                       :result-id :cmd1
                       :result "foobar"
                       :error-id :exit1
                       :step-id :fork}
        c-result (chain [:let #(swap! exec-log conj
                                      (:step-id %))]
                        (let [res1 (:chain/wait :cmd1 (do (swap! exec-log
                                                                 conj :exec-cmd1)
                                                          (echo-async cmd1)))
                              res2 (:chain/go :exit1 (do (swap! exec-log
                                                                conj :exec-exit1)
                                                         (error-async)))
                              _ (:chain/fork :fork (do (echo-async cmd2)
                                                       (swap! exec-log
                                                              conj :exec-fork)))
                              signal (:chain/end (fn [_ s] s))]
                          signal))]

    #?(:clj (let [a-result (a/<!! c-result)]
              (is (= exec-target @exec-log)
                  "test-abort execution log")
              (is (= signal-target (dissoc a-result :error))
                  "test-abort chain signal"))

       :cljs (go (let [a-result (a/<! c-result)]
                   (is (= exec-target @exec-log)
                       "test-abort execution log")
                   (is (= signal-target (dissoc a-result :error))
                       "test-abort chain signal"))))))
