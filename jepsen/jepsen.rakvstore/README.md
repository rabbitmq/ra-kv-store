# Jepsen Test for Raft-based Key/Value Store

## Usage (Docker)

In the **root directory**, package the Erlang application and copy it in the Jepsen test directory:

```
ra-kv-store $ make rel-jepsen
...
cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/
```

`cd` into the `jepsen` directory and set the `JEPSEN_ROOT` environment variable:
```
ra-kv-store $ cd jepsen
jepsen $ export JEPSEN_ROOT=$(pwd)
```

`cd` into the `docker` directory:
```
jepsen $ cd docker
```

Start the containers:

```
docker $ ./up.sh --dev
```

Connect to the Jepsen control container:

```
docker $ docker exec -it jepsen-control bash
```

Inside the control container, `cd` into the RA KV Store test directory and launch the test:

```
$ cd jepsen.rakvstore
$ lein run test --time-limit 15
```

## Usage (Vagrant)

In the **root directory**, package the Erlang application and copy it in the Jepsen test directory:

```
ra-kv-store $ make rel-jepsen
...
cp _rel/ra_kv_store_release/*.tar.gz /jepsen/jepsen.rakvstore/
```

`cd` into the `jepsen/jepsen.rakvstore` directory:
```
ra-kv-store $ cd jepsen/jepsen.rakvstore
```

We are going to use 3 virtual machines created by Vagrant. You need to reference those
VMs in your `/etc/hosts` file (be careful to use tabs between the IP and the hostname):

```
192.168.33.10   n1
192.168.33.11   n2
192.168.33.12   n3
```

Start up the VMs (this may take a few minutes, especially the first time):

```
jepsen.rakvstore $ vagrant up
```

Make sure the appropriate release file will be used by updating the `src/clojure/jepsen/rakvstore.clj` file:

```
;(def releasefile "file:///jepsen/jepsen.rakvstore/ra_kv_store_release-1.tar.gz") // commented out
(def releasefile "file:///vagrant/ra_kv_store_release-1.tar.gz")
```

You can now start the Jepsen test:

```
jepsen.rakvstore $ lein run test --node n1 --node n2 --node n3 \
  --username vagrant --password vagrant --ssh-private-key $(pwd)/.vagrant/machines/n1/virtualbox/private_key \
  --test-count 1 --time-limit 15
```

## Client

Jepsen tests access the KV through a Java-based client. This client handles HTTP connections, requests,
and responses, as well appropriate logic for the tests (error handling, value swapping). The client is
compiled as part as of the Jepsen. The client and its corresponding unit tests can be modified and the
changes can be checked by running Maven:

```
./mvnw clean test
```

## License

RA KV Store is [Apache 2.0 licensed](http://www.apache.org/licenses/LICENSE-2.0.html).

_Sponsored by [Pivotal](http://pivotal.io)_
