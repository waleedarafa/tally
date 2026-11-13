package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F15, the payload scan (ADR-006, ADR-034; I-11): the message to Access can carry no personal data, because it can
 * carry no free text. Its schema allows no property it does not name, and every property is one of: an ID (a string
 * of format {@code uuid}), an enum (a string with {@code enum}), a number ({@code integer}), or a time (a string of
 * format {@code date-time}); or an array or object of these. A string that is none of these is free text, and fails
 * the build.
 */
class F15PayloadScanTest {

    static final Path SCHEMA = Path.of("../contracts/access-facts/v1/schema.json");

    @Test
    void theAccessFactsSchemaAllowsOnlyIdsEnumsNumbersAndTimes() throws IOException {
        JsonNode schema = JsonMapper.builder().build().readTree(Files.readString(SCHEMA));

        assertThat(problems(schema, "$")).as("free text or an open object in " + SCHEMA).isEmpty();
    }

    static List<String> problems(JsonNode schema, String path) {
        List<String> found = new ArrayList<>();
        switch (schema.path("type").asString()) {
            case "object" -> {
                if (!schema.path("additionalProperties").isBoolean() || schema.get("additionalProperties").asBoolean()) {
                    found.add(path + ": an object must have \"additionalProperties\": false");
                }
                for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
                    found.addAll(problems(property.getValue(), path + "." + property.getKey()));
                }
            }
            case "array" -> found.addAll(problems(schema.path("items"), path + "[]"));
            case "integer" -> { }
            case "string" -> {
                String format = schema.path("format").asString("");
                boolean id = format.equals("uuid");
                boolean time = format.equals("date-time");
                boolean enumeration = schema.has("enum") && format.isEmpty();
                if (!id && !time && !enumeration) {
                    found.add(path + ": a string that is not an ID, an enum or a time is free text");
                }
            }
            default -> found.add(path + ": type " + schema.path("type") + " is not an ID, an enum, a number or a time");
        }
        return found;
    }
}
