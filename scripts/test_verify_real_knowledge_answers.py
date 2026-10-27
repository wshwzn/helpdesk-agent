"""在线评测的离线报告与续跑契约，不调用模型或业务接口。"""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import verify_real_knowledge_answers as runner


class RealKnowledgeReportTest(unittest.TestCase):
    def test_report_can_be_saved_and_resume_does_not_repeat_completed_questions(self):
        """实际调用公共报告函数检查必填字段，完整续跑不能再次发送已完成的问题。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'runtime.jar'
            jar.write_bytes(b'offline-test')
            output = root / 'report'
            argv = ['verify', '--output', str(output), '--runtime-jar', str(jar)]
            original = {'knowledgeBase': {'id': 3, 'name': '项目开发记录'}}

            def answer(client, case, state):
                """仅替换外部问答，不替换报告格式化逻辑。"""
                return {**case, 'outcome': 'CHECK_PASSED', 'actualStatus': 'ANSWERABLE',
                        'elapsedSeconds': 1, 'statusMatched': True, 'referenceCount': 0,
                        'invalidReferenceCount': 0, 'message': {'content': '固定测试内容', 'totalTokens': 3}}

            with patch('sys.argv', argv), patch.object(runner.base, 'Client'), \
                    patch.object(runner.base, 'load_credentials'), \
                    patch.object(runner.base, 'snapshot', return_value=original), \
                    patch.object(runner.base, 'fingerprint', return_value='source'), \
                    patch.object(runner.subprocess, 'check_output', return_value='test'), \
                    patch.object(runner.base, 'execute_case', side_effect=answer) as execute:
                with self.assertRaises(SystemExit) as stopped:
                    runner.main()
                self.assertEqual(0, stopped.exception.code)
                self.assertEqual(4, execute.call_count)
                self.assertTrue((output / 'report.md').exists())
                report = json.loads((output / 'results.json').read_text(encoding='utf-8'))
                self.assertTrue(report['configurationUnchanged'])
                self.assertEqual(4, len(report['results']))
                execute.reset_mock()
                with patch('sys.argv', argv + ['--resume']), self.assertRaises(SystemExit) as resumed:
                    runner.main()
                self.assertEqual(0, resumed.exception.code)
                execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
