"""在用户已有项目资料库执行固定问题，保留全部失败，不改资料、默认库或检索参数。"""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import subprocess
from datetime import datetime

import evaluate_trusted_knowledge as base

QUESTIONS = [
    ('K01', '项目是否具有澄清机制'),
    ('K02', '文档上传后会自动建立索引吗？索引失败后怎么处理？'),
    ('K03', '系统如何保证回答引用的内容确实来自知识库？'),
    ('K04', '文档发布新版以后，旧版会不会继续参与问答？'),
]


def main():
    """每题一次真实请求，记录运行版本及前后配置，正确性仍需阅读实际答案。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime-jar', type=Path, required=True)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=args.resume)
    base.load_credentials(base.ROOT / '.env')
    client = base.Client(args.base_url, timeout=360)
    client.login('admin')
    client.login('customer')
    original = base.snapshot(client)
    if original['knowledgeBase']['name'] != '项目开发记录':
        raise ValueError('expected existing project development knowledge base')
    report = {'planned': len(QUESTIONS), 'original': original, 'results': [],
              'startedAt': datetime.now(base.CST).isoformat(),
              'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], text=True).strip()),
              'datasetSha256': hashlib.sha256(json.dumps(QUESTIONS, ensure_ascii=False).encode('utf-8')).hexdigest(),
              'runtimeJarSha256': hashlib.sha256(args.runtime_jar.read_bytes()).hexdigest(),
              'sourceSha256': base.fingerprint((base.ROOT / 'src/main/java').rglob('*.java')),
              'note': '真实检索与模型，每题一次；自动检查不等于语义正确。'}
    if args.resume:
        previous = json.loads((args.output / 'results.json').read_text(encoding='utf-8'))
        if previous['original'] != original or previous['sourceSha256'] != report['sourceSha256']:
            raise ValueError('cannot resume after data or Java source changes')
        previous_rows = previous['results']
        if [(r['id'], r['question']) for r in previous_rows] != QUESTIONS[:len(previous_rows)]:
            raise ValueError('saved cases do not match planned prefix')
        for row in previous_rows:
            row.setdefault('runtimeJarSha256', previous['runtimeJarSha256'])
        report['results'] = previous_rows
        report['resumedFrom'] = {'previousRuntimeJarSha256': previous['runtimeJarSha256'],
                                 'completedCases': len(previous_rows), 'previousStartedAt': previous.get('startedAt')}
        base.save_report(args.output, report)
    for case_id, question in QUESTIONS[len(report['results']):]:
        if base.snapshot(client) != original:
            raise ValueError('knowledge base or model changed during evaluation')
        case = {'id': case_id, 'scenario': 'REAL_PROJECT_KNOWLEDGE', 'question': question,
                'scope': 'ALL', 'expectedStatus': 'ANSWERABLE'}
        row = base.execute_case(client, case, original)
        row['runtimeJarSha256'] = report['runtimeJarSha256']
        report['results'].append(row)
        base.save_report(args.output, report)
        print('LIVE', case_id, row['outcome'], row.get('knowledgeRunId'), flush=True)
        if row.get('diagnosticState') == 'INCOMPLETE':
            break
    report['configurationUnchanged'] = base.snapshot(client) == original
    base.save_report(args.output, report)
    sys.exit(0 if report['configurationUnchanged'] and len(report['results']) == len(QUESTIONS)
             and all(r['outcome'] == 'CHECK_PASSED' for r in report['results']) else 1)


if __name__ == '__main__':
    main()
