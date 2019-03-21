(defproject jepsen.rakvstore "0.1.0-SNAPSHOT"
  :description "Jepsen for raft-based key/value store"
  :url "https://github.com/rabbitmq/ra-kv-store/tree/master/jepsen.rakvstore"
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :javac-options     ["-target" "1.8" "-source" "1.8"]
  :jvm-opts ["-Xmx3g"]
  :license {:name "Apache 2.0 License"
            :url "https://www.apache.org/licenses/LICENSE-2.0.html"}
  :main jepsen.rakvstore
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [jepsen "0.1.12"]])
