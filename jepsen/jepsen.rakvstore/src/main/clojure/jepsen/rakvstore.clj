;; Copyright (c) 2018-2023 Broadcom. All Rights Reserved. The term Broadcom refers to Broadcom Inc. and/or its subsidiaries.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;       https://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;

(ns jepsen.rakvstore
    (:require [clojure.tools.logging :refer :all]
      [clojure.string :as str]
      [knossos.model :as model]
      [slingshot.slingshot :refer [try+]]
      [jepsen [cli :as cli]
              [checker :as checker]
              [control :as c]
              [db :as db]
              [client :as client]
              [nemesis :as nemesis]
              [independent :as independent]
              [generator :as gen]
              [util :as util]
              [tests :as tests]]
      [jepsen.set :as set]
      [jepsen.checker.timeline :as timeline]
      [jepsen.control.util :as cu]
      [jepsen.os.debian :as debian]))


(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn parse-long-nil
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (parse-long s)))

(defrecord Client [conn]
           client/Client
           (open! [this test node]
                  (assoc this :conn (com.rabbitmq.jepsen.Utils/createClient node)))

           (setup! [this test])

           (invoke! [_ test op]
                    (let [[k v] (:value op)]
                         (try+
                           (case (:f op)
                                 :read  (assoc op :type :ok, :value (independent/tuple k (parse-long-nil (com.rabbitmq.jepsen.Utils/get conn k))))
                                 :write (do (let [result (com.rabbitmq.jepsen.Utils/write conn k v)]
                                            (assoc op :type :ok :error (str (com.rabbitmq.jepsen.Utils/node conn) " " (.getHeaders result)))))
                                 :cas (let [[old new] v]
                                        (do (let [result (com.rabbitmq.jepsen.Utils/cas conn k old new)]
                                          (assoc op :type (if (.isOk result)
                                                            :ok
                                                            :fail) :error (str (com.rabbitmq.jepsen.Utils/node conn) " " (.getHeaders result)))    
                                        ))))
                           (catch com.rabbitmq.jepsen.RaTimeoutException _
                             (assoc op
                                    :type  :info
                                    :error :timeout))
                           (catch com.rabbitmq.jepsen.RaNodeDownException _
                             (assoc op
                                    :type  :info
                                    :error (str :nodedown " " (com.rabbitmq.jepsen.Utils/node conn))))
                           (catch java.lang.Exception _
                             (assoc op
                                    :type  (if (= :read (:f op)) :fail :info)
                                    :error :exception))
                           ))

                    )
           (teardown! [this test])

           (close! [_ test]))

(def dir "/opt/rakvstore")
(def log-dir "/opt/rakvstore/log")
(def status-file "/opt/rakvstore/log/status.dump")
(def configurationFile "/opt/rakvstore/releases/1/sys.config")
(def vmArgsFile "/opt/rakvstore/releases/1/vm.args")
(def env-variables "ERL_CRASH_DUMP=/opt/rakvstore/log/erl_crash.dump RUN_ERL_LOG_MAXSIZE=1000000 RUN_ERL_LOG_GENERATIONS=100")
(def binary "/opt/rakvstore/bin/ra_kv_store_release")

(defn db
      "RA KV Store."
      []
      (reify db/DB
             (setup! [_ test node]
                     (info node "installing RA KV Store")
                     (c/su
                       (c/exec :rm :-rf "/tmp/ra_kv_store")
                       (c/exec :rm :-rf dir)
                       (c/exec :mkdir :-p log-dir)
                       (cu/install-archive! (str (test :erlang-distribution-url)) dir)
                       (let [configuration (com.rabbitmq.jepsen.Utils/configuration test node)]
                            (c/exec :echo configuration :| :tee configurationFile)
                            )
                       (let [vmArgs (com.rabbitmq.jepsen.Utils/vmArgs)]
                            (c/exec :echo vmArgs :| :tee vmArgsFile)
                            )
                       (c/exec* "chmod u+x /opt/rakvstore/erts*/bin/*")
                       (info node "starting RA server" binary)
                       (c/exec* env-variables binary "daemon")
                       (Thread/sleep 5000)
                       )
                     )
             (teardown! [_ test node]
                        (info node "tearing down RA KV Store")
                        (c/su
                          (c/exec :mkdir :-p log-dir)
                          (if (not= "" (try
                                         (c/exec :pgrep :beam)
                                         (catch RuntimeException _ "")))
                            (c/exec* env-variables binary "stop")
                            (do (info node "RA KV Store already stopped")
                                ))
;                          (c/exec :rm :-rf dir)
                          )
                        )
             db/LogFiles
             (log-files [_ test node]
                        (c/su
                          (c/exec* (str "chmod o+r " log-dir "/*"))
                          (if (not= "" (try
                                         (c/exec :pgrep :beam)
                                         (catch RuntimeException _ "")))
                            (do
                              (let [
                                    ra-node-id (com.rabbitmq.jepsen.Utils/raNodeId node -1)
                                    erlang-eval-status (str "eval \"sys:get_status(" ra-node-id ").\"")
                                    erlang-eval-counters (str "eval \"ra_counters:overview().\"")
                                    ]
                                   (c/exec* binary erlang-eval-status ">>" status-file)
                                   (c/exec* binary erlang-eval-counters ">>" status-file)
                                   ))
                            (do (info node "RA KV Store stopped, cannot get status")
                                ))
                          )
                        (conj (jepsen.control.util/ls-full log-dir))
                        )))

