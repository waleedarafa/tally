package com.tallyaccess.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code consumer-access.json} lists exactly what Access reads (ADR-034; review of PR-4). The fields of Access's
 * message type are compared with it, both ways: a field Access starts to read and the file does not list fails here,
 * so billing's F7 always protects the fields Access really reads.
 */
class ConsumerAccessFieldsTest {

    static final Path CONSUMER = Path.of("../contracts/access-facts/v1/consumer-access.json");

    @Test
    void theFieldsAccessReadsAreExactlyTheOnesItsContractFileLists() throws IOException {
        Set<String> listed = new TreeSet<>();
        JsonMapper.builder().build().readTree(Files.readString(CONSUMER)).get("reads")
                .forEach(field -> listed.add(field.asString()));

        assertThat(fieldsOf(AccessFactsMessage.class, "")).isEqualTo(listed);
    }

    /** Every record component, and for a list of records, "name[].component". */
    static Set<String> fieldsOf(Class<?> type, String prefix) {
        Set<String> fields = new TreeSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getType() == List.class
                    && component.getGenericType() instanceof ParameterizedType list
                    && list.getActualTypeArguments()[0] instanceof Class<?> element && element.isRecord()) {
                fields.addAll(fieldsOf(element, prefix + component.getName() + "[]."));
            } else {
                fields.add(prefix + component.getName());
            }
        }
        return fields;
    }
}
