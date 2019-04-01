PROJECT = ra_kv_store
PROJECT_DESCRIPTION = Experimental raft-based key/value store
PROJECT_VERSION = 0.1.0
PROJECT_MOD = ra_kv_store_app

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

dep_ra = git https://github.com/rabbitmq/ra.git master
DEPS = ra cowboy
dep_cowboy_commit = 2.6.1

DEP_PLUGINS = cowboy

clean-rel:
	rm -rf _rel

clean-deps:
	rm -rf deps

rel-docker: clean-rel clean-deps
	docker run -it --rm --name erlang-inst1 -v "$(PWD)":/usr/src/myapp -w /usr/src/myapp erlang:21.3.1 make rel

rel-jepsen: rel-docker
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/

rel-jepsen-local: rel
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/

include erlang.mk
