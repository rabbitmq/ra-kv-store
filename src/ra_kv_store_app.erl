%% Copyright (c) 2018 Pivotal Software Inc, All Rights Reserved.
%%
%% Licensed under the Apache License, Version 2.0 (the "License");
%% you may not use this file except in compliance with the License.
%% You may obtain a copy of the License at
%%
%%       http://www.apache.org/licenses/LICENSE-2.0
%%
%% Unless required by applicable law or agreed to in writing, software
%% distributed under the License is distributed on an "AS IS" BASIS,
%% WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
%% See the License for the specific language governing permissions and
%% limitations under the License.
%%

-module(ra_kv_store_app).
-behaviour(application).

-export([start/2]).
-export([stop/1]).

start(_Type, _Args) ->
    {ok, Nodes} = application:get_env(ra_kv_store, nodes),
    {ok, ServerReference} = application:get_env(ra_kv_store, server_reference),
    ClusterId = <<"ra_kv_store">>,
    Config = #{},
    Machine = {module, ra_kv_store, Config},
    application:ensure_all_started(ra),
    ra:start_cluster(ClusterId, Machine, Nodes),

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
