package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class KnowledgeScopesTest {
    /** 新字段不能静默覆盖旧字段，也不能混入通用范围或无限扩展搜索。 */
    @Test @DisplayName("范围列表拒绝冲突重复混选和超限")
    void rejectsInvalidSets() {
        assertThatThrownBy(() -> KnowledgeScopes.resolve("SH", List.of("BJ"))).isInstanceOf(IllegalArgumentException.class);
        for (var values : List.of(List.<String>of(), List.of("SH", "SH"), List.of("ALL", "SH"),
                List.of("A", "B", "C", "D"), List.of("../file")))
            assertThatThrownBy(() -> KnowledgeScopes.checked(values)).isInstanceOf(IllegalArgumentException.class);
    }
    /** 旧请求仍为单范围，新请求不能把比较范围写成ALL。 */
    @Test @DisplayName("旧请求和多选请求兼容且不会丢失范围")
    void readsBothRequestShapes() throws Exception {
        var json = JsonMapper.builder().build();
        var old = json.readValue("{\"content\":\"问题\",\"applicabilityScope\":\"SH\"}", SendMessageRequest.class);
        assertThat(old.applicabilityScopes()).containsExactly("SH");
        var multi = json.readValue("{\"content\":\"比较\",\"applicabilityScopes\":[\"SH\",\"BJ\"]}", SendMessageRequest.class);
        assertThat(multi.applicabilityScope()).isNull();
        assertThat(multi.applicabilityScopes()).containsExactly("SH", "BJ");
        assertThat(multi.isKnowledgeInputValid()).isTrue();
    }
}
