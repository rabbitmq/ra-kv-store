# Jepsen Test for Raft-based Key/Value Store

This work is based on [Jepsen tutorial](https://github.com/jepsen-io/jepsen/blob/master/doc/tutorial/index.md).

## Building the Erlang release

You must be in the project **root directory** to build the Erlang release. The Erlang
release needs to be built on Linux-based system, as this is where it executes in Jepsen tests.

### Package locally if you run Linux

```
ra-kv-store $ make rel-jepsen-local
...
cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/
```

### Package with Docker if you run macOS

```
ra-kv-store $ make rel-jepsen
...
cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/
```

Right now the Docker packaging uses Erlang 19. Use the Vagrant packaging to use
a more recent version of Erlang.

### Package with Vagrant if you run macOS

```
ra-kv-store $ vagrant up
ra-kv-store $ vagrant ssh
```

Then inside the VM:

```
~ $ cd /vagrant/
vagrant $ make rel-jepsen-local
...
cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/
```

## Running Jepsen tests with Docker

**Make sure the Erlang release file `ra_kv_store_release-1.tar.gz` is in the
`jepsen/jepsen.rakvstore/` directory.**

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

Inside the control container, `cd` into the RA KV Store test directory and launch a test:

```
$ cd jepsen.rakvstore
$ lein run test --time-limit 15 --concurrency 10 --rate 1 --workload set --nemesis random-partition-halves
```

The execution should finish with something like:

```
INFO [2018-06-25 08:45:23,157] jepsen test runner - jepsen.core {:ok-count 83,
 :valid? true,
 :lost-count 0,
 :lost "#{}",
 :acknowledged-count 83,
 :recovered "#{}",
 :ok "#{0..43 48 52..62 64..79 81..91}",
 :attempt-count 92,
 :unexpected "#{}",
 :unexpected-count 0,
 :recovered-count 0}


Everything looks good! ヽ(‘ー`)ノ
```

You can list all the options of the tests:
```
$ lein run test --help
```

Most common options to use:
 * `--time-limit`: how long the test should run for (in seconds)
 * `--concurrency`: number of workers to run
 * `--rate`: number of requests per thread
 * `--workload`: the type of test, can be `register` or `set`
 * `--nemesis`: the type of nemesis, can be `random-partition-halves`,
 `partition-halves`, `partition-majorities-ring`, `random-node`
 `kill-erlang-vm`, `kill-erlang-process`, or `combined` (see below for the `combined` nemesis)
 * `--network-partition-nemesis`: the type of network partition nemesis to use with the
 `combined` nemesis. Can be `random-partition-halves`, `partition-halves`,
 `partition-majorities-ring` or `random-node`. Default is `random-partition-halves`.
 * `--erlang-net-ticktime`: Erlang net tick time (in seconds)
 * `--release-cursor-every`: release RA cursor every n operations
 * `--wal-max-size-bytes`: maximum size of RA Write Ahead Log, default is 134217728 (128 MB)
 * `--random-nodes`: number of nodes disrupted by Erlang VM and Erlang process killing nemesises
 * `--time-before-disruption`: time before the nemesis kicks in (in seconds)
 * `--disruption-duration`: duration of disruption (in seconds)

The `register` workload tests the RA KV Store cluster as a set of registers, identified
by a key. The supported operations are read, write, and compare-and-set. The `set` workload
tests the RA KV Store cluster as a set (a collection of distinct elements stored in the
same key). The `register` workload is more general but also more "expensive" (understand
more resource-consuming when it comes to find bugs) than the `set` test.

The `combined` nemesis creates a network partition and randomly kills the Erlang VM or Erlang
processes on random nodes (`--random-nodes` option) during the partition. The network partition
strategy can be chosen with the `--network-partition-nemesis` option when using the `combined` nemesis,
the default being `random-partition-halves`.

## Running Jepsen tests with Vagrant (outdated)

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

_Sponsored by [Pivotal](https://pivotal.io)_
