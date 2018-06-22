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
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 *
 */
@SuppressWarnings("unchecked")
public class Utils {

    public static String configuration(Map<Object, Object> test, Object currentNode) {
        List<Object> nodesObj = (List<Object>) get(test, ":nodes");
        List<String> nodes = nodesObj.stream().map(o -> {
            String node = o.toString();
            String nodeIndex = node.substring(node.length() - 1, node.length());
            return String.format("{ra_kv%s, 'kv@%s'}", nodeIndex, node);
        }).collect(Collectors.toList());

        String node = currentNode.toString();
        String nodeIndex = node.substring(node.length() - 1, node.length());

        String configuration = String.format("[\n"
            + "    {ra, [{data_dir, \"/tmp/ra_kv_store\"}]},\n"
            + "    {ra_kv_store, [\n"
            + "        {port, 8080},\n"
            + "        {nodes, [%s]},\n"
            + "        {server_reference, ra_kv%s}\n"
            + "    ]}\n"
            + "].", String.join(", ", nodes), nodeIndex);

        return configuration;
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
                return true;
            } catch (Exception e) {
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        public void addToSet(Object key, Object value) throws Exception {
            retry(() -> {
                String currentValue = get(key);
                if (currentValue == null || currentValue.isEmpty()) {
                    return cas(key.toString(), "", value.toString());
                }
                String valueAsString = value.toString();
                for (String valueInSet : currentValue.split(" ")) {
                    if (valueAsString.equals(valueInSet)) {
                        // already in the set, nothing to do
                        return true;
                    }
                }
                return cas(key, currentValue, currentValue + " " + valueAsString);
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

        public String getSet(Object key) throws Exception {
            return "#{" + get(key) + "}";
        }
    }
}
