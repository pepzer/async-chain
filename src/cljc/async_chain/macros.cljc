;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns async-chain.macros
  (:require [async-chain.core]))

(def ^:private c-init! 'async-chain.core/init-chain!)
(def ^:private c-chan? 'async-chain.core/chan?)
(def ^:private c-prefix "async-chain.macros/chain-")
(def ^:private macro-allowed? #{'let 'do '-> '->> 'cond-> 'cond->>})

#?(:clj (def ^:private a-go 'clojure.core.async/go)
   :cljs (def ^:private a-go 'cljs.core.async.macros/go))

#?(:clj (def ^:private a-close! 'clojure.core.async/close!)
   :cljs (def ^:private a-close! 'cljs.core.async/close!))

#?(:clj (def ^:private a-chan 'clojure.core.async/chan)
   :cljs (def ^:private a-chan 'cljs.core.async/chan))

#?(:clj (def ^:private a-<! 'clojure.core.async/<!)
   :cljs (def ^:private a-<! 'cljs.core.async/<!))

#?(:clj (def ^:private a-put! 'clojure.core.async/put!)
   :cljs (def ^:private a-put! 'cljs.core.async/put!))

(defmacro chain-node
  "Wraps a Node.js async method in a core.async channnel.

  Take the form with a node async method invocation and add a callback that
  returns through the channel.
  On error put a map on the channel containing the error {:chain/error error}.

  :param form
    A Node.js async call without a callback, i.e. (.read fs \"file\").

  :param transformer
    An optional method to apply to the result before returning it, i.e. js->clj.

  :return
    A core.async channel that will receive the result or a map with key
    :chain/error and the error as value.
  "
  ([form] `(chain-node ~form identity))
  ([form transformer]
   `(let [chan# (~a-chan 1)
          callback# (fn [error# value#]
                      (if error#
                        (~a-put! chan# {:chain/error error#})
                        (~a-put! chan# (~transformer value#)))
                      (~a-close! chan#))]
      (~@form callback#)
      chan#)))

(defmulti build-form (fn [[dir & _] & _]
                       dir))

(defn- build-hold-form
  "Build a form for directives that wait to obtain the previous result before
  execution, i.e. :chain/wait and :chain/fork."
  [hold-fn chan xs sym macro]
  (let [opts (butlast xs)
        function (last xs)
        inner-fn (or (and  macro (list macro sym function))
                     function)
        outer-fn (list 'fn [] inner-fn)
        all (concat opts [outer-fn])]
    (list a-<! (apply list hold-fn chan all))))

(defmethod build-form :chain/wait
  [[_ & xs] {:keys [wait]} chan _ sym macro]
  (build-hold-form wait chan xs sym macro))

(defmethod build-form :chain/fork
  [[_ & xs] {:keys [fork]} chan _ sym macro]
  (build-hold-form fork chan xs sym macro))

(defmethod build-form :chain/go
  [[_ & xs] {:keys [go]} chan go-allowed? sym macro]
  (if go-allowed?
    (let [opts (butlast xs)
          function (last xs)
          inner-fn (or (and macro (list macro sym function))
                       function)
          res-ch (gensym "go-res-ch-")
          outer-fn (list 'fn [] res-ch)
          all (concat opts [outer-fn])]
      [:chain/go
       [res-ch (list a-chan 1)]
       (list 'do
             (list a-put! res-ch inner-fn)
             (list a-close! res-ch))
       (list a-<!
             (apply list go chan all))])

    #?(:clj (throw (Exception. ":chain/go not allowed in this macro!"))
       :cljs (throw (js/Error. ":chain/go not allowed in this macro!")))))

(defmethod build-form :chain/unchain
  [[_ & xs] _ _ go-allowed? _ _]
  (if go-allowed?
    [:chain/unchain (last xs)]
    #?(:clj (throw (Exception. ":chain/unchain not allowed in this macro!"))
       :cljs (throw (js/Error. ":chain/unchain not allowed in this macro!")))))

(defmethod build-form :chain/end
  [[_ & xs] {:keys [end]} chan _ _ _]
  (list a-<! (apply list end chan xs)))

(defmethod build-form :no-seq
  [[_ form] {:keys [wait]} chan _ sym macro]
  (let [inner-fn (if macro
                   (list macro sym form)
                   form)
        outer-fn (list 'fn [] inner-fn)]
    (list a-<! (list wait chan outer-fn))))

(defmethod build-form :default
  [form {:keys [wait]} chan _ sym macro]
  (let [inner-fn (if macro
                   (list macro sym form)
                   form)
        outer-fn (list 'fn [] inner-fn)]
    (list a-<! (list wait chan outer-fn))))

(defn- pre-process
  "Substitute keyword directives with method invocations.

  Pass the chain channel as first argument.
  For :chain/go and :chain/unchain return a preliminary vector to be handled by
  build-forms-map.
  "
  ([fns chan form] (pre-process fns chan form false nil nil))
  ([fns chan form go-allowed?] (pre-process fns chan form go-allowed? nil nil))
  ([fns chan form go-allowed? sym] (pre-process fns chan form go-allowed? sym nil))
  ([fns chan form go-allowed? sym macro]
   (let [seq-form (or (and (sequential? form) form)
                      [:no-seq form])
         new-form (build-form seq-form fns chan go-allowed? sym macro)]
     (if sym
       [sym new-form]
       new-form))))

(defn- dispatch-forms
  "Return a closure to dispatch the forms to the appropriate part of the chain."
  [with-sym?]
  (fn [coll form]
    (let [sym (and with-sym? (first form))
          in-form (or (and with-sym? (second form)) form)
          tag (and (vector? in-form) (first in-form))
          out-form (and tag (drop 1 in-form))]
      (cond
        (and with-sym? (= tag :chain/unchain)) (update-in coll [:head] conj [sym (first out-form)])
        (= tag :chain/unchain) (update-in coll [:head] conj (first out-form))
        (= tag :chain/go) (cond-> coll
                            :always (update-in [:middle] conj (first out-form))
                            :always (update-in [:middle] conj ['_ (second out-form)])
                            with-sym? (update-in [:tail] conj [sym (last out-form)])
                            (not with-sym?) (update-in [:tail] conj (last out-form)))
        :else (update-in coll [:tail] conj form)))))

(defn- build-forms-map
  "Take pre-processed forms and separate them in three blocks, i.e. :head, :middle and :tail.

  :param with-sym?
    A flag to know if forms are bindings pairs like for the let macro.

  :forms forms
    Pre-processed forms.

  :return
    A map with forms assigned to sections :head, :middle and :tail."
  [with-sym? forms]
  (reduce (dispatch-forms with-sym?) {:head [] :middle [] :tail []} forms))

(defmacro chain
  "Convert let, do, ->, ->>, cond->, cond->> to work with async functions.

  Build a chain of expressions where by default each one waits the realization
  of the previous one before executing and aborts execution if receives an error.
  Expressions could be async function invocations where the return value is a
  core.async channel, like an async Node.js API call wrapped with chain-node.

  Examples:

  (defn read-file
    [filename]
     (go (str (cs/lower-case filename)
              \"\nmock file, content body.\")))

  (chain []
         (let [filename \"README.md\"
               content (read-file filename)
               lines (cs/split-lines content)]
           (println (first lines))))

  (let [chan1 (a/chan)]

    (chain []
           (-> chan1
               (str \"bar\")
               println))

    (a/put! chan1 \"foo\")
    (a/close! chan1))

  Each expression could be wrapped in a directive, which is like a function call,
  when not specified an implicit :chain/wait directive is assumed.
  The first example is equivalent to this:

  (chain []
         (let [filename (:chain/wait \"README.md\")
               content (:chain/wait (read-file filename))
               lines (:chain/wait (cs/split-lines content))]
           (println (first lines))))

  Allowed directives include:

    - :chain/go

      This tells the chain that execution could be immediate, but the result
      must be provided to the next waiting expression.

      (chain []
        (let [content1 (:chain/go (read-file \"README.md\"))
              content2 (:chain/go (read-file \"project.clj\"))
              line1 (first (cs/split-lines content1))
              line2 (first (cs/split-lines content2))
              contents (str line1 \"\n\" line2)]
          (println contents)))

      In the example above both async reading execute immediately but the
      following expression runs only when both have returned with success and
      the values are bound to the symbols content1 and content2.
      Because :chain/go expressions run immediately, local bindings cannot be
      used inside these expressions, in the previous example the expression for
      content2 cannot refer to content1, if it is necessary to refer to content1
      then :chain/wait should be used (or nothing as it is the default).
      Because execution happens first, all calls to :chain/go should be placed
      at the beginning of the chain, to avoid confusion.
      :chain/go directives are not allowed in threading macros as it would make
      no sense since each expression needs the result of the previous one.

    - :chain/fork

      This directive is useful to perform side effects but only if the previous
      operations were successful.
      Expressions wrapped with :chain/fork wait for the results of preceding
      operations and execute only if error is nil, as with :chain/wait all
      preceding bindings are available.
      The return value is the result received from the chain, that is forwarded
      without waiting (if async) the result of the fork expression.
      After this step the result carried by the chain will contain the same
      value it had before :chain/fork.
      This behaviour allows the use of :chain/fork in threading macros.

      (let [chan1 (a/chan)]

      (chain []
             (->> chan1
                  ;; Thread in the result of the implicit take.
                  (:chain/fork (prn :take-value))
                  ;; Then jump to println and thread in the same result again.
                  println))

      (a/put! chan1 \"foo\")
      (a/close! chan1))


      (chain []
             (do
               (go (reduce + (range 100)))
               ;; Invoke fork expr and jump to the next immediately (on the JVM),
               (:chain/fork (go (println :fork (reduce + (range 100000)))))
               [:foobar]))

    - :chain/end

      This directive must be the last expression in the chain and is useful to
      implement a custom handler for the chain that is *always* executed.
      The chain carries around a ChainSignal record containing :result and :error
      fields (among others), the function wrapped by :chain/end will receive two
      arguments, an id for the end expression (for logging purposes) and the
      ChainSignal record.
      The namespace shrimp-chain.core defines two handlers: <-result and <-error,
      these handlers extracts respectively the :result and :error field.

      (chain []
        (let [content (read-file \"README.md\")
              lines (cs/split-lines content)
              bad-res #?(:clj (throw (Exception. \"This is serious!\"))
                         :cljs (throw (js/Error. \"This is serious!\")))
              ;; This addition is skipped.
              total (+ 1 bad-res)
              err (:chain/end ac/<-error)]
          (if err
            (println \"Better stopping here!\")
            (println (+ total (count lines))))))

      In the chain-let macro the body is executed even if the chain was partially
      aborted because of an error, the reason is that it might be possible to
      recover from the error.
      The directive :chain/end could be used to explicitly check for an error
      like in the example above.
      The :chain/end could be used to terminate a threading macro, the handler
      function is *not* threaded and the final result of the chain will be
      whatever the handler returns:

      (chain []
             (cond->> channel
               true (str \"res: \")
               false (into [])
               true (:chain/end (fn [_ {:keys [error result]}]
                                  (if error
                                    (println :default-value)
                                    (println result)))))))

  A chain invocation is an async block that returns a core.async channel, hence
  chains allow composition:

  (chain []
         (let [content (read-file \"README.md\")
               chan-res (chain []
                               (->> (go :foo)
                                    (:chain/fork (prn :chan-res))
                                    name))
               lines (cs/split-lines content)]
           (println (str chan-res \" \" (first lines)))))

  The chain macro accepts three optional arguments as init options:

    - A chain-id, e.g. a keyword, that is bound to the :chain-id field of the
      ChainSignal record, and could be useful for logging.

    - A logging function that receives the ChainSignal record at each step of the
      chain, its return value is ignored and the ChainSignal is unchanged.
      Currently to log the last step of the chain it is necessary to add a
      :chain/end directive, e.g. (:chain/end <-result).

    - A transformer function that receives the ChainSignal at each step,
      what is passed to the next step is the result of applying transformer to
      the ChainSignal. Using this is probably a bad idea in most cases.

  All directives accept an optional step-id as first argument that is assigned
  to the :step-id field of the ChainSignal.

    (chain [:log-chain
            (fn [{:keys [chain-id step-id]}]
              (prn [chain-id step-id]))]

      (let [content1 (:chain/go :read1 (chain-node (.read fs \"file1\")))
            content2 (:chain/go :read2 (chain-node (.read fs \"file2\")))
            contents (:chain/wait :str (str content1 content2))
            err (:chain/end <-error)]
        (when-not err
          (println contents))))

    => [:log-chain :chain/init]
    => [:log-chain :read1]
    => [:log-chain :read2]
    => [:log-chain :str]
    => ...
  "
  [init-opts macro-form]
  (let [macro (first macro-form)
        chain-macro (symbol (str c-prefix (name macro)))]
    (if (macro-allowed? macro)
      (cons chain-macro
            (cons init-opts
                  (rest macro-form)))
      #?(:clj (throw (Exception. (str "Cannot apply chain to " macro)))
         :cljs (throw (js/Error. (str "Cannot apply chain to " macro)))))))

(defn- gen-symbols []
  (let [chain-ch (gensym "chain-ch-")
        wait (gensym "wait-")
        go (gensym "go-")
        fork (gensym "fork-")
        end (gensym "end-")
        return (gensym "return-")
        fns {:wait wait
             :go go
             :fork fork
             :end end}]
    [chain-ch wait
     go fork end
     return fns]))

(defmacro chain-let
  "This is the expanded form of (chain [] (let ...)) refer to chain macro doc."
  [init-opts bindings & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        new-bindings (for [[sym form] (partition 2 bindings)]
                       (if (= form :chain/channel)
                         [sym chain-ch]
                         (pre-process fns chain-ch form true sym)))
        forms-map (build-forms-map true new-bindings)]

    `(~a-go
      (let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
            ~@(apply concat (:head forms-map))
            ~@(apply concat (:middle forms-map))
            ~@(apply concat (:tail forms-map))
            ~return (do ~@forms)]
        (~a-close! ~chain-ch)
        (if (~c-chan? ~return)
          (~a-<! ~return)
          ~return)))))

(defmacro chain-do
  "This is the expanded form of (chain [] (do ...)) refer to chain macro doc."
  [init-opts & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        forms-bindings (for [form forms]
                         (pre-process fns chain-ch form true))
        forms-map (build-forms-map false forms-bindings)]
    `(~a-go
      (let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
            ~'_ (do ~@(:head forms-map))
            ~@(apply concat (:middle forms-map))
            ~return (do ~@(:tail forms-map))]
        (~a-close! ~chain-ch)
        ~return))))

(defmacro chain-->
  "This is the expanded form of (chain [] (-> ...)) refer to chain macro doc."
  [init-opts expr & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-binding (pre-process fns chain-ch expr false return)
        forms-bindings (for [form forms]
                         (pre-process fns chain-ch form false return '->))]

    `(~a-go
      (let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
            ~@expr-binding
            ~@(apply concat forms-bindings)]
        (~a-close! ~chain-ch)
        ~return))))

(defmacro chain-->>
  "This is the expanded form of (chain [] (->> ...)) refer to chain macro doc."
  [init-opts expr & forms]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-binding (pre-process fns chain-ch expr false return)
        forms-bindings (for [form forms]
                         (pre-process fns chain-ch form false return '->>))]

    `(~a-go
      (let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
            ~@expr-binding
            ~@(apply concat forms-bindings)]
        (~a-close! ~chain-ch)
        ~return))))

(defn- pred->binding [[pred [sym form]]]
  [sym (list 'or (list 'and pred form) sym)])

(defmacro chain-cond->
  "This is the expanded form of (chain [] (cond-> ...)) refer to chain macro doc."
  [init-opts expr & clauses]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-binding (pre-process fns chain-ch expr false return)
        pred-forms (for [[pred form] (partition 2 clauses)]
                     [pred (pre-process fns chain-ch form false return '->)])
        bindings (map pred->binding pred-forms)]

    `(~a-go
      (let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
            ~@expr-binding
            ~@(apply concat bindings)]
        (~a-close! ~chain-ch)
        ~return))))

(defmacro chain-cond->>
  "This is the expanded form of (chain [] (cond->> ...)) refer to chain macro doc."
  [init-opts expr & clauses]
  (let [[chain-ch wait
         go fork end
         return fns] (gen-symbols)
        expr-binding (pre-process fns chain-ch expr false return)
        pred-forms (for [[pred form] (partition 2 clauses)]
                     [pred (pre-process fns chain-ch form false return '->>)])
        bindings (map pred->binding pred-forms)]

    `(~a-go
      (let [[~chain-ch ~wait ~go ~fork ~end] (~c-init! ~@init-opts)
            ~@expr-binding
            ~@(apply concat bindings)]
        (~a-close! ~chain-ch)
        ~return))))
