# Jepsen Test for Raft-based Key/Value Store

## Usage (Docker)

In the **root directory**, package the Erlang application and copy it in the Jepsen test directory:

```
ra-kv-store $ make rel-jepsen
...
cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/
```

`cd` into the `jepsen/docker` directory:
```
ra-kv-store $ cd jepsen/docker
```

Start the containers:

```
docker $ ./up --dev
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

Once the VMs are up, you can start a Jepsen test:

```
jepsen.rakvstore $ lein run test --node n1 --node n2 --node n3 \
  --username vagrant --password vagrant --ssh-private-key $(pwd)/.vagrant/machines/n1/virtualbox/private_key \
  --test-count 1 --time-limit 15
```

## License

RA KV Store is [Apache 2.0 licensed](http://www.apache.org/licenses/LICENSE-2.0.html).

_Sponsored by [Pivotal](http://pivotal.io)_
