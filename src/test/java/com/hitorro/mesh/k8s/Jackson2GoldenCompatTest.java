/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.k8s;

import com.hitorro.mesh.orion.ClusterManager;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.util.*;

/**
 * Pins the Kubernetes path to 3.0.2: PodLists as the API server sends them (annotations with
 * messy capability lists, missing nodeName, unknown fields, non-ASCII, empty lists) go through the
 * fabric8 client's own Jackson 2 serialization and {@link KubernetesClusterManager#toDeclared}. The
 * test checks the declared agents and the re-serialized JSON. The re-serialization guards the
 * Jackson 2 pin: fabric8 6.13.4 on Jackson 2.19+ fails to serialize a resource with unknown fields
 * (see the comment in pom.xml). {@code jackson2-golden.txt} was produced by running the same
 * program against the 3.0.2 build.
 */
class Jackson2GoldenCompatTest {
  static String pod(String name, String caps, String node, String extra) {
    return "{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"name\":\"" + name + "\",\"namespace\":\"mesh\","
        + "\"creationTimestamp\":\"2026-11-20T10:00:00Z\",\"labels\":{\"app\":\"hitorro-mesh\",\"role\":\"agent\"},"
        + "\"annotations\":" + caps + ",\"resourceVersion\":\"12345\",\"uid\":\"0a1b\"},"
        + "\"spec\":{" + (node == null ? "" : "\"nodeName\":\"" + node + "\",") + "\"containers\":[{\"name\":\"agent\",\"image\":\"hitorro/mesh-agent:4.0.0\","
        + "\"ports\":[{\"containerPort\":7070,\"protocol\":\"TCP\"}],\"resources\":{\"limits\":{\"memory\":\"2Gi\",\"cpu\":\"1500m\"}}}]},"
        + "\"status\":{\"phase\":\"Running\",\"startTime\":\"2026-11-20T10:00:05Z\",\"podIP\":\"10.0.0.7\"" + extra + "}}";
  }
  static final String[] LISTS = {
    "{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"metadata\":{\"resourceVersion\":\"99\"},\"items\":["
      + pod("hitorro-agent-shard-3", "{\"hitorro.mesh/capabilities\":\"jvssql,partition:docs:shard-3\"}", "node1", "") + ","
      + pod("hitorro-agent-shard-4", "{\"hitorro.mesh/capabilities\":\" jvssql , partition:docs:shard-4,,arch:arm64 \"}", null, ",\"unknownField\":{\"x\":[1]}") + ","
      + pod("bare", "{}", "node2", "") + ","
      + pod("é-pod", "{\"hitorro.mesh/capabilities\":\"\"}", "né", ",\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\",\"lastTransitionTime\":\"2026-11-20T10:00:06Z\"}]") + "]}",
    "{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"items\":[]}",
    "{\"apiVersion\":\"v1\",\"kind\":\"PodList\"}"
  };

  static String generate() throws Exception {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < LISTS.length; i++) {
      String v;
      try {
        PodList pl = Serialization.unmarshal(LISTS[i], PodList.class);
        List<String> agents = new ArrayList<>();
        for (ClusterManager.DeclaredAgent a : KubernetesClusterManager.toDeclared(pl))
          agents.add(a.name() + "|" + new TreeSet<>(a.declaredCapabilities()) + "|" + a.nodeName());
        Collections.sort(agents);
        v = String.join(" ; ", agents);
        out.append("list").append(i).append(".declared=").append(v).append('\n');
        out.append("list").append(i).append(".reserialized=").append(Serialization.asJson(pl)).append('\n');
      } catch (Throwable t) {
        out.append("list").append(i).append(".error=!").append(t.getClass().getSimpleName()).append('\n');
      }
    }
    return out.toString();
  }

  @org.junit.jupiter.api.Test
  void matchesJackson2() throws Exception {
    java.util.Map<String, String> golden = new java.util.LinkedHashMap<>();
    try (java.io.InputStream in = getClass().getResourceAsStream("jackson2-golden.txt")) {
      for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        int eq = line.indexOf('=');
        if (eq > 0) golden.put(line.substring(0, eq), line.substring(eq + 1));
      }
    }
    java.util.Map<String, String> actual = new java.util.LinkedHashMap<>();
    for (String line : generate().split("\n")) {
      int eq = line.indexOf('=');
      if (eq > 0) actual.put(line.substring(0, eq), line.substring(eq + 1));
    }
    java.util.List<String> diffs = new java.util.ArrayList<>();
    for (String k : golden.keySet())
      if (!java.util.Objects.equals(golden.get(k), actual.get(k))) diffs.add(k + ": jackson2=" + golden.get(k) + " now=" + actual.get(k));
    for (String k : actual.keySet()) if (!golden.containsKey(k)) diffs.add(k + " (not in golden)");
    org.junit.jupiter.api.Assertions.assertTrue(diffs.isEmpty(), diffs.size() + " differences:\n" + String.join("\n", diffs));
    org.junit.jupiter.api.Assertions.assertEquals(6, golden.size());
  }
}
