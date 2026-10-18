package com.anying.helpdesk.conversation.mapper;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
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

/** 将已核验报告保存为 JSON 文本，不依赖数据库厂商的 JSON 类型。 */
public class KnowledgeAnswerReportTypeHandler extends BaseTypeHandler<KnowledgeAnswerReport> {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** 序列化回答报告，使正文与证据快照在同一次消息更新中提交。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, KnowledgeAnswerReport value,
                                    JdbcType jdbcType) throws SQLException {
        try {
            statement.setString(index, MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new SQLException("cannot serialize knowledge answer report");
        }
    }

    /** 按列名读取历史消息的证据报告。 */
    @Override
    public KnowledgeAnswerReport getNullableResult(ResultSet result, String column) throws SQLException {
        return read(result.getString(column));
    }

    /** 按列序号读取历史消息的证据报告。 */
    @Override
    public KnowledgeAnswerReport getNullableResult(ResultSet result, int column) throws SQLException {
        return read(result.getString(column));
    }

    /** 支持数据库调用结果中的证据报告字段。 */
    @Override
    public KnowledgeAnswerReport getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getString(column));
    }

    /** 保留旧消息的空报告；报告损坏时显式失败，避免展示错误审计数据。 */
    private KnowledgeAnswerReport read(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, KnowledgeAnswerReport.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("cannot deserialize knowledge answer report");
        }
    }
}
