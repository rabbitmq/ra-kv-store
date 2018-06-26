/*
 * Copyright (c) 2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 *
 */
@SuppressWarnings("unchecked")
public class Utils {

    // static JepsenTestLog LOG = new DefaultJepsenTestLog();
    static JepsenTestLog LOG = new NoOpJepsenTestLog();

    public static String configuration(Map<Object, Object> test, Object currentNode) {
        List<Object> nodesObj = (List<Object>) get(test, ":nodes");
        String erlangNetTickTime = get(test, ":erlang-net-ticktime") != null ? erlangNetTickTime(test) : "";

        List<String> nodes = nodesObj.stream().map(o -> {
            String node = o.toString();
            String nodeIndex = node.substring(node.length() - 1, node.length());
            return String.format("{ra_kv%s, 'kv@%s'}", nodeIndex, node);
        }).collect(Collectors.toList());

        String node = currentNode.toString();
        String nodeIndex = node.substring(node.length() - 1, node.length());

        String configuration = String.format("[\n"
            + erlangNetTickTime
            + "    {ra, [{data_dir, \"/tmp/ra_kv_store\"}]},\n"
            + "    {ra_kv_store, [\n"
            + "        {port, 8080},\n"
            + "        {nodes, [%s]},\n"
            + "        {server_reference, ra_kv%s}\n"
            + "    ]}\n"
            + "].", String.join(", ", nodes), nodeIndex);

        return configuration;
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
        return "-sname kv\n"
            + "-setcookie ra_kv_store";
    }

    public static Client createClient(Object node) {
        return new Client(node.toString());
    }

    public static Object get(Client client, Object key) throws Exception {
        return client.get(key);
    }

    public static void write(Client client, Object key, Object value) throws Exception {
        client.write(key, value);
    }

    public static boolean cas(Client client, Object key, Object oldValue, Object newValue) throws Exception {
        return client.cas(key, oldValue, newValue);
    }

    public static void addToSet(Client client, Object key, Object value) throws Exception {
        client.addToSet(key, value);
    }

    public static String getSet(Client client, Object key) throws Exception {
        return client.getSet(key);
    }

    static Object get(Map<Object, Object> map, String keyStringValue) {
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            if (keyStringValue.equals(entry.getKey().toString())) {
                return entry.getValue();
            }
        }
        return null;
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
        public void step(RequestAttempt attempt, Supplier<String> message) throws InterruptedException {

        }

        @Override
        public void attempt(RequestAttempt attempt) {

        }

        @Override
        public void success(RequestAttempt attempt) {

        }

        @Override
        public void alreadyInSet(RequestAttempt attempt) {

        }

        @Override
        public CasRequest casRequest(Object node, Object expectedValue, Object newValue) {
            return null;
        }

        @Override
        public void statusCode(CasRequest request, int status) {

        }

        @Override
        public void dump() {

        }
    }

    public static class Client {

        private final String node;

        public Client(String node) {
            this.node = node;
        }

        String get(Object key) throws Exception {
            URL url = new URL(String.format("http://%s:8080/%s", this.node, key.toString()));
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                try {
                    return response(conn.getInputStream());
                } catch (FileNotFoundException e) {
                    return null;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        void write(Object key, Object value) throws Exception {
            URL url = new URL(String.format("http://%s:8080/%s", this.node, key.toString()));
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                try (OutputStreamWriter out = new OutputStreamWriter(conn.getOutputStream())) {
                    out.write("value=" + value.toString());
                }
                conn.getInputStream();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        boolean cas(Object key, Object oldValue, Object newValue) throws Exception {
            CasRequest request = LOG.casRequest(node, oldValue, newValue);
            URL url = new URL(String.format("http://%s:8080/%s", this.node, key.toString()));
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                try (OutputStreamWriter out = new OutputStreamWriter(conn.getOutputStream())) {
                    out.write(String.format("value=%s&expected=%s", newValue.toString(), oldValue.toString()));
                }
                conn.getInputStream();
                LOG.statusCode(request, conn.getResponseCode());
                return true;
            } catch (Exception e) {
                LOG.statusCode(request, conn.getResponseCode());
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        /**
         * Add a value to a set.
         * The uniqueness of the value in the set is enforced
         * thanks to the CAS operation (the initial value of the
         * set is checked when trying to update the set with the new
         * value).
         *
         * @param key
         * @param value
         * @throws Exception
         */
        public void addToSet(Object key, Object value) throws Exception {
            RequestAttempt requestAttempt = LOG.requestAttempt(node, value);
            retry(() -> {
                LOG.step(requestAttempt, () -> "in retry loop");
                // currentValue is ""
                String currentValue = get(key);
                if (currentValue == null || currentValue.isEmpty()) {
                    LOG.step(requestAttempt, () -> "no value in the set");
                    LOG.attempt(requestAttempt);
                    LOG.step(requestAttempt, () -> "sending cas operation for empty set");
                    boolean result = cas(key.toString(), "", value.toString());
                    LOG.step(requestAttempt, () -> "cas operation returned " + result + " for empty set");
                    if (result) {
                        LOG.success(requestAttempt);
                    }
                    LOG.step(requestAttempt, () -> "returning " + result);
                    return result;
                }
                // currentValue is "1 2 3 4 5"
                String valueAsString = value.toString();
                LOG.step(requestAttempt, () -> "checking value to add is not already in the set");
                for (String valueInSet : currentValue.split(" ")) {
                    if (valueAsString.equals(valueInSet)) {
                        LOG.alreadyInSet(requestAttempt);
                        LOG.step(requestAttempt, () -> "value already in the set, returning true");
                        // already in the set, nothing to do
                        return true;
                    }
                }
                LOG.attempt(requestAttempt);
                LOG.step(requestAttempt, () -> "value not already in the set");
                LOG.step(requestAttempt, () -> "sending cas option");
                boolean result = cas(key, currentValue, currentValue + " " + valueAsString);
                LOG.step(requestAttempt, () -> "cas operation returned " + result);
                if (result) {
                    LOG.success(requestAttempt);
                }
                LOG.step(requestAttempt, () -> "returning " + result);
                return result;
            });
        }

        private void retry(Callable<Boolean> operation) throws Exception {
            boolean done = operation.call();
            while (!done) {
                Thread.sleep(
                    100); // same as in https://github.com/aphyr/verschlimmbesserung/blob/498fd20ca39c52f6f4506dc281d6af9920791342/src/verschlimmbesserung/core.clj#L41-L44
                done = operation.call();
            }
        }

        private String response(InputStream inputStream) throws IOException {
            StringBuilder content = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                new InputStreamReader(inputStream))) {
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
            }
            return content.toString();
        }

        /**
         * Return value wrapped in <code>#{ }</code>.
         * This allows parsing it as a Clojure set.
         *
         * @param key
         * @return
         * @throws Exception
         */
        public String getSet(Object key) throws Exception {
            LOG.dump();
            return "#{" + get(key) + "}";
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
            return "RequestAttempt{" +
                "node='" + node + '\'' +
                ", value='" + value + '\'' +
                ", attempts=" + attempts +
                ", successes=" + successes +
                ", alreadyInSet=" + alreadyInSet +
                ", steps=" + steps +
                '}';
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
            CasRequest request = new CasRequest(node.toString(), expectedValue.toString(), newValue.toString());
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
            return "CasRequest{" +
                "node='" + node + '\'' +
                ", expectedValue='" + expectedValue + '\'' +
                ", newValue='" + newValue + '\'' +
                ", statusCode=" + statusCode +
                '}';
        }
    }
}
