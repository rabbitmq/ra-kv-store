/*
 * Copyright (c) 2018-2023 Broadcom. All Rights Reserved. The term Broadcom refers to Broadcom Inc. and/or its subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rabbitmq.jepsen;

import static com.rabbitmq.jepsen.Utils.erlangNetTickTime;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** */
public class UtilsTest {

  private static final String KEY = "foo";
  Utils.Client client;

  @BeforeEach
  public void init() throws Exception {
    client = Utils.createClient("localhost");
    client.write(KEY, "");
  }

  @Test
  public void configurationFile() {
    Map<Object, Object> test = new HashMap<>();
    test.put(":nodes", asList("192.168.33.10"));
    String configuration = Utils.configuration(test, "192.168.33.10");

    assertThat(configuration)
        .isEqualTo(
            "[\n"
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, 134217728}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [{ra_kv1, 'kv@192.168.33.10'}]},\n"
                + "        {server_reference, ra_kv1},\n"
                + "        {release_cursor_every, -1}\n"
                + "    ]}\n"
                + "].");

    test = new HashMap<>();
    test.put(":nodes", asList("192.168.33.10", "192.168.33.11", "192.168.33.12"));
    configuration = Utils.configuration(test, "192.168.33.10");

    assertThat(configuration)
        .isEqualTo(
            "[\n"
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, 134217728}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [{ra_kv1, 'kv@192.168.33.10'}, {ra_kv2, 'kv@192.168.33.11'}, {ra_kv3, 'kv@192.168.33.12'}]},\n"
                + "        {server_reference, ra_kv1},\n"
                + "        {release_cursor_every, -1}\n"
                + "    ]}\n"
                + "].",
            configuration);

    test = new HashMap<>();
    test.put(":nodes", asList("n1", "n2", "n3"));
    test.put(":release-cursor-every", "10");
    configuration = Utils.configuration(test, "n2");

    assertThat(configuration)
        .isEqualTo(
            "[\n"
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, 134217728}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [{ra_kv1, 'kv@n1'}, {ra_kv2, 'kv@n2'}, {ra_kv3, 'kv@n3'}]},\n"
                + "        {server_reference, ra_kv2},\n"
                + "        {release_cursor_every, 10}\n"
                + "    ]}\n"
                + "].",
            configuration);

    test = new HashMap<>();
    test.put(":nodes", asList("n1", "n2", "n3"));
    test.put(":erlang-net-ticktime", "-1");
    configuration = Utils.configuration(test, "n2");
    assertThat(configuration)
        .isEqualTo(
            "[\n"
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, 134217728}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [{ra_kv1, 'kv@n1'}, {ra_kv2, 'kv@n2'}, {ra_kv3, 'kv@n3'}]},\n"
                + "        {server_reference, ra_kv2},\n"
                + "        {release_cursor_every, -1}\n"
                + "    ]}\n"
                + "].",
            configuration);

    test = new HashMap<>();
    test.put(":nodes", asList("n1", "n2", "n3"));
    test.put(":erlang-net-ticktime", "15");
    configuration = Utils.configuration(test, "n2");
    assertThat(configuration)
        .isEqualTo(
            "[\n"
                + "{kernel, [{net_ticktime,  15}]},\n"
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, 134217728}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [{ra_kv1, 'kv@n1'}, {ra_kv2, 'kv@n2'}, {ra_kv3, 'kv@n3'}]},\n"
                + "        {server_reference, ra_kv2},\n"
                + "        {release_cursor_every, -1}\n"
                + "    ]}\n"
                + "].",
            configuration);

    test = new HashMap<>();
    test.put(":nodes", asList("n1", "n2", "n3"));
    test.put(":erlang-net-ticktime", "-1");
    test.put(":wal-max-size-bytes", "8388608"); // 8 MB
    configuration = Utils.configuration(test, "n2");
    assertThat(configuration)
        .isEqualTo(
            "[\n"
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, 8388608}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [{ra_kv1, 'kv@n1'}, {ra_kv2, 'kv@n2'}, {ra_kv3, 'kv@n3'}]},\n"
                + "        {server_reference, ra_kv2},\n"
                + "        {release_cursor_every, -1}\n"
                + "    ]}\n"
                + "].",
            configuration);
  }

  @Test
  public void configurationNetTickTime() {
    assertThat(erlangNetTickTime(singletonMap(":erlang-net-ticktime", "-1"))).isEmpty();
    assertThat(erlangNetTickTime(singletonMap(":erlang-net-ticktime", "0")))
        .isEqualTo("{kernel, [{net_ticktime,  0}]},\n");
    assertThat(erlangNetTickTime(singletonMap(":erlang-net-ticktime", "120")))
        .isEqualTo("{kernel, [{net_ticktime,  120}]},\n");
    assertThatThrownBy(() -> erlangNetTickTime(singletonMap(":erlang-net-ticktime", "dummy")))
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  public void raNodeId() {
    assertThat(Utils.raNodeId("n1", 0)).isEqualTo("{ra_kv1, 'kv@n1'}");
    assertThat(Utils.raNodeId("n2", 1)).isEqualTo("{ra_kv2, 'kv@n2'}");
  }

  @Test
  public void writeGet() throws Exception {
    assertThat(client.get(KEY)).isNull();
    Utils.Response response = Utils.write(client, KEY, "23");
    assertThat(response.isOk()).isTrue();
    assertThat(response.getHeaders()).isNotEmpty();
    assertThat(response.getHeaders().keySet().toString().equals("[term, leader, index]")).isTrue();
    response = client.getWithResponse(KEY);
    assertThat(response.getBody()).isEqualTo("23");
    assertThat(response.getHeaders()).isNotEmpty();
    assertThat(response.getHeaders().keySet().toString()).isEqualTo("[term, leader, index]");
  }

  @Test
  public void cas() throws Exception {
    Utils.write(client, KEY, "1");
    assertThat(client.get(KEY)).isEqualTo("1");
    Utils.Response response = client.cas(KEY, "1", "2");
    assertThat(response.isOk()).isTrue();
    assertThat(response.getHeaders()).isNotEmpty();
    assertThat(response.getHeaders().keySet().toString()).isEqualTo("[term, leader, index]");
    assertThat(client.get(KEY)).isEqualTo("2");
    response = client.cas(KEY, "1", "2");
    assertThat(response.getHeaders().keySet().toString()).isEqualTo("[term, leader, index]");
    assertThat(response.isOk()).isFalse();
    assertThat(client.get(KEY)).isEqualTo("2");
  }

  @Test
  public void casWithNull() throws Exception {
    Utils.Response response = client.cas(KEY, "2", "1");
    assertThat(response.isOk()).isFalse();
    response = client.cas(KEY, "", "1");
    assertThat(response.isOk()).isTrue();
    assertThat(response.getHeaders()).isNotEmpty();
    assertThat(response.getHeaders().keySet().toString()).isEqualTo("[term, leader, index]");
    assertThat(client.get(KEY)).isEqualTo("1");
    response = client.cas(KEY, "", "2");
    assertThat(response.isOk()).isFalse();
    assertThat(response.getHeaders()).isNotEmpty();
    assertThat(response.getHeaders().keySet().toString()).isEqualTo("[term, leader, index]");
    assertThat(client.get(KEY)).isEqualTo("1");
  }

  @Test
  public void addToSet() throws Exception {
    Utils.Client client = Utils.createClient("localhost");
    client.write(KEY, "");
    Utils.addToSet(client, KEY, "2");
    assertThat(Utils.get(client, KEY)).isEqualTo("2");
    Utils.addToSet(client, KEY, "2");
    assertThat(Utils.get(client, KEY)).isEqualTo("2");
    Utils.addToSet(client, KEY, "3");
    assertThat(Utils.get(client, KEY)).isEqualTo("2 3");
    Utils.addToSet(client, KEY, "3");
    assertThat(Utils.get(client, KEY)).isEqualTo("2 3");
    Utils.addToSet(client, KEY, "2");
    assertThat(Utils.get(client, KEY)).isEqualTo("2 3");
    Utils.addToSet(client, KEY, "1");
    assertThat(Utils.get(client, KEY)).isEqualTo("2 3 1");
  }

  @Test
  public void getSet() throws Exception {
    Utils.Client client = Utils.createClient("localhost");
    client.write(KEY, "");
    Utils.addToSet(client, KEY, "1");
    Utils.addToSet(client, KEY, "2");
    Utils.addToSet(client, KEY, "3");
    assertThat(client.getSet(KEY)).isEqualTo("#{1 2 3}");
  }

  /**
   * Test the addToSet logic. This is a simplified and local version of the set workload in the
   * Jepsen tests.
   *
   * @throws Exception
   */
  @Test
  public void addToSetWithCas() throws Exception {
    Utils.Client client = Utils.createClient("localhost");
    client.write(KEY, "");
    final int concurrency = 10;
    ExecutorService executorService = Executors.newFixedThreadPool(concurrency);
    List<String> values = new CopyOnWriteArrayList<>();
    IntStream.range(1, 100).forEach(i -> values.add(i + ""));
    Map<String, String> referenceMap = new ConcurrentHashMap<>();
    Random random = new Random();
    long start = System.currentTimeMillis();
    AtomicBoolean someResponseIsNull = new AtomicBoolean(false);
    while ((System.currentTimeMillis() - start) < 10_000) {
      IntStream.range(0, concurrency)
          .forEach(
              i -> {
                executorService.submit(
                    () -> {
                      String value = values.get(random.nextInt(values.size()));
                      referenceMap.put(value, "");
                      try {
                        Utils.Response response = client.addToSet(KEY, value);
                        if (response == null) {
                          someResponseIsNull.set(true);
                        }
                      } catch (Exception e) {
                        e.printStackTrace();
                      }
                    });
              });
      Thread.sleep(50L);
    }
    executorService.shutdown();
    assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(someResponseIsNull).isFalse();

    Set<String> referenceSet = referenceMap.keySet();

    List<Integer> setInDbAsList =
        asList(client.get(KEY).split(" ")).stream()
            .map(value -> Integer.parseInt(value))
            .sorted()
            .collect(Collectors.toList());
    Set<Integer> setInDb = new LinkedHashSet<>(setInDbAsList);
    assertThat(setInDb).hasSameSizeAs(setInDbAsList);
    List<Integer> referenceSetAsList =
        referenceSet.stream()
            .map(value -> Integer.parseInt(value))
            .sorted()
            .collect(Collectors.toList());
    Set<Integer> referenceSetWithInt = new LinkedHashSet<>(referenceSetAsList);

    LinkedHashSet<Integer> lost = new LinkedHashSet<>(referenceSetWithInt);
    lost.removeAll(setInDb);
    assertThat(lost).describedAs("There should be no lost values").isEmpty();
    assertThat(setInDb.toString()).isEqualTo(referenceSetWithInt.toString());
  }
}
