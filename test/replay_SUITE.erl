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

-module(replay_SUITE).

-include_lib("common_test/include/ct.hrl").

-compile(export_all).

all() ->
    [replay].

group() ->
    [].

%% -------------------------------------------------------------------
%% Testsuite setup/teardown.
%% -------------------------------------------------------------------

init_per_suite(Config) ->
    application:load(ra),
    WorkDirectory = proplists:get_value(priv_dir, Config),
    ok =
        application:set_env(ra, data_dir, filename:join(WorkDirectory, "ra")),
    ok = application:set_env(ra_kv_store, release_cursor_every, 1),
    Config.

end_per_suite(Config) ->
    application:stop(ra),
    Config.

%% -------------------------------------------------------------------
%% Testcases.
%% -------------------------------------------------------------------

replay(_Config) ->
    Nodes = [{ra_kv1, node()}, {ra_kv2, node()}, {ra_kv3, node()}],
    ClusterId = <<"ra_kv_store">>,
    Config = #{},
    Machine = {module, ra_kv_store, Config},
    application:ensure_all_started(ra),
    ra_system:start_default(),
    {ok, _, _} = ra:start_cluster(default, ClusterId, Machine, Nodes),
    {ok, _} = ra_kv_store:write(ra_kv1, 1, 2),
    {ok, {{read, 2}, _, _, _}} = ra_kv_store:cas(ra_kv1, 1, 2, 4),
    {ok, {{read, 4}, _, _, _}} = ra_kv_store:cas(ra_kv1, 1, 3, 6),

    {ok, {{read, undefined}, _, _, _}} =
        ra_kv_store:cas(ra_kv1, 2, undefined, 1),
    {ok, {{read, 1}, _, _, _}} = ra_kv_store:cas(ra_kv1, 2, undefined, 3),

    {ok, RaDataDir} = application:get_env(ra, data_dir),
    NodeDirectory = filename:join([RaDataDir, node()]),
    {ok, RaFiles} = file:list_dir(NodeDirectory),
    WalSuffix = "1.wal",
    [WalFilename] = lists:filter(fun(F) -> string:find(F, WalSuffix) =:= WalSuffix end, RaFiles),

    WalFile = filename:join([NodeDirectory, WalFilename]),

    InitialState = ra_kv_store:init(Config),
    {_, Store, _, _} =
        ra_dbg:replay_log(WalFile, ra_kv_store, InitialState),
    Store = #{1 => 4, 2 => 1},
    ok.
