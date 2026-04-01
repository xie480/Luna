package org.yilena.luna.rag.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEnumJsonTest {

    @Test
    void shouldSerializeRouteAndSourceAsLowercase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Map.of(
                "route", RetrievalRoute.MODULAR,
                "source", RetrievalSource.KNOWLEDGE
        ));
        assertTrue(json.contains("\"route\":\"modular\""));
        assertTrue(json.contains("\"source\":\"knowledge\""));
    }

    @Test
    void shouldDeserializeRouteAndSourceFromLowercase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(RetrievalRoute.MODULAR, mapper.readValue("\"modular\"", RetrievalRoute.class));
        Map<RetrievalSource, Integer> sourceMap = mapper.readValue(
                "{\"memory\":1}",
                new TypeReference<Map<RetrievalSource, Integer>>() {
                }
        );
        assertEquals(1, sourceMap.get(RetrievalSource.MEMORY));
    }
}
