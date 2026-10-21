package com.anying.helpdesk.knowledge.answer;

import java.util.List;
import java.util.HashSet;

/** 统一请求范围边界；ALL 是通用资料，不能作为跨地区通配符。 */
public final class KnowledgeScopes {
    public static final int MAX_SCOPES = 3;
    /** 工具类不创建实例。 */
    private KnowledgeScopes() { }
    /** 合并新旧请求字段，拒绝冲突、重复、超限和通用范围混选。 */
    public static List<String> resolve(String scope, List<String> scopes) {
        if (scopes == null) return scope == null ? List.of("ALL") : checked(List.of(scope));
        var selected = checked(scopes);
        if (scope != null && !(selected.size() == 1 && scope.equals(selected.getFirst())))
            throw new IllegalArgumentException("scope fields disagree");
        return selected;
    }
    /** 校验固定的小范围集合，禁止通过空列表绕过过滤。 */
    public static List<String> checked(List<String> scopes) {
        if (scopes == null || scopes.isEmpty() || scopes.size() > MAX_SCOPES
                || scopes.stream().anyMatch(s -> s == null || !s.matches("[A-Z][A-Z0-9_-]{0,39}"))
                || new HashSet<>(scopes).size() != scopes.size()
                || (scopes.size() > 1 && scopes.contains("ALL")))
            throw new IllegalArgumentException("select one to three distinct scopes; ALL cannot be combined");
        return List.copyOf(scopes);
    }
    /** 多范围不伪装为一个地区或 ALL；旧标量字段只在单范围时有值。 */
    public static String single(List<String> scopes) { return scopes.size() == 1 ? scopes.getFirst() : null; }
    /** 为旧模型调用保留单范围字符串，多范围使用明确的范围列表文本。 */
    public static String describe(List<String> scopes) { return scopes.size() == 1 ? scopes.getFirst() : scopes.toString(); }
}
