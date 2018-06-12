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

-module(store_SUITE).

-include_lib("common_test/include/ct.hrl").

-compile(export_all).

all() ->
    [
      kv_store
    ].

group() -> [].

%% -------------------------------------------------------------------
%% Testsuite setup/teardown.
%% -------------------------------------------------------------------

init_per_suite(Config) ->
    application:load(ra),
    WorkDirectory = proplists:get_value(priv_dir, Config),
    ok = application:set_env(ra, data_dir, filename:join(WorkDirectory, "ra")),
    Config.

end_per_suite(Config) ->
    application:stop(ra),
    Config.

%% -------------------------------------------------------------------
%% Testcases.
%% -------------------------------------------------------------------

kv_store(_Config) ->
    Nodes = [{ra_kv1, node()}, {ra_kv2, node()}, {ra_kv3, node()}],
    ClusterId = <<"ra_kv_store">>,
    Config = #{},
    Machine = {module, ra_kv_store, Config},
    application:ensure_all_started(ra),
    {ok, _, _} = ra:start_cluster(ClusterId, Machine, Nodes),
    {ok, _, _} = ra:send_and_await_consensus(ra_kv1, {write, 1, 2}),
    {ok, 2, _} = ra:send_and_await_consensus(ra_kv1, {read, 1}),
    {ok, 2, _} = ra:send_and_await_consensus(ra_kv1, {cas, 1, 2, 4}),
    {ok, 4, _} = ra:send_and_await_consensus(ra_kv1, {cas, 1, 3, 6}),
    {ok, 4, _} = ra:send_and_await_consensus(ra_kv1, {read, 1}),
    ok.