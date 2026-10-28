package com.github.petergamez.trackcache.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Sanitizes and validates Lavalink v4 LoadResult JSON payloads and individual Track nodes.
 * Ensures compatibility between Jackson tree models and kotlinx.serialization structures.
 */
@Component
public class LoadResultSanitizer {

    private final ObjectMapper objectMapper;
    private final UrlNormalizer normalizer;

    public LoadResultSanitizer(ObjectMapper objectMapper, UrlNormalizer normalizer) {
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
    }

    public boolean isTrackNodeCacheable(JsonNode trackNode) {
        if (trackNode == null || !trackNode.isObject()) {
            return false;
        }
        JsonNode infoNode = trackNode.get("info");
        if (infoNode == null || !infoNode.isObject()) {
            return false;
        }

        // Live streams must never be cached
        if (infoNode.hasNonNull("isStream") && infoNode.get("isStream").asBoolean(false)) {
            return false;
        }

        // Direct HTTP or local source tracks must never be cached
        String sourceName = getNonEmptyText(infoNode, "sourceName");
        if ("http".equalsIgnoreCase(sourceName) || "local".equalsIgnoreCase(sourceName)) {
            return false;
        }

        // Validate URI: reject generic HTTP/HTTPS URLs
        String uri = getNonEmptyText(infoNode, "uri");
        if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://")) && !normalizer.isCacheable(uri)) {
            return false;
        }

        return true;
    }

    public void sanitizeLoadResultNode(ObjectNode objectNode) {
        if (objectNode == null) {
            return;
        }

        // 1. Normalize loadType to Lavalink v4 lowercase specification
        String loadType = "";
        if (objectNode.hasNonNull("loadType")) {
            String rawType = objectNode.get("loadType").asText().trim();
            loadType = "NONE".equalsIgnoreCase(rawType) ? "empty" : rawType.toLowerCase(Locale.ROOT);
            objectNode.put("loadType", loadType);
        }

        // 2. Normalize payload structure according to Lavalink v4
        if ("search".equals(loadType)) {
            if (objectNode.has("data")) {
                JsonNode dataNode = objectNode.get("data");
                if (dataNode.isObject() && dataNode.has("tracks") && dataNode.get("tracks").isArray()) {
                    ArrayNode tracksArray = (ArrayNode) dataNode.get("tracks");
                    for (JsonNode track : tracksArray) {
                        sanitizeTrackNode(track);
                    }
                    objectNode.set("data", tracksArray);
                } else if (dataNode.isArray()) {
                    for (JsonNode track : dataNode) {
                        sanitizeTrackNode(track);
                    }
                }
            } else {
                objectNode.set("data", objectMapper.createArrayNode());
            }
        } else if ("track".equals(loadType)) {
            if (objectNode.has("data")) {
                sanitizeTrackNode(objectNode.get("data"));
            }
        } else if ("playlist".equals(loadType)) {
            if (objectNode.has("data") && objectNode.get("data").isObject()) {
                ObjectNode playlistData = (ObjectNode) objectNode.get("data");
                sanitizeJsonObjectField(playlistData, "pluginInfo");
                if (playlistData.has("tracks") && playlistData.get("tracks").isArray()) {
                    for (JsonNode track : playlistData.get("tracks")) {
                        sanitizeTrackNode(track);
                    }
                }
            }
        } else if ("empty".equals(loadType)) {
            if (!objectNode.has("data") || objectNode.get("data").isNull()) {
                objectNode.set("data", objectMapper.createObjectNode());
            }
        }
    }

    public void sanitizeTrackNode(JsonNode trackNode) {
        if (trackNode == null || !trackNode.isObject()) {
            return;
        }
        ObjectNode track = (ObjectNode) trackNode;

        if (track.has("info") && track.get("info").isObject()) {
            ObjectNode info = (ObjectNode) track.get("info");

            if (info.has("seekable")) {
                if (!info.has("isSeekable")) {
                    info.set("isSeekable", info.get("seekable"));
                }
                info.remove("seekable");
            }

            if (info.has("stream")) {
                if (!info.has("isStream")) {
                    info.set("isStream", info.get("stream"));
                }
                info.remove("stream");
            }

            if (!info.has("isSeekable")) {
                boolean isStream = info.has("isStream") && info.get("isStream").asBoolean(false);
                info.put("isSeekable", !isStream);
            }

            if (!info.has("isStream")) {
                info.put("isStream", false);
            }

            if (!info.has("position")) {
                info.put("position", 0);
            }
        }

        sanitizeJsonObjectField(track, "pluginInfo");
        sanitizeJsonObjectField(track, "userData");
    }

    public String getNonEmptyText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String text = node.get(field).asText().trim();
        return (text.isEmpty() || "null".equalsIgnoreCase(text)) ? null : text;
    }

    private void sanitizeJsonObjectField(ObjectNode parent, String fieldName) {
        if (!parent.has(fieldName) || parent.get(fieldName).isNull()) {
            parent.set(fieldName, objectMapper.createObjectNode());
        } else {
            parent.set(fieldName, sanitizeKotlinJsonElement(parent.get(fieldName)));
        }
    }

    /**
     * Unwraps kotlinx.serialization.json.JsonPrimitive objects that were serialized via Jackson
     * reflection as {"string": boolean, "content": "..."} back into native JSON primitives.
     */
    private JsonNode sanitizeKotlinJsonElement(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node instanceof ObjectNode obj) {
            if (obj.size() == 2 && obj.has("string") && obj.has("content")
                    && obj.get("string").isBoolean()
                    && (obj.get("content").isTextual() || obj.get("content").isNull())) {
                boolean isString = obj.get("string").asBoolean();
                String content = obj.get("content").isNull() ? "null" : obj.get("content").asText();
                if (isString) {
                    return objectMapper.getNodeFactory().textNode(content);
                }
                if ("null".equalsIgnoreCase(content)) {
                    return objectMapper.nullNode();
                }
                if ("true".equalsIgnoreCase(content)) {
                    return objectMapper.getNodeFactory().booleanNode(true);
                }
                if ("false".equalsIgnoreCase(content)) {
                    return objectMapper.getNodeFactory().booleanNode(false);
                }
                try {
                    if (content.contains(".") || content.contains("e") || content.contains("E")) {
                        return objectMapper.getNodeFactory().numberNode(Double.parseDouble(content));
                    }
                    return objectMapper.getNodeFactory().numberNode(Long.parseLong(content));
                } catch (NumberFormatException ignored) {
                    return objectMapper.getNodeFactory().textNode(content);
                }
            }
            ObjectNode cleaned = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                cleaned.set(entry.getKey(), sanitizeKotlinJsonElement(entry.getValue()));
            }
            return cleaned;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode cleaned = objectMapper.createArrayNode();
            for (JsonNode elem : arr) {
                cleaned.add(sanitizeKotlinJsonElement(elem));
            }
            return cleaned;
        }
        return node;
    }
}

