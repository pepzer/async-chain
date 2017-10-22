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


(require '[clojure.core.async :as a :refer [go]])

(let [chan1 (a/chan)]

  (chain []
         (-> chan1
             (str "bar")
             println))

  (a/put! chan1 "foo")
  (a/close! chan1))

;; => foobar


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
              ;; Thread in the result of the implicit take.
              (:chain/fork (prn :take-value))
              ;; Then jump to println and thread in the result of the take again.
              println))

  (a/put! chan1 "foo")
  (a/close! chan1))

;; => :take-value "foo"
;; => foo


(go
  (prn (a/<! (chain []
                    (do (go (reduce + (range 100)))
                        ;; Possibly slow async operation, go to next expression immediately (on the JVM),
                        ;; Ignore possible errors for this call.
                        (:chain/fork (go (println :fork (reduce + (range 100000)))))
                        [:foobar])))))

;; => [:foobar]
;; => :fork 4999950000


(chain []
       (let [content (read-file "README.md") 
             lines (cs/split-lines content)
             bad-res #?(:clj (throw (Exception. "This is serious!"))
                        :cljs (throw (js/Error. "This is serious!")))
             ;; This addition is skipped.
             total (+ 1 bad-res)
             err (:chain/end ac/<-error)]
         (if err
           (println "Better stopping here!")
           (println (+ total (count lines))))))

;; => Better stopping here!


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


(chain [:log-chain
        (fn [{:keys [chain-id step-id]}]
          (prn [chain-id step-id]))]

       (let [content1 (:chain/go :read1 (read-file "README.md"))
             content2 (:chain/go :read2 (read-file "project.clj"))
             line1 (first (cs/split-lines content1))
             line2 (first (cs/split-lines content2))
             contents (:chain/wait :str (str line1 "\n" line2))
             err (:chain/end :ignored-id ac/<-error)]
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
