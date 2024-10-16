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

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** */
@SuppressWarnings("unchecked")
public class Utils {

  private static final int HTTP_REQUEST_TIMEOUT = 600_000;

  private static final Logger LOGGER = LoggerFactory.getLogger("jepsen.utils.client");

  // static JepsenTestLog LOG = new DefaultJepsenTestLog();
  static JepsenTestLog LOG = new NoOpJepsenTestLog();

  private static final Map<String, String> NODE_TO_ERLANG_NODE = new ConcurrentHashMap<>();

  public static String configuration(Map<Object, Object> test, Object currentNode) {
    List<Object> nodesObj = (List<Object>) get(test, ":nodes");
    String erlangNetTickTime =
        get(test, ":erlang-net-ticktime") != null ? erlangNetTickTime(test) : "";
    Object releaseCursorEvery = get(test, ":release-cursor-every");
    releaseCursorEvery =
        releaseCursorEvery == null ? -1L : Long.parseLong(releaseCursorEvery.toString());
    Object walMaxSizeBytes = get(test, ":wal-max-size-bytes");
    Long megaBytes128 = 134217728L;
    walMaxSizeBytes =
        walMaxSizeBytes == null ? megaBytes128 : Long.parseLong(walMaxSizeBytes.toString());

    List<String> nodes = nodesObj.stream().map(Object::toString).sorted().collect(Collectors.toList());
    String node = currentNode.toString();
    String nodeIndex = String.valueOf(nodes.indexOf(node) + 1);
    List<String> erlangNodes = nodesObj.stream().map(n -> raNodeId(n, nodes.indexOf(n.toString()))).collect(Collectors.toList());

    String configuration =
        String.format(
            "[\n"
                + erlangNetTickTime
                + "    {ra, [\n"
                + "        {data_dir, \"/tmp/ra_kv_store\"},\n"
                + "        {wal_max_size_bytes, %d}\n"
                + "    ]},\n"
                + "    {ra_kv_store, [\n"
                + "        {port, 8080},\n"
                + "        {nodes, [%s]},\n"
                + "        {server_reference, ra_kv%s},\n"
                + "        {release_cursor_every, %d}\n"
                + "    ]}\n"
                + "].",
            walMaxSizeBytes,
            String.join(", ", erlangNodes),
            nodeIndex,
            releaseCursorEvery);

    return configuration;
  }

  public static String raNodeId(Object n, int index) {
    String node = n.toString();
    if (index == -1) {
      // we don't have enough context, but the node name should have been computed already
      return NODE_TO_ERLANG_NODE.get(node);
    } else {
      String erlangNode =  String.format("{ra_kv%d, 'kv@%s'}", index + 1, node);
      NODE_TO_ERLANG_NODE.putIfAbsent(node, erlangNode);
      return erlangNode;
    }
  }

  static String erlangNetTickTime(Map<Object, Object> test) {
    Object valueObj = get(test, ":erlang-net-ticktime");
    String value = valueObj == null ? "" : valueObj.toString();
    long tickTime = Long.parseLong(value);
    if (tickTime >= 0) {
      return String.format("{kernel, [{net_ticktime,  %d}]},\n", tickTime);
    } else {
      return "";
    }
  }

  public static String vmArgs() {
    return "-sname kv\n" + "-setcookie ra_kv_store";
  }

  public static Client createClient(Object node) {
    return new Client(node.toString());
  }

  public static Object get(Client client, Object key) throws Exception {
    return client.get(key);
  }

  public static Response write(Client client, Object key, Object value) throws Exception {
    return client.write(key, value);
  }

  public static Response cas(Client client, Object key, Object oldValue, Object newValue)
      throws Exception {
    return client.cas(key, oldValue, newValue);
  }

  public static Response addToSet(Client client, Object key, Object value) throws Exception {
    return client.addToSet(key, value);
  }

  public static String getSet(Client client, Object key) throws Exception {
    try {
      return client.getSet(key);
    } catch (Exception e) {
      LOGGER.warn("Error while getting set on node {}", client.node, e);
      throw e;
    }
  }

  public static String node(Client client) {
    return client.node;
  }

