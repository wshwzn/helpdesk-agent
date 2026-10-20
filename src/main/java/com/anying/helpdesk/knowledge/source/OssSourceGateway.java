package com.anying.helpdesk.knowledge.source;

import java.util.List;

public interface OssSourceGateway {
    /** 完整列出指定目录；超出上限或任一分页失败必须抛错，不能返回部分清单。 */
    List<String> list(String prefix, int limit);

    /** 读取文件内容标识和业务元数据，用于判断是否需要下载快照。 */
    OssSourceObject head(String key);

    /** 按预期标识下载有大小限制的内容，变化中的对象拒绝导入。 */
    byte[] read(String key, OssSourceObject expected);
}
