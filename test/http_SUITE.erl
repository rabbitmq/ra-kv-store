%% Copyright (c) 2018-2023 Broadcom. All Rights Reserved. The term Broadcom refers to Broadcom Inc. and/or its subsidiaries.
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

-module(http_SUITE).

-include_lib("common_test/include/ct.hrl").

-compile(export_all).

all() ->
    [http_handler].

group() ->
    [].

%% -------------------------------------------------------------------
%% Testsuite setup/teardown.
%% -------------------------------------------------------------------

init_per_suite(Config) ->
    application:load(ra),
    logger:set_primary_config(level, all),
    WorkDirectory = proplists:get_value(priv_dir, Config),
    ok =
        application:set_env(ra, data_dir, filename:join(WorkDirectory, "ra")),
    Config.

end_per_suite(Config) ->
    application:stop(ra),
    Config.

%% -------------------------------------------------------------------
%% Testcases.
%% -------------------------------------------------------------------

http_handler(_Config) ->
    Nodes = [{ra_kv1, node()}, {ra_kv2r, node()}, {ra_kv3, node()}],
    ClusterId = <<"ra_kv_store_http_handler">>,
    Config = #{},
    Machine = {module, ra_kv_store, Config},
    application:ensure_all_started(ra),
    ra_system:start_default(),
    {ok, _, _} = ra:start_cluster(default, ClusterId, Machine, Nodes),
    {ok, _, {Leader, _}} = ra:members(hd(Nodes)),

    application:ensure_all_started(cowboy),

    Dispatch =
        cowboy_router:compile([{'_',
                                [{"/:key", ra_kv_store_handler,
                                  [{server_reference, Leader}]}]}]),

    {ok, Socket} = gen_tcp:listen(0, []),
    {ok, Port} = inet:port(Socket),
    gen_tcp:close(Socket),

    {ok, _} =
        cowboy:start_clear(kv_store_http_listener, [{port, Port}],
                           #{env => #{dispatch => Dispatch}}),

    ok = inets:start(),

    Url = io_lib:format("http://localhost:~p/~p", [Port, 1]),

    {ok, {{_, 404, _}, _, _}} = httpc:request(get, {Url, []}, [], []),

    {ok, {{_, 204, _}, Headers1, _}} =
        httpc:request(put, {Url, [], [], "value=1"}, [], []),

    LeaderAsList = atom_to_list(Leader),

    "2" = proplists:get_value("ra_index", Headers1),
    "1" = proplists:get_value("ra_term", Headers1),
    LeaderAsList = proplists:get_value("ra_leader", Headers1),

    {ok, {{_, 200, _}, HeadersGet, "1"}} =
        httpc:request(get, {Url, []}, [], []),

    "2" = proplists:get_value("ra_index", HeadersGet),
    "1" = proplists:get_value("ra_term", HeadersGet),
    LeaderAsList = proplists:get_value("ra_leader", HeadersGet),

    {ok, {{_, 204, _}, Headers2, _}} =
        httpc:request(put, {Url, [], [], "value=2&expected=1"}, [], []),

    "3" = proplists:get_value("ra_index", Headers2),
    "1" = proplists:get_value("ra_term", Headers2),
    LeaderAsList = proplists:get_value("ra_leader", Headers2),

    {ok, {{_, 409, _}, Headers3, "2"}} =
        httpc:request(put, {Url, [], [], "value=99&expected=1"}, [], []),

    "4" = proplists:get_value("ra_index", Headers3),
    "1" = proplists:get_value("ra_term", Headers3),
    LeaderAsList = proplists:get_value("ra_leader", Headers3),

    {ok, {{_, 204, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value=3&expected=2"}, [], []),

    %% test with empty values meant to reset a key
    {ok, {{_, 204, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value=&expected=3"}, [], []),

    {ok, {{_, 404, _}, _, _}} = httpc:request(get, {Url, []}, [], []),

    {ok, {{_, 204, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value=1&expected="}, [], []),

    {ok, {{_, 200, _}, _, "1"}} = httpc:request(get, {Url, []}, [], []),

    %% ensure expected value in CAS can be empty
    {ok, {{_, 204, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value="}, [], []),

    {ok, {{_, 404, _}, _, _}} = httpc:request(get, {Url, []}, [], []),

    {ok, {{_, 409, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value=1&expected=2"}, [], []),

    {ok, {{_, 204, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value=1&expected="}, [], []),

    {ok, {{_, 200, _}, _, "1"}} = httpc:request(get, {Url, []}, [], []),

    {ok, {{_, 409, _}, _, _}} =
        httpc:request(put, {Url, [], [], "value=2&expected="}, [], []),

    {ok, {{_, 200, _}, _, "1"}} = httpc:request(get, {Url, []}, [], []),

    ok.
