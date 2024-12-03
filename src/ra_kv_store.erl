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

-module(ra_kv_store).

-behaviour(ra_machine).

-record(state,
        {store = #{} :: #{term() => term()}, index :: term(),
         term :: term()}).

-export([init/1,
         apply/3,
         write/3,
         read/2,
         cas/4]).

-define(OP_TIMEOUT, 100).

write(ServerReference, Key, Value) ->
    Cmd = {write, Key, Value},
    case ra:process_command(ServerReference, Cmd, ?OP_TIMEOUT) of
        {ok, {Index, Term}, LeaderRaNodeId} ->
            {ok, {{index, Index}, {term, Term}, {leader, LeaderRaNodeId}}};
        {timeout, _} ->
            timeout
    end.

read(ServerReference, Key) ->
    case ra:consistent_query(ServerReference,
                             fun(#state{store = Store,
                                        index = Index,
                                        term = Term}) ->
                                {maps:get(Key, Store, undefined), Index, Term}
                             end,
                             ?OP_TIMEOUT)
    of
        {ok, {V, Idx, T}, {Leader, _}} ->
            {{read, V}, {index, Idx}, {term, T}, {leader, Leader}};
        {timeout, _} ->
            logger:info("Read operation failed because of timeout~n"),
            timeout;
        {error, nodedown} ->
            logger:info("Read operation failed because node is down~n"),
            error;
        R ->
            logger:warning("Unexpected result for read operation: ~p~n", [R]),
            error
    end.

cas(ServerReference, Key, ExpectedValue, NewValue) ->
    Cmd = {cas, Key, ExpectedValue, NewValue},
    case ra:process_command(ServerReference, Cmd, ?OP_TIMEOUT) of
        {ok, {{read, ReadValue}, {index, Index}, {term, Term}},
         LeaderRaNodeId} ->
            {ok,
             {{read, ReadValue},
              {index, Index},
              {term, Term},
              {leader, LeaderRaNodeId}}};
        {timeout, _} ->
            timeout
    end.

init(_Config) ->
    #state{}.

apply(#{index := Index, term := Term} = _Metadata,
      {write, Key, Value}, #state{store = Store0} = State0) ->
    Store1 = maps:put(Key, Value, Store0),
    State1 =
        State0#state{store = Store1,
                     index = Index,
                     term = Term},
    SideEffects = side_effects(Index, State1),
    %% return the index and term here as a result
    {State1, {Index, Term}, SideEffects};
apply(#{index := Index, term := Term} = _Metadata,
      {cas, Key, ExpectedValue, NewValue},
      #state{store = Store0} = State0) ->
    {Store1, ReadValue} =
        case maps:get(Key, Store0, undefined) of
            ExpectedValue ->
                {maps:put(Key, NewValue, Store0), ExpectedValue};
            ValueInStore ->
                {Store0, ValueInStore}
        end,
    State1 =
        State0#state{store = Store1,
                     index = Index,
                     term = Term},
    SideEffects = side_effects(Index, State1),
    {State1, {{read, ReadValue}, {index, Index}, {term, Term}},
     SideEffects}.

side_effects(RaftIndex, MachineState) ->
    case application:get_env(ra_kv_store, release_cursor_every) of
        undefined ->
            [];
        {ok, NegativeOrZero} when NegativeOrZero =< 0 ->
            [];
        {ok, Every} ->
            case release_cursor(RaftIndex, Every) of
                release_cursor ->
                    [{release_cursor, RaftIndex, MachineState}];
                _ ->
                    []
            end
    end.

release_cursor(Index, Every) ->
    case Index rem Every of
        0 ->
            release_cursor;
        _ ->
            do_not_release_cursor
    end.
