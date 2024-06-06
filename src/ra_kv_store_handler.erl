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

-module(ra_kv_store_handler).

-behavior(cowboy_handler).

-export([init/2]).

-define(READ_BODY_OPTIONS, #{length => 640000}).

init(Req0 = #{method := <<"GET">>}, State) ->
    ServerReference = proplists:get_value(server_reference, State),
    Key = cowboy_req:binding(key, Req0),
    Value = ra_kv_store:read(ServerReference, Key),
    Req = case Value of
              timeout ->
                  logger:info("Timeout on GET, returning 503~n"),
                  cowboy_req:reply(503, #{}, "RA timeout", Req0);
              error ->
                  logger:info("Error on GET, returning 503~n"),
                  cowboy_req:reply(503, #{}, "RA error", Req0);
              {{read, undefined},
               {index, Index},
               {term, Term},
               {leader, Leader}} ->
                  cowboy_req:reply(404,
                                   #{"content-type" => <<"text/plain">>,
                                     "ra_index" => to_list(Index),
                                     "ra_term" => to_list(Term),
                                     "ra_leader" => to_list(Leader)},
                                   <<"undefined">>,
                                   Req0);
              {{read, Read}, {index, Index}, {term, Term}, {leader, Leader}} ->
                  cowboy_req:reply(200,
                                   #{"content-type" => <<"text/plain">>,
                                     "ra_index" => to_list(Index),
                                     "ra_term" => to_list(Term),
                                     "ra_leader" => to_list(Leader)},
                                   Read,
                                   Req0)
          end,
    {ok, Req, State};
init(Req0 = #{method := <<"PUT">>}, State) ->
    ServerReference = proplists:get_value(server_reference, State),
    Key = cowboy_req:binding(key, Req0),
    {ok, KeyValues, Req1} =
        cowboy_req:read_urlencoded_body(Req0, ?READ_BODY_OPTIONS),
    Value =
        case proplists:get_value(<<"value">>, KeyValues) of
            <<"">> ->
                undefined;
            NotNullValue ->
                NotNullValue
        end,
    Expected =
        case proplists:get_value(<<"expected">>, KeyValues, not_present) of
            <<"">> ->
                undefined;
            NotNullExpectedValue ->
                NotNullExpectedValue
        end,

    Req = case Expected of
              not_present ->
                  case ra_kv_store:write(ServerReference, Key, Value) of
                      {ok,
                       {{index, Index}, {term, Term},
                        {leader, LeaderRaNodeId}}} ->
                          cowboy_req:reply(204,
                                           #{"ra_index" => to_list(Index),
                                             "ra_term" => to_list(Term),
                                             "ra_leader" =>
                                                 to_list(LeaderRaNodeId)},
                                           Req1);
                      timeout ->
                          cowboy_req:reply(503, #{}, "RA timeout", Req1)
                  end;
              Expected ->
                  case ra_kv_store:cas(ServerReference, Key, Expected, Value) of
                      {ok,
                       {{read, Expected},
                        {index, Index},
                        {term, Term},
                        {leader, LeaderRaNodeId}}} ->
                          cowboy_req:reply(204,
                                           #{"ra_index" => to_list(Index),
                                             "ra_term" => to_list(Term),
                                             "ra_leader" =>
                                                 to_list(LeaderRaNodeId)},
                                           Req1);
                      timeout ->
                          cowboy_req:reply(503, #{}, "RA timeout", Req1);
                      {ok,
                       {{read, ReadValue},
                        {index, Index},
                        {term, Term},
                        {leader, LeaderRaNodeId}}} ->
                          cowboy_req:reply(409,
                                           #{"ra_index" => to_list(Index),
                                             "ra_term" => to_list(Term),
                                             "ra_leader" =>
                                                 to_list(LeaderRaNodeId)},
                                           to_list(ReadValue),
                                           Req1)
                  end
          end,
    {ok, Req, State};
init(Req0, State) ->
    Req = cowboy_req:reply(405, #{<<"allow">> => <<"GET,PUT">>}, Req0),
    {ok, Req, State}.

-spec to_list(Val :: integer() | list() | binary() | atom()) ->
                 list().
to_list(Val) when is_list(Val) ->
    Val;
to_list(Val) when is_atom(Val) ->
    atom_to_list(Val);
to_list(Val) when is_binary(Val) ->
    binary_to_list(Val);
to_list(Val) when is_integer(Val) ->
    integer_to_list(Val);
to_list({Leader, _}) when is_atom(Leader) ->
    atom_to_list(Leader);
to_list(Val) ->
    lists:flatten(
        io_lib:format("~w", [Val])).
