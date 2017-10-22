Async-Chain is a [Clojure(Script)](https://clojure.org/) library of macros to chain sync/async expressions built on top of [core.async](https://github.com/clojure/core.async).

The goal is to simplify code with possibly many async calls where a test on the result is necessary after most expressions. By hiding controls behind the curtains these macros should clarify the code for this use case.

A chain is a sequence of expressions where each one receives information about the state of the previous and aborts its execution if the state reports an error.  
State information is transmitted through a core.async channel *implicitly*, this process is hidden to the user unless more control on the execution is needed.  
When applying chain to let, the expressions that participate in the chain (steps) are all those in the bindings, the chain terminates before the body and it is possible to modify its execution by using handlers at the end of the chain.  
A step of the chain could be any expression, but special handling is provided if it returns a core.async channel, the chain by default waits to take from the channel and if inside a let the symbol bound to the expression will receive the result of the take.  
With threading macros (e.g. -> cond->) it is possible to mix any expression that returns a channel with synchronous ones.  
Each step could be tweaked with directives, it can run without waiting the result of the previous step (when it makes sense) but then "join" again the chain once the result is ready, or it can wait the success of the previous step but then detach itself from the chain to execute potentially asynchronous side effects.  

There is an alternative implementation of these macros, [Shrimp-Chain](https://github.com/pepzer/shrimp-chain), that only targets [Node.js](https://nodejs.org/en/). 

## Warning

This is an early release, tests are present but not comprehensive and severe bugs might be present.
The result of a bug could be unpredictable due to the inherent complexity of intervening macros and functions.
If you decide to use this library for operations that could potentially damage your system you do it at your own risk!

## Leiningen/Clojars/Lumo

[![Clojars Project](https://img.shields.io/clojars/v/async-chain.svg)](https://clojars.org/async-chain)

If you use [Leiningen](https://github.com/technomancy/leiningen) add core.async and async-chain to the dependencies in your project.clj file.
  
```clojure
    :dependencies [... 
                   [org.clojure/core.async "0.3.443"]
                   [async-chain "0.1.0-SNAPSHOT"]]
```
    
For the use with Lumo you could download async-chain and [Andare](https://github.com/mfikes/andare) with Leiningen/Maven and then add them to Lumo:

    $ lumo -D andare:0.7.0,async-chain:0.1.0-SNAPSHOT
    
## REPL

To run a REPL with Leiningen run `lein repl`. Open repl_session.clj in your editor to evaluate the examples of this readme with cider (or similar).

For Lumo run the lumo-repl.cljsh script:
   
    $ bash lumo-repl.cljsh
    
This will start a REPL with required namespaces already loaded that will also listen on the port 12345 of the localhost for connections.  
By connecting with Emacs and inf-clojure-connect you could open repl_session.clj to evaluate the examples (skip all requires).
  
## Usage

Async-Chain provides chain versions of let, do, ->, ->>, cond-> and cond->> that work with async functions.  
To be used inside a chain an async expression must return a core.async channel.  
Node.js async calls could be wrapped with the chain-node macro to integrate with async-chain.  
The chain macro modifies the macro immediately following, an example of using the chain-let macro is:

```clojure
(require '[async-chain.core :as ac])
(require '[async-chain.macros :refer [chain]])
(require '[clojure.string :as cs])

(defn read-file 
  [filename]
  (go (str (cs/lower-case filename) 
           "\nmock file, content body.")))

(chain []
       (let [filename "README.md"
             content (read-file filename)
             lines (cs/split-lines content)]
         (println (first lines))))

;; => readme.md
```

Using the threading macro -> and a channel:

```clojure
(require '[clojure.core.async :as a :refer [go]])

(let [chan1 (a/chan)]

  (chain []
         (-> chan1
             (str "bar")
             println))

  (a/put! chan1 "foo")
  (a/close! chan1))

;; => foobar
```

These macros build a chain of expressions where by default each one waits the realization of the previous one before executing and aborts execution when an error occurs.  
Each expression could be wrapped in a directive, which is like a function call, when not specified an implicit :chain/wait directive is assumed.
The previous examples are equivalent to these verbose versions:

```clojure
(chain []
       ;; The first expression waits for the completition of the init function.
       (let [filename (:chain/wait "README.md")
             content (:chain/wait (read-file filename))
             lines (:chain/wait (cs/split-lines content))]
         (println (first lines))))


(let [chan1 (a/chan)]

  (chain []
         (-> (:chain/wait chan1)
             ;; Threading happens inside the expression, the directive form is ignored.
             (:chain/wait (str "bar"))
             (:chain/wait println)))

  (a/put! chan1 "foo")
  (a/close! chan1))
```
 
Async-Chain recognizes other directives:

### :chain/go

This tells the chain that execution could be immediate, but the result must be provided to the next waiting expression.

```clojure
(chain []
       ;; The first expression could use :chain/wait with no actual difference.
       (let [content1 (:chain/go (read-file "README.md"))
             content2 (:chain/go (read-file "project.clj"))
             line1 (first (cs/split-lines content1))
             line2 (first (cs/split-lines content2))
             contents (str line1 "\n" line2)]
         (println contents)))

;; => readme.md
;; => project.clj
```

In the example above both async read execute immediately but the following expression runs only when both have returned with success and the values are bound to the symbols content1 and content2.  
Because :chain/go expressions run immediately, local bindings cannot be used inside these expressions, in the previous example the expression for 'content2' cannot refer to 'content1', if it's necessary to refer to 'content1' then :chain/wait should be used (or nothing as it is the default).  
Because execution happens before other expressions, all calls to :chain/go should be placed at the beginning of the chain, to avoid confusion.  
:chain/go directives are *not* allowed in threading macros as it would make no sense since each expression needs the result of the previous one.

### :chain/fork

This directive is useful to perform side effects with the condition that all previous operations were successful.
Like with :chain/wait all preceding bindings are available, following the standard behaviour of the let macro.  
Errors from a fork expression are *not* handled by the chain.
The return value is the result received from the chain, that is forwarded without waiting (if async) the result of the fork expression.
After this step the result carried by the chain will contain the same value it had before :chain/fork.
This behaviour allows the use of :chain/fork in threading macros.

```clojure
(chain []
       (let [content (read-file "README.md")
             ;; Compared to an async call in a standard let, here we either know
             ;; that the content was retrieved with success or we avoid execution.
             _ (:chain/fork (go (println (str "content lenght: "
                                              (count content)))))
             lines (cs/split-lines content)]
         (println (first lines))))

;; => content lenght: 34
;; => readme.md


(let [chan1 (a/chan)]

  (chain []
         (->> chan1
              ;; Thread in the result of the implicit take from chan1.
              (:chain/fork (prn :take-value))
              ;; Then jump to println and thread in the same result again.
              println))

  (a/put! chan1 "foo")
  (a/close! chan1))

;; => :take-value "foo"
;; => foo


(go
  (prn (a/<! (chain []
                    (do (go (reduce + (range 100)))
                        ;; Possibly slow async operation inside fork.
                        ;; Jump to next expression immediately on the JVM.
                        (:chain/fork (go (println :fork (reduce + (range 100000)))))
                        [:foobar])))))

;; => [:foobar]
;; => :fork 4999950000
```

### :chain/end

This directive could be used only once as the last expression in the chain. It isn't part of the chain itself its purpose is to define a custom handler for what is returned by the chain.  
The chain carries around a ChainSignal record containing fields like :result and :error, the function wrapped by :chain/end will receive two arguments, an id for the end expression (for logging purposes) and the ChainSignal record as returned by the last step in the chain.
The namespace async-chain.core defines two simple handlers, <-result and <-error, these handlers extracts respectively the :result and :error field from the record.  
In the chain-let macro the body is executed even if the chain is partially aborted because of an error in the bindings, the reason is that it might be possible to recover from the error. The directive :chain/end could be used to explicitly check and handle an error like in the following example:


```clojure
(require '[async-chain.core :refer [<-error]])

(chain []
       (let [content (read-file "README.md") 
             lines (cs/split-lines content)
             bad-res #?(:clj (throw (Exception. "This is serious!"))
                         :cljs (throw (js/Error. "This is serious!")))
             ;; This addition is skipped.
             total (+ 1 bad-res)
             err (:chain/end <-error)]
         (if err
           (println "Better stopping here!")
           (println (+ total (count lines))))))

;; => Better stopping here!
```
          
For the chain to identify an error, the expression could:
  
 * Throw an exception/error (only if synchronous).
 * Return a map with key :chain/error bound to anything but nil and false.
 * Return an exception/error either synchronously or from a channel.

The :chain/end could also be used to terminate a threading macro, the result carried by the chain is *not* threaded in the handler function, it could be retrieved from the ChainSignal record.
The result of the entire chain is then what the :chain/end handler returns.

```clojure
(let [channel (a/chan)

      res1 (chain []
                  (-> channel
                      read-file
                      (:chain/end (fn [_ {:keys [result]}]
                                    (println "end handler returning the result")
                                    result))))]
  
  (a/put! channel "README.md")
  (a/close! channel)

  (chain []
         (cond->> res1
           false (into [])
           true (:chain/end (fn [_ {:keys [error result]}]
                              (if error
                                (println :default-value)
                                (println result)))))))

;; => end handler returning the result
;; => readme.md
;; => mock file, content body.
```

A chain invocation is an async block that returns a core.async channel, hence chains allow composition:

```clojure
(chain []
       (let [content (read-file "README.md")
             chan-res (chain []
                             (->> (go :foo)
                                  (:chain/fork (prn :chan-res))
                                  name))
             lines (cs/split-lines content)]
         (println (str chan-res " " (first lines)))))

;; => :chan-res :foo
;; => foo readme.md
```

The chain macro accepts three optional arguments as init options:

    - A chain-id, e.g. a keyword, that is bound to the :chain-id field of the ChainSignal record, and could be useful for logging.

    - A logging function that receives the ChainSignal record at each step of the chain, its return value is ignored and the ChainSignal is unchanged.  
      Currently in order to log the last step of the chain it is necessary to add a :chain/end directive, e.g. (:chain/end <-result).

    - A transformer function that receives the ChainSignal at each step, what is passed to the next step is the result of applying transformer to the ChainSignal.  
      Using this is probably a bad idea in most cases.

All directives accept an optional step-id as first argument that is assigned to the :step-id field of the ChainSignal, the id assigned to :chain/end is passed as the first argument to its handler.

```clojure
(chain [:log-chain
        (fn [{:keys [chain-id step-id]}]
          (prn [chain-id step-id]))]

       (let [content1 (:chain/go :read1 (read-file "README.md"))
             content2 (:chain/go :read2 (read-file "project.clj"))
             line1 (first (cs/split-lines content1))
             line2 (first (cs/split-lines content2))
             contents (:chain/wait :str (str line1 "\n" line2))
             err (:chain/end :ignored-here <-error)]
         (when-not err
           (println contents))))

;; => [:log-chain :chain/init]
;; => [:log-chain :read1]
;; => [:log-chain :read2]
;; => [:log-chain :chain/wait]
;; => [:log-chain :chain/wait]
;; => [:log-chain :str]
;; => readme.md
;; => project.clj
```

### ChainSignal

This record is carried by the chain and modified at each step, it contains the following fields:

    - *chain-id*, provided as the first element in the vector following the chain symbol, defaults to :async-chain.

    - *result-id*, contains the id of the step that successfully produced the most recent result, defaults to the directive of the step, i.e. :chain/wait, :chain/go, etc.

    - *result*, the most recent result returned by a step completed with success, this is not overwritten if an error occurs, could be nil.

    - *error-id*, the id of the first (and almost always the last too) step that produced an error or nil if no errors occurred.

    - *error*, the first error produced by the chain or nil if no errors occurred.

    - *step-id*, contains the id of the most recent intervening step in the chain (even if execution was aborted), defaults to the directive.

## Tests

To run the tests with Leiningen use:

    $ lein test

With Lumo:

    $ bash lumo-test.cljsh
    
## Notes

Currently compilation with `lein cljsbuild` is broken with an issue on macro expansion:

*java.lang.ClassCastException: clojure.lang.Keyword cannot be cast to clojure.lang.IObj*

It is puzzling because tests are cleared with both `lein test` and lumo, also the closely related [Shrimp-Chain](https://github.com/pepzer/shrimp-chain) seems unaffected by this issue. Any help/suggestion is welcome!

## Contacts

[Giuseppe Zerbo](https://github.com/pepzer), [giuseppe (dot) zerbo (at) gmail (dot) com](mailto:giuseppe.zerbo@gmail.com).

## License

Copyright © 2017 Giuseppe Zerbo.  
Distributed under the [Mozilla Public License, v. 2.0](http://mozilla.org/MPL/2.0/).
