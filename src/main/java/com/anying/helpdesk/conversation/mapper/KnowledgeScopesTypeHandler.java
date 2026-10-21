package com.anying.helpdesk.conversation.mapper;

import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 将消息范围列表保存为 JSON 文本，不依赖数据库厂商的 JSON 类型。 */
public class KnowledgeScopesTypeHandler extends BaseTypeHandler<List<String>> {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** 序列化范围列表，使正文与证据快照在同一次消息更新中提交。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, List<String> value,
                                    JdbcType jdbcType) throws SQLException {
        try {
            statement.setString(index, MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new SQLException("cannot serialize knowledge scopes");
        }
    }

    /** 按列名读取历史消息的范围列表。 */
    @Override
    public List<String> getNullableResult(ResultSet result, String column) throws SQLException {
        return read(result.getString(column));
    }

    /** 按列序号读取历史消息的范围列表。 */
    @Override
    public List<String> getNullableResult(ResultSet result, int column) throws SQLException {
        return read(result.getString(column));
    }

    /** 支持数据库调用结果中的范围列表字段。 */
    @Override
    public List<String> getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getString(column));
    }

    /** 保留旧消息的空列表；列表损坏时显式失败，避免展示错误审计数据。 */
    private List<String> read(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new SQLException("cannot deserialize knowledge scopes");
        }
    }
}
