PROJECT = ra_kv_store
PROJECT_DESCRIPTION = Experimental raft-based key/value store
PROJECT_VERSION = 0.1.0
PROJECT_MOD = ra_kv_store_app

ERLANG_VERSION_FOR_DOCKER_IMAGE ?= 28.5.0.6

define PROJECT_ENV
[
	{port, 8080},
    {nodes, [{ra_kv1, 'ra_kv_store@127.0.0.1'}]},
    {server_reference, ra_kv1},
    {release_cursor_every, -1},
    {node_reconnection_interval, 10000},
    {restart_ra_cluster, false}
]
endef

DEPS = ra cowboy
dep_ra = git https://github.com/rabbitmq/ra.git main
dep_cowboy = git https://github.com/ninenines/cowboy 2.19.0

DEP_PLUGINS = cowboy
BUILD_DEPS = relx

clean-rel:
	rm -rf _rel

clean-deps:
	rm -rf deps

rel-docker: clean-rel clean-deps
	docker run -it --rm --name erlang-inst1 -v "$(PWD)":/usr/src/ra_kv_store -w /usr/src/ra_kv_store erlang:28 make rel

rel-jepsen: rel-docker
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/

rel-jepsen-local: rel
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/

include erlang.mk
