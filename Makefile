PROJECT = ra_kv_store
PROJECT_DESCRIPTION = Experimental raft-based key/value store
PROJECT_VERSION = 0.1.0
PROJECT_MOD = ra_kv_store_app

ERLANG_VERSION_FOR_DOCKER_IMAGE ?= 26.2.5.6

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

dep_ra = git https://github.com/rabbitmq/ra.git follower-resend-bug
DEPS = ra cowboy cowlib ranch
dep_cowboy_commit = 2.13.0
dep_cowlib = git https://github.com/ninenines/cowlib 2.14.0
dep_ranch = git https://github.com/ninenines/ranch 2.2.0

DEP_PLUGINS = cowboy
BUILD_DEPS = relx

clean-rel:
	rm -rf _rel

clean-deps:
	rm -rf deps

rel-docker: clean-rel clean-deps
	docker run -it --rm --name erlang-inst1 -v "$(PWD)":/usr/src/ra_kv_store -w /usr/src/ra_kv_store rabbitmqdevenv/erlang-dev-bookworm make rel

rel-jepsen: rel-docker
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/

rel-jepsen-local: rel
	cp _rel/ra_kv_store_release/*.tar.gz jepsen/jepsen.rakvstore/


.PHONY: erlang-docker-image
erlang-docker-image: ## Build Erlang Docker (for local development)
	@docker build \
	  --file Dockerfile-erlang \
	  --tag rabbitmqdevenv/erlang-dev-bookworm:$(ERLANG_VERSION_FOR_DOCKER_IMAGE) \
	  --tag rabbitmqdevenv/erlang-dev-bookworm:latest \
	  .

.PHONY: push-erlang-docker-image
push-erlang-docker-image: erlang-docker-image ## Push Erlang Docker image
	@docker push rabbitmqdevenv/erlang-dev-bookworm:$(ERLANG_VERSION_FOR_DOCKER_IMAGE)
	@docker push rabbitmqdevenv/erlang-dev-bookworm:latest

include erlang.mk
