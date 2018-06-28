;; Copyright (c) 2018 Pivotal Software Inc, All Rights Reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;       http://www.apache.org/licenses/LICENSE-2.0
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

(defn parse-long
      "Parses a string to a Long. Passes through `nil`."
      [s]
      (when s (Long/parseLong s)))

(defrecord Client [conn]
           client/Client
           (open! [this test node]
                  (assoc this :conn (com.rabbitmq.jepsen.Utils/createClient node)))

           (setup! [this test])

           (invoke! [_ test op]
                    (let [[k v] (:value op)]
                         (try+
                           (case (:f op)
                                 :read  (assoc op :type :ok, :value (independent/tuple k (parse-long (com.rabbitmq.jepsen.Utils/get conn k))))
                                 :write (do (com.rabbitmq.jepsen.Utils/write conn k v)
                                            (assoc op :type, :ok))
                                 :cas (let [[old new] v]
                                           (assoc op :type (if (com.rabbitmq.jepsen.Utils/cas conn k old new)
                                                             :ok
                                                             :fail)))
                                 )
                           (catch java.io.IOException _
                             (assoc op
                                    :type  (if (= :read (:f op)) :fail :info)
                                    :error :ioexception))
                           ))

                    )
           (teardown! [this test])

           (close! [_ test]))


(def releasefile "file:///jepsen/jepsen.rakvstore/ra_kv_store_release-1.tar.gz")
;(def releasefile "file:///vagrant/ra_kv_store_release-1.tar.gz")
(def dir "/opt/rakvstore")
(def logDir "/opt/rakvstore/log")
(def configurationFile "/opt/rakvstore/releases/1/sys.config")
(def vmArgsFile "/opt/rakvstore/releases/1/vm.args")
(def binary "/opt/rakvstore/bin/ra_kv_store_release")
(def logfile "/opt/rakvstore/log/erlang.log.1")
(def erllogfile "/opt/rakvstore/log/run_erl.log")

(defn db
      "RA KV Store."
      []
      (reify db/DB
             (setup! [_ test node]
                     (info node "installing RA KV Store")
                     (c/su
                       (c/exec :rm :-rf "/tmp/ra_kv_store")
                       (let [url releasefile]
                            (cu/install-archive! url dir))
                       (let [configuration (com.rabbitmq.jepsen.Utils/configuration test node)]
                            (c/exec :echo configuration :| :tee configurationFile)
                            )
                       (let [vmArgs (com.rabbitmq.jepsen.Utils/vmArgs)]
                            (c/exec :echo vmArgs :| :tee vmArgsFile)
                            )
                       (c/exec :mkdir logDir)
                       (c/exec binary "start")
                       (Thread/sleep 2000)
                       )
                     )
             (teardown! [_ test node]
                        (info node "tearing down RA KV Store")
                        (c/su
                          (if (not= "" (try
                                         (c/exec :pgrep :beam)
                                         (catch RuntimeException e "")))
                            (c/exec binary "stop")
                            (do (info node "RA KV Store already stopped")
                                ))
                          (c/exec :rm :-rf dir)
                          )
                        )
             db/LogFiles
             (log-files [_ test node]
                        [logfile erllogfile])
             ))

(defn register-workload
      "Tests linearizable reads, writes, and compare-and-set operations on
      independent keys."
      [opts]
      {:client    (Client. nil)
       :checker   (independent/checker
                    (checker/compose
                      {:linear   (checker/linearizable)
                       :timeline (timeline/html)}))
       :generator (independent/concurrent-generator
                    10
                    (range)
                    (fn [k]
                        (->> (gen/mix [r w cas])
                             (gen/limit (:ops-per-key opts)))))})

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"set"      set/workload
   "register" register-workload})

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
   [nil "--erlang-net-ticktime NUM" "Erlang net tick time in seconds (https://www.rabbitmq.com/nettick.html)."
    :default  -1
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--partition-duration NUM" "Partition duration in seconds."
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--working-network-duration NUM" "Working (no partition) network duration in seconds."
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--release-cursor-every NUM" "Release RA cursor every n operations."
    :default  -1
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   ])