(defn register-workload
      "Tests linearizable reads, writes, and compare-and-set operations on
      independent keys."
      [opts]
      {:client    (Client. nil)
       :checker   (independent/checker
                    (checker/compose
                      {:linear   (checker/linearizable {:model (model/cas-register)})
                       :timeline (timeline/html)}))
       :generator (independent/concurrent-generator
                    10
                    (repeatedly #(rand-int 75))
                    (fn [k]
                        (->> (gen/mix [r w cas])
                             (gen/limit (:ops-per-key opts)))))})

(defn start-erlang-vm!
      "Start RA KV Store on node."
      [test node]
      (c/su
        (if (not= "" (try
                       (c/exec :pgrep :beam)
                       (catch RuntimeException _ "")))
          (info node "RA KV Store already running.")
          (do (info node "Starting RA KV Store...")
              (c/exec* env-variables binary "start -ra_kv_store restart_ra_cluster 'true'")
              (info node "RA KV Store started"))))
      :started)

(defn kill-erlang-vm!
      "Kills RA KV store on node."
      [test node]
      (util/meh (c/su (c/exec* "killall -9 beam.smp")))
      (info node "RA KV Store killed.")
      :killed)

(defn start-erlang-process!
      "Start for Erlang process killer (no-op)."
      [test node]
      (info node "Called start of Erlang process killer (no-op)")
      :started)

(defn kill-erlang-process!
      "Kills a random RA Erlang process"
      [test node]
      (let [
            ; FIXME looks like the ra_log_segment_writer isn't killed (doesn't show up in the logs)
            ;erlangProcess (rand-nth (list "ra_log_wal" "ra_log_snapshot_writer" "ra_log_segment_writer"))
            erlangProcess (rand-nth (list "ra_log_wal" "ra_log_segment_writer"))
            erlangEval (str "eval 'exit(whereis(" erlangProcess "), kill).'")
            ]
           (c/su
             (info node "Killing" erlangProcess "Erlang process")
             (c/exec* binary erlangEval)
             )

           (info node erlangProcess "Erlang process killed" erlangEval))
      :killed)

(defn kill-erlang-vm-nemesis
  "A nemesis that kills the Erlang VM on (a) random node(s)"
  [n]
  (nemesis/node-start-stopper
    (fn [nodes] ((comp (partial take n) shuffle) nodes))
    kill-erlang-vm!
    start-erlang-vm!)
  )

(defn kill-erlang-process-nemesis
  "A nemesis that kills a random RA log process on (a) random node(s)"
  [n]
  (nemesis/node-start-stopper
    (fn [nodes] ((comp (partial take n) shuffle) nodes))
    kill-erlang-process!
    start-erlang-process!)
  )


(def nemesises
  "A map of nemesis names."
  {"kill-erlang-vm"            ""
   "kill-erlang-process"       ""
   "random-partition-halves"   ""
   "partition-halves"          ""
   "partition-majorities-ring" ""
   "partition-random-node"     ""
   "combined"                  ""
   })

(def network-partition-nemesises
  "A map of network partition nemesis names"
  {"random-partition-halves"   ""
   "partition-halves"          ""
   "partition-majorities-ring" ""
   "partition-random-node"     ""
   })

(defn init-network-partition-nemesis
      "Returns appropriate network partition nemesis"
      [opts]
      (case (:network-partition-nemesis opts)
            "random-partition-halves"   (nemesis/partition-random-halves)
            "partition-halves"          (nemesis/partition-halves)
            "partition-majorities-ring" (nemesis/partition-majorities-ring)
            "partition-random-node"     (nemesis/partition-random-node)
            )
      )

(defn init-nemesis
      "Returns appropriate nemesis"
      [opts]
      (case (:nemesis opts)
            "kill-erlang-vm"            (kill-erlang-vm-nemesis (:random-nodes opts))
            "kill-erlang-process"       (kill-erlang-process-nemesis (:random-nodes opts))
            "random-partition-halves"   (nemesis/partition-random-halves)
            "partition-halves"          (nemesis/partition-halves)
            "partition-majorities-ring" (nemesis/partition-majorities-ring)
            "partition-random-node"     (nemesis/partition-random-node)
            "combined"                  (nemesis/compose {{:split-start :start
                                                           :split-stop  :stop} (init-network-partition-nemesis opts)
                                                          {:kill-erlang-vm-start  :start
                                                           :kill-erlang-vm-stop  :stop} (kill-erlang-vm-nemesis (:random-nodes opts))
                                                          {:kill-erlang-process-start  :start
                                                           :kill-erlang-process-stop  :stop} (kill-erlang-process-nemesis (:random-nodes opts))
                                                          }
                                                           )
            )
      )

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"set"      set/workload
   "register" register-workload})

