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
      [jepsen [cli :as cli]
              [checker :as checker]
              [control :as c]
              [db :as db]
              [client :as client]
              [nemesis :as nemesis]
              [generator :as gen]
              [tests :as tests]]
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

           (invoke! [this test op]
                    (case (:f op)
                          :read (assoc op :type :ok, :value (parse-long (com.rabbitmq.jepsen.Utils/get conn "foo")))
                          :write (do (com.rabbitmq.jepsen.Utils/write conn "foo" (:value op))
                                     (assoc op :type, :ok))
                          :cas (let [[old new] (:value op)]
                                    (assoc op :type (if (com.rabbitmq.jepsen.Utils/cas conn "foo" old new)
                                                      :ok
                                                      :fail)))
                          ))

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
                          (c/exec binary "stop")
                          (c/exec :rm :-rf dir)
                          (c/exec :rm :-rf "/tmp/ra_kv_store")
                          )
                        )
             db/LogFiles
             (log-files [_ test node]
                        [logfile erllogfile])
             ))

(defn rakvstore-test
      "Given an options map from the command line runner (e.g. :nodes, :ssh,
      :concurrency ...), constructs a test map."
      [opts]
      (merge tests/noop-test
             opts
             {:name "rakvstore"
              :os   debian/os
              :db   (db)
              :model (model/cas-register)
              :checker (checker/compose
                         {:perf      (checker/perf)
                          :linear    (checker/linearizable)
                          :timeline  (timeline/html)})
              :client (Client. nil)
              :nemesis    (nemesis/partition-random-halves)
              :generator (->> (gen/mix [r w cas])
                              (gen/stagger 1/10)
                              (gen/nemesis
                                (gen/seq (cycle [(gen/sleep 5)
                                                 {:type :info, :f :start}
                                                 (gen/sleep 5)
                                                 {:type :info, :f :stop}]))
                                )
                              (gen/time-limit (:time-limit opts)))}))
              ;}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rakvstore-test})
            args))
