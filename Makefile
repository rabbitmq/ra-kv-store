PROJECT = ra_kv_store
PROJECT_DESCRIPTION = Experimental raft-based key/value store
PROJECT_VERSION = 0.1.0
PROJECT_MOD = ra_kv_store_app

define PROJECT_ENV
[
	{port, 8080}
]
endef

dep_ra = git https://github.com/rabbitmq/ra.git master
DEPS = ra cowboy
dep_cowboy_commit = 2.4.0

DEP_PLUGINS = cowboy

include erlang.mk
