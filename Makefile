PROJECT = ra_kv_store
PROJECT_DESCRIPTION = Experimental raft-based key/value store
PROJECT_VERSION = 0.1.0
PROJECT_MOD = ra_kv_store_app

ERLANG_VERSION_FOR_DOCKER_IMAGE ?= 26.2.5.4 

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

dep_ra = git https://github.com/rabbitmq/ra.git main
DEPS = ra cowboy cowlib ranch
dep_cowboy_commit = 2.11.0
dep_cowlib = git https://github.com/ninenines/cowlib 2.12.1
dep_ranch = git https://github.com/ninenines/ranch 2.1.0

DEP_PLUGINS = cowboy
BUILD_DEPS = relx

clean-rel:
	rm -rf _rel

clean-deps:
	rm -rf deps

rel-docker: clean-rel clean-deps
	docker run -it --rm --name erlang-inst1 -v "$(PWD)":/usr/src/ra_kv_store -w /usr/src/ra_kv_store pivotalrabbitmq/erlang-dev-bookworm make rel

rel-jepsen: rel-docker
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/

rel-jepsen-local: rel
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/


.PHONY: erlang-docker-image
erlang-docker-image: ## Build Erlang Docker (for local development)
	@docker build \
	  --file Dockerfile-erlang \
	  --tag pivotalrabbitmq/erlang-dev-bookworm:$(ERLANG_VERSION_FOR_DOCKER_IMAGE) \
	  --tag pivotalrabbitmq/erlang-dev-bookworm:latest \
	  .

.PHONY: push-erlang-docker-image
push-erlang-docker-image: erlang-docker-image ## Push Erlang Docker image
	@docker push pivotalrabbitmq/erlang-dev-bookworm:$(ERLANG_VERSION_FOR_DOCKER_IMAGE)
	@docker push pivotalrabbitmq/erlang-dev-bookworm:latest

include erlang.mk
