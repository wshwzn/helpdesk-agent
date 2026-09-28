package com.anying.helpdesk.storage;

import java.io.InputStream;

public interface ObjectStorageService {

    /**
     * 返回写入文档时记录的存储提供方标识。
     */
    String provider();

    /**
     * 返回当前对象存储使用的逻辑桶名称。
     */
    String bucketName();

    /**
     * 将文件流写入指定对象键。
     */
    void upload(String objectKey, InputStream inputStream, long contentLength, String contentType);

    /**
     * 打开指定对象的读取流，调用方负责关闭该流。
     */
    InputStream download(String bucketName, String objectKey);

    /**
     * 删除指定对象；对象不存在时按成功处理。
     */
    void delete(String bucketName, String objectKey);
}
