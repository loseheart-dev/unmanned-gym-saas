package com.hiking.gymsaas.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class JsonSnapshots {
    private static final Set<String> SECRETS = Set.of("password", "passwordhash", "oldpassword", "newpassword",
            "accesstoken", "refreshtoken", "token", "authorization", "secret", "secretciphertext", "appsecret",
            "sessionkey", "openid", "unionid", "code", "phonecode", "phone", "phoneciphertext", "objectkey", "playbackurl");
    private final ObjectMapper mapper;
    public JsonSnapshots(ObjectMapper mapper) { this.mapper = mapper; }

    public String canonical(Object value) { return sort(mapper.valueToTree(value)).toString(); }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), sort(e.getValue())));
            ObjectNode object = mapper.createObjectNode();
            sorted.forEach(object::set);
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            node.forEach(n -> array.add(sort(n)));
            return array;
        }
        return node;
    }

    public JsonNode replayable(Object value) {
        JsonNode node = mapper.valueToTree(value);
        check(node);
        if (node.toString().length() > 65536) throw new IllegalArgumentException("Idempotency snapshot exceeds 64K characters");
        return node;
    }

    private void check(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            if (secret(e.getKey())) throw new IllegalArgumentException("Sensitive responses cannot use replay snapshots");
            check(e.getValue());
        });
        else if (node.isArray()) node.forEach(this::check);
    }

    public JsonNode redact(Object value) {
        JsonNode node = mapper.valueToTree(value);
        redactNode(node);
        return node;
    }

    private void redactNode(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            if (secret(e.getKey())) ((ObjectNode) node).put(e.getKey(), "[REDACTED]");
            else redactNode(e.getValue());
        });
        else if (node.isArray()) node.forEach(this::redactNode);
    }

    private static boolean secret(String key) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return SECRETS.contains(normalized) || normalized.endsWith("token") || normalized.contains("password")
                || normalized.contains("secret") || normalized.contains("credential");
    }
}
