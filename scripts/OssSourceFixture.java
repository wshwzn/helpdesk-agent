import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 真实联调专用 OSS 测试文件写入器，凭据仅从环境变量读取。 */
class OssSourceFixture {
    /** 只操作独立 smoke 目录内的固定测试文件，异常只输出类型以免泄露厂商响应。 */
    public static void main(String[] args) {
        if (args.length != 3 || !args[1].matches("external-knowledge/smoke-[a-f0-9]{12}/policy\\.md")
                || !args[2].matches("v[12]")) {
            throw new IllegalArgumentException("expected put/delete, isolated smoke object key and v1/v2");
        }
        var client = new OSSClientBuilder().build(required("OSS_ENDPOINT"), required("OSS_ACCESS_KEY_ID"), required("OSS_ACCESS_KEY_SECRET"));
        try {
            String bucket = required("OSS_BUCKET");
            if ("delete".equals(args[0])) {
                client.deleteObject(bucket, args[1]);
            } else if ("put".equals(args[0])) {
                String amount = "v1".equals(args[2]) ? "500" : "600";
                byte[] content = ("# 外部来源联调资料\n普通员工因公出差到上海，每晚住宿报销上限为" + amount + "元。\n")
                        .getBytes(StandardCharsets.UTF_8);
                var metadata = new ObjectMetadata();
                metadata.setContentType("text/markdown");
                metadata.setContentLength(content.length);
                metadata.setUserMetadata(Map.of("kb-source-key", "oss-smoke-travel", "kb-business-version", args[2],
                        "kb-scope", "CN_SH", "kb-valid-from", "2020-01-01T00:00:00"));
                client.putObject(bucket, args[1], new ByteArrayInputStream(content), metadata);
            } else {
                throw new IllegalArgumentException("unknown fixture operation");
            }
            System.out.println("fixture operation succeeded");
        } catch (RuntimeException exception) {
            System.err.println("fixture operation failed: " + exception.getClass().getSimpleName());
            System.exit(1);
        } finally {
            client.shutdown();
        }
    }

    /** 缺少配置时只提示字段名，不打印任何配置值。 */
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }
}
