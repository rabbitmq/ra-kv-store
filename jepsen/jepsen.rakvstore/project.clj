(defproject jepsen.rakvstore "0.1.0-SNAPSHOT"
  :description "Jepsen for raft-based key/value store"
  :url "https://github.com/rabbitmq/ra-kv-store/tree/master/jepsen.rakvstore"
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :jvm-opts ["-Xmx12g"]
  :license {:name "Apache 2.0 License"
            :url "https://www.apache.org/licenses/LICENSE-2.0.html"}
  :main jepsen.rakvstore
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [jepsen "0.3.7"]]
  :exclusions [org.slf4j/log4j-over-slf4j
               log4j/log4j]

)
