# E 轮固定交付资料

`上海住宿新版.md` 是虚构候选，上海金额为 760。旧版上海 640、北京 480、通用期限 12 个自然日复用 `../multi-scope/` 的固定文件。与真实企业制度无关。

脚本 `scripts/verify_knowledge_delivery.py` 会创建独立库，验证初始上传不产生任务、显式提交后仍是草稿、候选审核前后切换、重复识别及撤回不复活旧版；六个问答场景的预期固定在 CASES，执行前保存。末尾撤回候选，保留现场，并恢复原默认库。

设置既有 HELPDESK 管理员和客户登录环境变量，在仓库根目录执行：

```powershell
python -X utf8 scripts/verify_knowledge_delivery.py --output target/delivery-new-run --runtime-jar target/stage43-backend.jar
```

先核实实际运行 JAR 和配置：多跳启用、task-planning 关闭、BASELINE 审核、默认“dp官方”；`--runtime-jar` 只保存文件哈希，不替代进程核实。输出目录必须不存在。真实调用会使用模型及 OSS/ES 服务；默认库全局共享，运行时避免其他交互练习。

运行失败不可覆盖原结果或改标签重算；修改后再次运行应另记新轮次。断言失败导致非零退出是预期报告方式。本轮实际结果见[交付回归](../../evaluation/runs/2026-10-21-项目交付回归.md)。稳定练习请继续使用已恢复的原默认库，不要选被本脚本撤回过资料的回归库。
