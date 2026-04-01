package org.yilena.luna.rag.router;

import org.junit.jupiter.api.Test;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteSelectorTest {

    @Test
    void shouldSelectModularAndEnableRewriteForMultiSourceReasoning() {
        RouteSelector selector = new RouteSelector(new RagProperties());
        QueryObject queryObject = QueryObject.builder()
                .normalizedQuery("结合我的记忆和偏好给我建议")
                .queryType("multi_source_reasoning")
                .possibleFilters(Map.of("inferred_sources", List.of("memory", "preference")))
                .build();

        var plan = selector.selectPlan(queryObject, RetrievalRequest.builder()
                .query("结合我的记忆和偏好给我建议")
                .allowedRoutes(RetrievalRoute.all())
                .sourceScope(RetrievalSource.all())
                .build());

        assertEquals(RetrievalRoute.MODULAR, plan.getRoute());
        assertTrue(plan.isNeedsRewrite());
        assertEquals(2, plan.getSources().size());
    }
}
