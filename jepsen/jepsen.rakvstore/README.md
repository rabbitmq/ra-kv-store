# Jepsen Test for Raft-based Key/Value Store

This work is based on [Jepsen tutorial](https://github.com/jepsen-io/jepsen/blob/master/doc/tutorial/index.md).

## Building the Erlang release

You must be in the project **root directory** to build the Erlang release. The Erlang
release needs to be built on Linux-based system, as this is where it executes in Jepsen tests.

The easiest is to use a one-time Docker container to compile the release
with the appropriate configuration:

```shell
git clone https://github.com/rabbitmq/ra-kv-store.git ra_kv_store
cd ra_kv_store
make rel-jepsen
```

Then copy the release file in the Jepsen test directory:

```shell
cp _rel/ra_kv_store_release/ra_kv_store_release-1.tar.gz jepsen/jepsen.rakvstore
```

## Running Jepsen tests with Docker

**Make sure the Erlang release file `ra_kv_store_release-1.tar.gz` is in the
`jepsen/jepsen.rakvstore/` directory.**

`cd` into the `jepsen/docker` directory and start the containers:

```shell
cd jepsen/docker
ssh-keygen -t rsa -m pem -f shared/jepsen-bot -C jepsen-bot -N ''
docker compose up --detach
./provision.sh
```

Connect to the Jepsen control container:

```shell
docker exec -it jepsen-control bash
```

Inside the control container, `cd` into the RA KV Store test directory and launch a test:

```shell
cd /root/jepsen.rakvstore
lein run test --nodes n1,n2,n3 --ssh-private-key /root/shared/jepsen-bot --time-limit 15 --concurrency 10 --rate 10 --workload set --nemesis random-partition-halves
```

The execution should finish with something like the following:

```
INFO [2024-10-21 12:38:19,023] jepsen test runner - jepsen.core {:perf {:latency-graph {:valid? true},
        :rate-graph {:valid? true},
        :valid? true},
 :workload {:ok-count 144,
            :valid? true,
            :lost-count 0,
            :lost "#{}",
            :acknowledged-count 144,
            :recovered "#{}",
            :ok "#{0..53 55..56 58..59 61..146}",
            :attempt-count 147,
            :unexpected "#{}",
            :unexpected-count 0,
            :recovered-count 0},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Here is how to shut down and delete the containers:

```shell
docker compose down
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

## Client

Jepsen tests access the KV store through a Java-based client. This client handles HTTP connections, requests,
and responses, as well appropriate logic for the tests (error handling, value swapping). The client is
compiled as part of the Jepsen test. The client and its corresponding unit tests can be modified and the
changes can be checked by running Maven:

```
./mvnw clean test
```

## License

RA KV Store is [Apache 2.0 licensed](https://www.apache.org/licenses/LICENSE-2.0.html).

Copyright 2018-2024 Broadcom. All Rights Reserved.
The term Broadcom refers to Broadcom Inc. and/or its subsidiaries.