(defn combined-nemesis-generator
      "Nemesis generator that triggers Erlang VM/process killing during network partition."
      [opts]
      {
       :generator (cycle [
                          (gen/sleep (:time-before-disruption opts))
                          {:type :info :f :split-start}
                          (gen/stagger (/ (:disruption-duration opts) 8)
                                       (gen/mix
                                         [
                                          {:type :info, :f :kill-erlang-vm-start}
                                          {:type :info, :f :kill-erlang-process-start}
                                          ]
                                         ))
                          (gen/stagger (/ (:disruption-duration opts) 6)
                                       (gen/phases {:type :info :f :kill-erlang-vm-stop}
                                                   {:type :info :f :kill-erlang-process-stop}))
                          (gen/sleep (:disruption-duration opts))
                          {:type :info :f :split-stop}
                          ])
       :stop-generator [(gen/once {:type :info, :f :kill-erlang-vm-stop})
                        (gen/nemesis (gen/once {:type :info, :f :kill-erlang-process-stop}))
                        (gen/nemesis (gen/once {:type :info, :f :split-stop}))]

       })

(defn single-nemesis-generator
      "Nemesis with single disruption."
      [opts]
      {
       :generator (cycle [
                         (gen/sleep (:time-before-disruption opts))
                         {:type :info :f :start}
                         (gen/sleep (:disruption-duration opts))
                         {:type :info :f :stop}
                         ])
       :stop-generator (gen/once {:type :info, :f :stop})
       })

(def nemesis-generators
  "Map of nemesis types to nemesis generator."
  {"combined"                   combined-nemesis-generator
   "kill-erlang-vm"             single-nemesis-generator
   "kill-erlang-process"        single-nemesis-generator
   "random-partition-halves"    single-nemesis-generator
   "partition-halves"           single-nemesis-generator
   "partition-majorities-ring"  single-nemesis-generator
   "partition-random-node"      single-nemesis-generator
   })

(def cli-opts
  "Additional command line options."
  [["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--ops-per-key NUM" "Maximum number of operations on any given key."
    :default  100
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   ["-w" "--workload NAME" "What workload should we run?"
    :missing  (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]
   [nil "--nemesis NAME" "What nemesis should we use?"
    :missing  (str "--nemesis " (cli/one-of nemesises))
    :validate [nemesises (cli/one-of nemesises)]]
   [nil "--network-partition-nemesis NAME" "What network partition nemesis should we use (only for combined nemesis)? Default is random-partition-halves"
    :default  "random-partition-halves"
    :missing  (str "--network-partition-nemesis " (cli/one-of network-partition-nemesises))
    :validate [network-partition-nemesises (cli/one-of network-partition-nemesises)]]
   [nil "--random-nodes NUM" "Number of nodes disrupted by Erlang VM and Erlang process killing nemesises"
    :default  1
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--erlang-net-ticktime NUM" "Erlang net tick time in seconds (https://www.rabbitmq.com/nettick.html)."
    :default  -1
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--disruption-duration NUM" "Duration of disruption (in seconds)"
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--time-before-disruption NUM" "Time before the nemesis kicks in (in seconds)"
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--release-cursor-every NUM" "Release RA cursor every n operations."
    :default  -1
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--wal-max-size-bytes NUM" "Maximum size of RA Write Ahead Log, default is 134217728 (128 MB)."
    :default  134217728
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--erlang-distribution-url URL" "URL to retrieve the Erlang distribution archive"
    :default "file:///root/jepsen.rakvstore/ra_kv_store_release-1.tar.gz"
    :parse-fn read-string]
   ])



(defn rakvstore-test
      "Given an options map from the command line runner (e.g. :nodes, :ssh,
      :concurrency ...), constructs a test map. Special options:

        :rate                 Approximate number of requests per second, per thread.
        :ops-per-key          Maximum number of operations allowed on any given key.
        :workload             Type of workload.
        :nemesis              Type of nemesis.
        :erlang-net-ticktime  Erlang net tick time.
        :release-cursor-every Release RA cursor every n operations."
      [opts]
      (let [
            workload  ((get workloads (:workload opts)) opts)
            nemesis (init-nemesis opts)
            nemesis-generator ((get nemesis-generators (:nemesis opts)) opts)
            ]
      (merge tests/noop-test
             opts
             {:pure-generators true
              :name (str (name (:workload opts)))
              :os   debian/os
              :db   (db)
              :checker    (checker/compose
                            {:perf     (checker/perf)
                             :workload (:checker workload)})
              :client     (:client workload)
              :nemesis nemesis
              :generator (gen/phases
                           (->> (:generator workload)
                                (gen/stagger (/ (:rate opts)))
                                (gen/nemesis
                                  (:generator nemesis-generator)
                                  )
                                (gen/time-limit (:time-limit opts))
                                )
                           (gen/log "Healing cluster")
                           (gen/nemesis (:stop-generator nemesis-generator))
                           (gen/log "Waiting for recovery")
                           (gen/sleep 10)
                           (gen/clients (:final-generator workload)))}
             )))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rakvstore-test,
                                  :opt-spec cli-opts})
            args))

