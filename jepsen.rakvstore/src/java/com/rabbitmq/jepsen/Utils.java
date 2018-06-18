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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 */
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

    static Object get(Map<Object, Object> map, String keyStringValue) {
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            if (keyStringValue.equals(entry.getKey().toString())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
