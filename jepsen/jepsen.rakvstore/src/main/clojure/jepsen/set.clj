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

(ns jepsen.set
    (:require [jepsen
               [checker :as checker]
               [client :as client]
               [generator :as gen]]
      [slingshot.slingshot :refer [try+]]))


(defrecord SetClient [k conn]
           client/Client
           (open! [this test node]

                  (assoc this :conn (com.rabbitmq.jepsen.Utils/createClient node))
           )
           (setup! [this test])
           ; for some reasons, creating an empty set here makes the RA KV Store fail
           ; (step 8 of the tutorial actually initializes the set here)
           ; solution is to handle a null set in the addToSet function
           (invoke! [_ test op]
                    (try+
                      (case (:f op)
                            :read (assoc op
                                         :type :ok,
                                         :value (read-string (com.rabbitmq.jepsen.Utils/getSet conn k)))

                            :add (do (let [result (com.rabbitmq.jepsen.Utils/addToSet conn k (:value op))]
                                     (assoc op :type :ok :error (str (com.rabbitmq.jepsen.Utils/node conn) " " (.getHeaders result)))))
                            )

                      (catch com.rabbitmq.jepsen.RaTimeoutException rtex
                        (assoc op
                               :type  :info
                               :error (str :timeout " " (com.rabbitmq.jepsen.Utils/node conn) " " (.getHeaders rtex))))
                      (catch com.rabbitmq.jepsen.RaNodeDownException _
                        (assoc op
                               :type  :info
                               :error (str :nodedown " " (com.rabbitmq.jepsen.Utils/node conn))))
                      (catch java.lang.Exception _
                        (assoc op
                               :type  (if (= :read (:f op)) :fail :info)
                               :error :exception))

                      ))
           (teardown! [_ test])

           (close! [_ test])
           )

(defn workload
      "A generator, client, and checker for a set test."
      [opts]
      {:client (SetClient. "a-set" nil)
       :checker (checker/set)
       :generator (->> (range)
                       (map (fn [x] {:type :invoke, :f :add, :value x}))) 
       :final-generator (gen/once {:type :invoke, :f :read, :value nil})})
