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

-module(ra_kv_store_handler).
-behavior(cowboy_handler).

-export([init/2]).

init(Req0=#{method := <<"GET">>}, State) ->
    ServerReference = proplists:get_value(server_reference, State),
	Key = cowboy_req:binding(key, Req0),
    {ok, Value, _} = ra:send_and_await_consensus(ServerReference, {read, Key}),
    Req = case Value of
        undefined ->
            cowboy_req:reply(404,
                #{<<"content-type">> => <<"text/plain">>},
                <<"undefined">>,
                Req0);
        Value ->
            cowboy_req:reply(200,
                #{<<"content-type">> => <<"text/plain">>},
                Value,
                Req0)
    end,
	{ok, Req, State};
init(Req0=#{method := <<"PUT">>}, State) ->
    ServerReference = proplists:get_value(server_reference, State),
    Key = cowboy_req:binding(key, Req0),
    {ok, KeyValues, Req1} = cowboy_req:read_urlencoded_body(Req0),
    Value = proplists:get_value(<<"value">>, KeyValues),
    Expected = proplists:get_value(<<"expected">>, KeyValues),
    Req = case Expected of
        undefined ->
            {ok, _, _} = ra:send_and_await_consensus(ServerReference, {write, Key, Value}),
            cowboy_req:reply(204, #{}, Req1);
        Expected ->
            {ok, ReadValue, _} = ra:send_and_await_consensus(ServerReference, {cas, Key, Expected, Value}),
            case ReadValue of
                Expected ->
                    cowboy_req:reply(204, #{}, Req1);
                _ ->
                    cowboy_req:reply(409, #{}, ReadValue, Req1)
            end
    end,
	{ok, Req, State};
init(Req0, State) ->
	Req = cowboy_req:reply(405, #{
		<<"allow">> => <<"GET,PUT">>
	}, Req0),
	{ok, Req, State}.