  static Object get(Map<Object, Object> map, String keyStringValue) {
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      if (keyStringValue.equals(entry.getKey().toString())) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static Map<String, String> raHeaders(HttpURLConnection c) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : c.getHeaderFields().entrySet()) {
      if (entry.getKey() != null && entry.getKey().startsWith("ra_")) {
        headers.put(entry.getKey().replaceFirst("ra_", ""), String.join(",", entry.getValue()));
      }
    }
    return headers;
  }

  private static void logGetResponse(String node, Object key, Response<String> response) {
    try {
      LOGGER.info("read [" + key + " " + response.getBody() + "] from " + node + " " + response.getHeaders());
    } catch (Exception e) {
      LOGGER.warn("Error while logging RA headers: " + e.getMessage());
    }
  }

  interface JepsenTestLog {

    RequestAttempt requestAttempt(Object node, Object value);

    void step(RequestAttempt attempt, Supplier<String> message) throws InterruptedException;

    void attempt(RequestAttempt attempt);

    void success(RequestAttempt attempt);

    void alreadyInSet(RequestAttempt attempt);

    CasRequest casRequest(Object node, Object expectedValue, Object newValue);

    void statusCode(CasRequest request, int status);

    void dump();
  }

  static class NoOpJepsenTestLog implements JepsenTestLog {

    @Override
    public RequestAttempt requestAttempt(Object node, Object value) {
      return null;
    }

    @Override
    public void step(RequestAttempt attempt, Supplier<String> message)
        throws InterruptedException {}

    @Override
    public void attempt(RequestAttempt attempt) {}

    @Override
    public void success(RequestAttempt attempt) {}

    @Override
    public void alreadyInSet(RequestAttempt attempt) {}

    @Override
    public CasRequest casRequest(Object node, Object expectedValue, Object newValue) {
      return null;
    }

    @Override
    public void statusCode(CasRequest request, int status) {}

    @Override
    public void dump() {}
  }

  public static class Client {

    private final String node;

    public Client(String node) {
      this.node = node;
    }

    <V> V request(Callable<V> call) throws Exception {
      try {
        return call.call();
      } catch (ConnectException e) {
        throw new RaNodeDownException();
      }
    }

    private Response<String> get(Object key, boolean log) throws Exception {
      return request(
          () -> {
            URL url = new URI(String.format("http://%s:8080/%s", this.node, key.toString())).toURL();
            HttpURLConnection conn = null;
            try {
              conn = (HttpURLConnection) url.openConnection();
              conn.setRequestMethod("GET");
              conn.setConnectTimeout(HTTP_REQUEST_TIMEOUT);
              conn.setReadTimeout(HTTP_REQUEST_TIMEOUT);
              try {
                String body = body(conn.getInputStream());
                Response<String> response = new Response<>(true, raHeaders(conn), body);
                if (log) {
                  logGetResponse(node, key, response);
                }
                return response;
              } catch (FileNotFoundException e) {
                LOGGER.warn("FileNotFoundException on set GET operation: {}", e.getMessage());
                return null;
              } catch (Exception e) {
                int responseCode = conn.getResponseCode();
                String responseBody = body(conn.getErrorStream());
                if (responseCode == 503 && "RA timeout".equals(responseBody)) {
                  throw new RaTimeoutException(raHeaders(conn));
                } else {
                  throw e;
                }
              }
            } finally {
              if (conn != null) {
                conn.disconnect();
              }
            }
          });
    }

    String get(Object key) throws Exception {
      Response<String> response = getWithResponse(key);
      return response == null ? null : response.getBody();
    }

    Response<String> getWithResponse(Object key) throws Exception {
      return get(key, true);
    }

    Response write(Object key, Object value) throws Exception {
      return request(
          () -> {
            URL url = new URI(String.format("http://%s:8080/%s", this.node, key.toString())).toURL();
            HttpURLConnection conn = null;
            Response response = null;
            try {
              conn = (HttpURLConnection) url.openConnection();
              conn.setRequestMethod("PUT");
              conn.setDoOutput(true);
              conn.setConnectTimeout(HTTP_REQUEST_TIMEOUT);
              conn.setReadTimeout(HTTP_REQUEST_TIMEOUT);
              try (OutputStreamWriter out = new OutputStreamWriter(conn.getOutputStream())) {
                out.write("value=" + value.toString());
              }
              conn.getInputStream();
              response = new Response<Void>(true, raHeaders(conn));
            } catch (Exception e) {
              int responseCode = conn.getResponseCode();
              String responseBody = body(conn.getErrorStream());
              if (responseCode == 503 && "RA timeout".equals(responseBody)) {
                throw new RaTimeoutException(raHeaders(conn));
              } else {
                throw e;
              }
            } finally {
              if (conn != null) {
                conn.disconnect();
              }
            }
            return response;
          });
    }

    Response cas(Object key, Object oldValue, Object newValue) throws Exception {
      return request(
          () -> {
            CasRequest request = LOG.casRequest(node, oldValue, newValue);
            URL url = new URI(String.format("http://%s:8080/%s", this.node, key.toString())).toURL();
            HttpURLConnection conn = null;
            try {
              conn = (HttpURLConnection) url.openConnection();
              conn.setRequestMethod("PUT");
              conn.setDoOutput(true);
              conn.setConnectTimeout(HTTP_REQUEST_TIMEOUT);
              conn.setReadTimeout(HTTP_REQUEST_TIMEOUT);
              try (OutputStreamWriter out = new OutputStreamWriter(conn.getOutputStream())) {
                out.write(
                    String.format(
                        "value=%s&expected=%s", newValue.toString(), oldValue.toString()));
              }
              conn.getInputStream();
              LOG.statusCode(request, conn.getResponseCode());
              return new Response<Void>(true, raHeaders(conn));
            } catch (Exception e) {
              int responseCode = conn.getResponseCode();
              String responseBody = body(conn.getErrorStream());
              LOG.statusCode(request, responseCode);
              if (responseCode == 409) {
                return new Response<Void>(false, raHeaders(conn));
              } else if (responseCode == 503 && "RA timeout".equals(responseBody)) {
                throw new RaTimeoutException(raHeaders(conn));
              } else {
                throw e;
              }
            } finally {
              if (conn != null) {
                conn.disconnect();
              }
              LOG.dump();
            }
          });
    }

    /**
     * Add a value to a set. The uniqueness of the value in the set is enforced thanks to the CAS
     * operation (the initial value of the set is checked when trying to update the set with the new
     * value).
     *
     * @param key
     * @param value
     * @throws Exception
     */
    public Response addToSet(Object key, Object value) throws Exception {
      AtomicReference<Response> response = new AtomicReference<>();
      request(
          () -> {
            RequestAttempt requestAttempt = LOG.requestAttempt(node, value);
            final AtomicBoolean result = new AtomicBoolean();
            retry(
                () -> {
                  LOG.step(requestAttempt, () -> "in retry loop");
                  // currentValue is ""
                  Response<String> currentResponse = get(key, false);
                  String currentValue = currentResponse == null ? null : currentResponse.getBody();
                  if (currentValue == null || currentValue.isEmpty()) {
                    LOG.step(requestAttempt, () -> "no value in the set");
                    LOG.attempt(requestAttempt);
                    LOG.step(requestAttempt, () -> "sending cas operation for empty set");
                    try {
                      Response casResponse = cas(key.toString(), "", value.toString());
                      result.set(casResponse.isOk());
                      response.set(casResponse);
                    } catch (RaTimeoutException e) {
                      LOG.step(
                          requestAttempt,
                          () -> "cas operation timed out, result isn't indeterminate");
                      throw e;
                    }
                    LOG.step(
                        requestAttempt,
                        () -> "cas operation returned " + result + " for empty set");
                    if (result.get()) {
                      LOG.success(requestAttempt);
                    }
                    LOG.step(requestAttempt, () -> "returning " + result.get());
                    return result.get();
                  }
                  // currentValue is "1 2 3 4 5"
                  String valueAsString = value.toString();
                  LOG.step(requestAttempt, () -> "checking value to add is not already in the set");
                  for (String valueInSet : currentValue.split(" ")) {
                    if (valueAsString.equals(valueInSet)) {
                      LOG.alreadyInSet(requestAttempt);
                      LOG.step(requestAttempt, () -> "value already in the set, returning true");
                      // already in the set, nothing to do
                      Response casResponse = new Response<Void>(true, Collections.emptyMap());
                      result.set(casResponse.isOk());
                      response.set(casResponse);
                      return result.get();
                    }
                  }
                  LOG.attempt(requestAttempt);
                  LOG.step(requestAttempt, () -> "value not already in the set");
                  LOG.step(requestAttempt, () -> "sending cas option");
                  try {
                    Response casResponse =
                        cas(key, currentValue, currentValue + " " + valueAsString);
                    result.set(casResponse.isOk());
                    response.set(casResponse);
                  } catch (RaTimeoutException e) {
                    LOG.step(
                        requestAttempt,
                        () -> "cas operation timed out, result isn't indeterminate");
                    throw e;
                  }
                  LOG.step(requestAttempt, () -> "cas operation returned " + result.get());
                  if (result.get()) {
                    LOG.success(requestAttempt);
                  }
                  LOG.step(requestAttempt, () -> "returning " + result.get());
                  return result.get();
                });
            return null;
          });
      return response.get();
    }

    private void retry(Callable<Boolean> operation) throws Exception {
      boolean done = operation.call();
      while (!done) {
        Thread.sleep(
            100); // same as in
                  // https://github.com/aphyr/verschlimmbesserung/blob/498fd20ca39c52f6f4506dc281d6af9920791342/src/verschlimmbesserung/core.clj#L41-L44
        done = operation.call();
      }
    }

    private <T> T retry(Callable<T> operation, Predicate<Exception> retryPredicate) throws Exception {
      int attemptCount = 1;
      while (attemptCount <= 3) {
        try {
          return operation.call();
        } catch (Exception e) {
          if (retryPredicate.test(e)) {
            attemptCount++;
            LOGGER.info("Operation failed with '{}' exception, retrying...", e.getClass().getSimpleName());
            Thread.sleep(1000);
          } else {
            throw e;
          }
        }
      }
      throw new RuntimeException("Operation failed after " + (attemptCount - 1) + " attempts");
    }

    private String body(InputStream inputStream) throws IOException {
      if (inputStream != null) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(inputStream))) {
          String inputLine;
          while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
          }
        }
        return content.toString();
      } else {
        return "";
      }
    }

    /**
     * Return value wrapped in <code>#{ }</code>. This allows parsing it as a Clojure set.
     *
     * @param key
     * @return
     * @throws Exception
     */
    public String getSet(Object key) throws Exception {
      LOG.dump();
      return retry(() -> "#{" + get(key, true).getBody() + "}", e -> e instanceof RaTimeoutException);
    }
  }

  static class RequestAttempt {

    final String node;
    final String value;
    final AtomicLong attempts = new AtomicLong(0);
    final AtomicLong successes = new AtomicLong(0);
    final AtomicLong alreadyInSet = new AtomicLong(0);
    final BlockingQueue<String> steps = new ArrayBlockingQueue<>(100);

    RequestAttempt(String node, String value) {
      this.node = node;
      this.value = value;
    }

    void markAttempt() {
      this.attempts.incrementAndGet();
    }

    void markSuccess() {
      this.successes.incrementAndGet();
    }

    void markAlreadyInSet() {
      this.alreadyInSet.incrementAndGet();
    }

    void step(String step) throws InterruptedException {
      this.steps.offer(step, 1, TimeUnit.SECONDS);
    }

    @Override
    public String toString() {
      return "RequestAttempt{"
          + "node='"
          + node
          + '\''
          + ", value='"
          + value
          + '\''
          + ", attempts="
          + attempts
          + ", successes="
          + successes
          + ", alreadyInSet="
          + alreadyInSet
          + ", steps="
          + steps
          + '}';
    }
  }

  static class DefaultJepsenTestLog implements JepsenTestLog {

    // increase the capacity of those queues for long tests, when log is enabled
    final BlockingQueue<RequestAttempt> attempts = new ArrayBlockingQueue<>(100_000);
    final BlockingQueue<CasRequest> casRequests = new ArrayBlockingQueue<>(100_000);

    @Override
    public RequestAttempt requestAttempt(Object node, Object value) {
      RequestAttempt requestAttempt = new RequestAttempt(node.toString(), value.toString());
      attempts.add(requestAttempt);
      return requestAttempt;
    }

    @Override
    public void step(RequestAttempt attempt, Supplier<String> message) throws InterruptedException {
      attempt.step(message.get());
    }

    @Override
    public void attempt(RequestAttempt attempt) {
      attempt.markAttempt();
    }

    @Override
    public void success(RequestAttempt attempt) {
      attempt.markSuccess();
    }

    @Override
    public void alreadyInSet(RequestAttempt attempt) {
      attempt.markAlreadyInSet();
    }

    @Override
    public CasRequest casRequest(Object node, Object expectedValue, Object newValue) {
      CasRequest request =
          new CasRequest(node.toString(), expectedValue.toString(), newValue.toString());
      casRequests.add(request);
      return request;
    }

    @Override
    public void statusCode(CasRequest request, int status) {
      request.statusCode(status);
    }

    @Override
    public void dump() {
      System.out.println("REQUESTS: " + attempts);
      System.out.println("CAS: " + casRequests);
    }
  }

  static class CasRequest {

    private final String node, expectedValue, newValue;

    private final AtomicInteger statusCode = new AtomicInteger(-1);

    CasRequest(String node, String expectedValue, String newValue) {
      this.node = node;
      this.expectedValue = expectedValue;
      this.newValue = newValue;
    }

    void statusCode(int status) {
      statusCode.set(status);
    }

    @Override
    public String toString() {
      return "CasRequest{"
          + "node='"
          + node
          + '\''
          + ", expectedValue='"
          + expectedValue
          + '\''
          + ", newValue='"
          + newValue
          + '\''
          + ", statusCode="
          + statusCode
          + '}';
    }
  }

  public static class Response<T> {

    private final T body;
    private final Map<String, String> headers;
    private final boolean ok;

    public Response(boolean ok, Map<String, String> headers) {
      this(ok, headers, null);
    }

    public Response(boolean ok, Map<String, String> headers, T body) {
      this.headers = headers;
      this.ok = ok;
      this.body = body;
    }

    public T getBody() {
      return body;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    public boolean isOk() {
      return ok;
    }

    @Override
    public String toString() {
      return "Response{" + "body=" + body + ", headers=" + headers + ", ok=" + ok + '}';
    }
  }
}
