"""验证SSE评测解析器保留事件边界和失败，不误记为成功交付。"""
import unittest
from verify_unified_knowledge_entry import read_events, matches_history, stream_answer
from evaluate_trusted_knowledge import ApiError


class UnifiedEntrySseTest(unittest.TestCase):
    def test_progress_preserves_delivery_checks(self):
        """阶段事件不冒充正文，兼容旧服务且继续拒绝失败或不一致的交付。"""
        message = {"content": "已核验"}
        events = [{"type": "start"}, {"type": "delta", "content": "已核验"},
                  {"type": "done", "assistantMessage": message}]
        self.assertEqual(message, stream_answer(events))
        events.insert(1, {"type": "progress", "stage": "REVIEW"})
        self.assertEqual(message, stream_answer(events))
        with self.assertRaises(ApiError):
            stream_answer(events[:2] + [{"type": "error"}])
        events[2]["content"] = "未经核验"
        with self.assertRaises(ValueError):
            stream_answer(events)

    def test_history_tolerates_only_microsecond_rounding(self):
        """只允许数据库时间精度差异，正文变化或更大时间偏移仍失败。"""
        message = {"content": "正文", "updatedAt": "2026-10-21T14:12:02.8599245"}
        self.assertTrue(matches_history(message, {**message, "updatedAt": "2026-10-21T14:12:02.859925"}))
        self.assertFalse(matches_history(message, {**message, "updatedAt": "2026-10-21T14:12:02.859927"}))
        self.assertFalse(matches_history(message, {**message, "content": "改写正文"}))

    def test_history_does_not_ignore_report_changes(self):
        """核验报告、模式或引用差异不能被时间精度兼容掩盖。"""
        message = {"mode": "KNOWLEDGE", "knowledgeAnswer": {"reason": "REVIEWED"}}
        self.assertFalse(matches_history(message, {**message, "mode": "AGENT"}))
        self.assertFalse(matches_history(message, {**message, "knowledgeAnswer": None}))

    def test_order_and_multiline_data(self):
        """完整帧的中文正文和多行JSON按原顺序保存。"""
        data = 'event:start\ndata:{"type":"start"}\n\nevent:delta\ndata:{"type":"delta",\ndata:"content":"已核验"}\n\nevent:done\ndata:{"type":"done"}\n\n'
        events = read_events([x.encode() for x in data.splitlines(keepends=True)])
        self.assertEqual(["start", "delta", "done"], [e["type"] for e in events])
        self.assertEqual("已核验", events[1]["content"])

    def test_error_remains_error(self):
        """错误事件不能被解析成完成或忽略。"""
        events = read_events([b'event:error\n', b'data:{"type":"error"}\n', b'\n'])
        self.assertEqual([{"type": "error"}], events)

    def test_mismatched_or_incomplete_frame_fails(self):
        """事件名称不一致或帧未完成时明确报错。"""
        for lines in [[b'event:done\n', b'data:{"type":"error"}\n', b'\n'],
                      [b'event:done\n', b'data:{"type":"done"}\n']]:
            with self.assertRaises(ValueError):
                read_events(lines)


if __name__ == "__main__":
    unittest.main()
