%% Copyright (c) 2018 Pivotal Software Inc, All Rights Reserved.
%%
%% Licensed under the Apache License, Version 2.0 (the "License");
%% you may not use this file except in compliance with the License.
%% You may obtain a copy of the License at
%%
%%       https://www.apache.org/licenses/LICENSE-2.0
%%
%% Unless required by applicable law or agreed to in writing, software
%% distributed under the License is distributed on an "AS IS" BASIS,
%% WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
%% See the License for the specific language governing permissions and
%% limitations under the License.
%%

-module(ra_kv_store_app).
-behaviour(application).

-export([start/2, connect_nodes/1, connect_node/1]).
-export([stop/1]).


wait_for_nodes([]) ->
    error_logger:info_msg("All erlang nodes connected~n", []),
    ok;
wait_for_nodes([Node | Rem] = AllNodes) ->
    case net_kernel:connect_node(Node) of
        true ->
            %% we're connected, great
            wait_for_nodes(Rem);
        false ->
            error_logger:info_msg("Could not connect ~w. Sleeping...~n", [Node]),
            %% we could not connect, sleep a bit and recurse
            timer:sleep(1000),
            wait_for_nodes(AllNodes)
    end.


start(_Type, _Args) ->
    {ok, Servers} = application:get_env(ra_kv_store, nodes),
    Nodes = [N || {_, N} <- Servers],
    {ok, ServerReference} = application:get_env(ra_kv_store, server_reference),
    logger:set_primary_config(level, all),
    ClusterId = <<"ra_kv_store">>,
    Config = #{},
    Machine = {module, ra_kv_store, Config},
    ok = ra:start(),

    case application:get_env(ra_kv_store, restart_ra_cluster) of
        {ok, true} ->
            Node = {ServerReference, node()},
            error_logger:info_msg("Restarting RA node ~p~n", [Node]),
            ok = ra:restart_server(Node);
        {ok, false} ->
            [N | _] = lists:usort(Nodes),
            case N == node() of
                true ->
                    %% wait for all nodes to come online
                    ok = wait_for_nodes(Nodes),
                    %% only the smallest node declares a cluster
                    timer:sleep(2000),
                    ra_system:start_default(),
                    {ok, Started, Failed} = ra:start_cluster(default, ClusterId, Machine, Servers),
                    case length(Started) == length(Servers) of
                        true ->
                            %% all started
                            ok;
                        false ->
                            error_logger:info_msg("RA cluster failures  ~w",
                                                  [Failed]),
                            ok
                    end;
                false ->
                    ok
            end,
            ok
    end,

    % to make sure nodes are always connected
    {ok, ReconnectInterval} = application:get_env(ra_kv_store, node_reconnection_interval),
    {ok, _ } = timer:apply_interval(
        ReconnectInterval,
        ?MODULE, connect_nodes, [Servers]),

    Dispatch = cowboy_router:compile([
        {'_', [{"/:key", ra_kv_store_handler, [{server_reference, ServerReference}]}]}
    ]),

    {ok, Port} = application:get_env(ra_kv_store, port),

    {ok, _} = cowboy:start_clear(kv_store_http_listener,
        [{port, Port}],
        #{env => #{dispatch => Dispatch}}
    ),
    ra_kv_store_sup:start_link().

stop(_State) ->
    ok.

connect_nodes(Nodes) ->
    error_logger:info_msg("Reconnecting nodes ~p~n", [Nodes]),
    lists:foreach(fun ra_kv_store_app:connect_node/1, Nodes).

connect_node({_, Node}) ->
    net_kernel:connect_node(Node).
