(ns jepsen.rakvstore
    (:require [clojure.tools.logging :refer :all]
      [clojure.string :as str]
      [jepsen [cli :as cli]
       [control :as c]
       [db :as db]
       [tests :as tests]]
      [jepsen.control.util :as cu]
      [jepsen.os.debian :as debian]))


(def dir "/opt/rakvstore")

(defn db
      "RA KV Store."
      []
      (reify db/DB
             (setup! [_ test node]
                     (info node "installing RA KV Store")
                     (c/su
                       (let [url (str "file:///vagrant/ra_kv_store_release-1.tar.gz")]
                            (cu/install-archive! url dir))))

             (teardown! [_ test node]
                        (info node "tearing down RA KV Store"))))

(defn rakvstore-test
      "Given an options map from the command line runner (e.g. :nodes, :ssh,
      :concurrency ...), constructs a test map."
      [opts]
      (merge tests/noop-test
             opts
             {:name "rakvstore"
              :os   debian/os
              :db   (db)}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rakvstore-test})
            args))