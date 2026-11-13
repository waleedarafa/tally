package com.tally.architecture;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks a JSON document against the part of JSON Schema (draft 2020-12) that the contract files in
 * {@code contracts/} use (ADR-034: no new framework; Tally writes its own small checks). A schema keyword outside
 * this part is refused, so a schema can never rely on a rule that is not checked.
 */
final class JsonSchemaSubset {

    /** The keywords this checker understands. */
    static final Set<String> KEYWORDS = Set.of("$schema", "title", "description", "type", "additionalProperties",
            "required", "properties", "items", "enum", "format", "minimum");

    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private JsonSchemaSubset() {
    }

    /** Every way {@code document} breaks {@code schema}, as "path: problem"; empty if it is valid. */
    static List<String> violations(JsonNode schema, JsonNode document) {
        List<String> found = new ArrayList<>();
        check(schema, document, "$", found);
        return found;
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<String> found) {
        for (Map.Entry<String, JsonNode> keyword : schema.properties()) {
            if (!KEYWORDS.contains(keyword.getKey())) {
                throw new IllegalArgumentException(path + ": schema keyword '" + keyword.getKey()
                        + "' is not checked by " + JsonSchemaSubset.class.getSimpleName());
            }
        }
        String type = schema.path("type").asString();
        switch (type) {
            case "object" -> object(schema, value, path, found);
            case "array" -> {
                if (!value.isArray()) {
                    found.add(path + ": not an array");
                    return;
                }
                for (int i = 0; i < value.size(); i++) {
                    check(schema.get("items"), value.get(i), path + "[" + i + "]", found);
                }
            }
            case "string" -> string(schema, value, path, found);
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    found.add(path + ": not an integer");
                } else if (schema.has("minimum") && value.asLong() < schema.get("minimum").asLong()) {
                    found.add(path + ": below " + schema.get("minimum").asLong());
                }
            }
            default -> throw new IllegalArgumentException(path + ": type '" + type + "' is not checked");
        }
    }

    private static void object(JsonNode schema, JsonNode value, String path, List<String> found) {
        if (!value.isObject()) {
            found.add(path + ": not an object");
            return;
        }
        Set<String> declared = new HashSet<>(schema.path("properties").propertyNames());
        for (JsonNode required : schema.path("required")) {
            if (!value.has(required.asString())) {
                found.add(path + ": missing " + required.asString());
            }
        }
        for (String name : value.propertyNames()) {
            if (declared.contains(name)) {
                check(schema.get("properties").get(name), value.get(name), path + "." + name, found);
            } else if (schema.path("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean()) {
                found.add(path + ": property " + name + " is not in the schema");
            }
        }
    }

    private static void string(JsonNode schema, JsonNode value, String path, List<String> found) {
        if (!value.isString()) {
            found.add(path + ": not a string");
            return;
        }
        String text = value.asString();
        if (schema.has("enum")) {
            Set<String> allowed = new HashSet<>();
            schema.get("enum").forEach(e -> allowed.add(e.asString()));
            if (!allowed.contains(text)) {
                found.add(path + ": " + text + " is not one of " + allowed);
            }
        }
        switch (schema.path("format").asString("")) {
            case "uuid" -> {
                if (!UUID_FORM.matcher(text).matches()) {
                    found.add(path + ": " + text + " is not a UUID");
                } else {
                    UUID.fromString(text);
                }
            }
            case "date-time" -> {
                try {
                    OffsetDateTime.parse(text);
                } catch (DateTimeParseException e) {
                    found.add(path + ": " + text + " is not a date-time");
                }
            }
            case "" -> { }
            default -> throw new IllegalArgumentException(path + ": format '" + schema.get("format").asString()
                    + "' is not checked");
        }
    }
}