(defn start!
      "Start RA KV Store on node."
      [test node]
      (c/su
        (if (not= "" (try
                       (c/exec :pgrep :beam)
                       (catch RuntimeException _ "")))
          (info node "RA KV Store already running.")
          (do (info node "Starting RA KV Store...")
              (c/exec binary "start")
              (info node "RA KV Store started"))))
      :started)

(defn kill!
      "Kills RA KV store on node."
      [test node]
      (util/meh (c/su (c/exec :killall :-9 :erts)))
      (info node "RA KV Store killed.")
      :killed)

(defn start-kill-erlang-process!
      "Start for Erlang process killer (no-op)."
      [test node]
      (info node "Called start of Erlang process killer (no-op)")
      :started)

(defn kill-erlang-process!
      "Kills a random RA Erlang process"
      [test node]
      (let [
            ; FIXME looks loke the ra_log_segment_writer isn't killed (doesn't show up in the logs)
            erlangProcess (rand-nth (list "ra_log_wal" "ra_log_snapshot_writer" "ra_log_segment_writer"))
            erlangEval (str "eval 'exit(whereis(" erlangProcess "), killed_by_jepsen).'")
            ]
         (c/su
           (info node "Killing" erlangProcess "Erlang process")
           (c/exec* binary erlangEval)
           )

        (info node erlangProcess "Erlang process killed" erlangEval))
      :killed)

(defn rakvstore-test
      "Given an options map from the command line runner (e.g. :nodes, :ssh,
      :concurrency ...), constructs a test map. Special options:

        :rate                 Approximate number of requests per second, per thread.
        :ops-per-key          Maximum number of operations allowed on any given key.
        :workload             Type of workload.
        :erlang-net-ticktime  Erlang net tick time.
        :release-cursor-every Release RA cursor every n operations."
      [opts]
      (let [
            workload  ((get workloads (:workload opts)) opts)
            randomNode (rand-nth (:nodes opts))

      ]
      (merge tests/noop-test
             opts
             {:name (str (name (:workload opts)))
              :os   debian/os
              :db   (db)
              :model (model/cas-register)
              :checker    (checker/compose
                            {:perf     (checker/perf)
                             :workload (:checker workload)})
              :client     (:client workload)
              :nemesis (nemesis/compose {
                                         {:split-start :start
                                          :split-stop  :stop} (nemesis/partition-random-halves)
                                         {:kill-start  :start
                                          :kill-stop   :stop} (nemesis/node-start-stopper (fn [_] randomNode) start! kill!)
                                         {:kill-erlang-process-start  :start
                                          :kill-erlang-process-stop   :stop} (nemesis/node-start-stopper (fn [_] randomNode) start-kill-erlang-process! kill-erlang-process!)
                                         })
              :generator (gen/phases
                           (->> (:generator workload)
                                (gen/stagger (/ (:rate opts)))
                                (gen/time-limit (:time-limit opts))
                                (gen/nemesis
                                  (gen/seq  (cycle [
                                                    {:type :info :f :kill-erlang-process-start}
                                                   (gen/sleep (:working-network-duration opts))
                                                    {:type :info :f :kill-erlang-process-stop}
                                                   {:type :info, :f :split-start}
                                                   (gen/sleep (:partition-duration opts))
                                                   {:type :info, :f :split-stop}

                                                   ])
                                            )
                                  )
                                (gen/time-limit (:time-limit opts))
                                )
                           (gen/log "Healing cluster")
                           (gen/nemesis (gen/once {:type :info, :f :split-stop}))
                           (gen/nemesis (gen/once {:type :info, :f :kill-start}))
                           (gen/log "Waiting for recovery")
                           (gen/sleep 10)
                           (gen/clients (:final-generator workload)))}
             )))
              ;}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rakvstore-test,
                                  :opt-spec cli-opts})
            args))

