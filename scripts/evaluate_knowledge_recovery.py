"""冻结真实知识库召回，对照原链路与一次修订链路；交付不自动等于回答正确。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from evaluate_trusted_knowledge import ROOT, fingerprint


def main():
    """输出目录不可覆盖，标签只留在结果中，普通单元测试不会触发联网。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, default=ROOT / 'docs/evaluation/knowledge-recovery-cases.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'target'):
        raise ValueError('raw outputs must stay under target')
    output.mkdir(parents=True, exist_ok=False)
    dataset = args.dataset.read_bytes()
    (output / 'dataset.json').write_bytes(dataset)
    metadata = {'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'sourceSha256': fingerprint((ROOT / 'src/main/java').rglob('*.java')),
                'runnerSha256': fingerprint((ROOT / 'src/test/java/com/anying/helpdesk/knowledge/answer/evaluation').rglob('*.java')),
                'datasetSha256': hashlib.sha256(dataset).hexdigest()}
    (output / 'environment.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    classpath_file = ROOT / 'target/recovery-classpath.txt'
    with (output / 'build.log').open('w', encoding='utf-8') as log:
        subprocess.run([shutil.which('mvn'), '-q', '-DskipTests', 'test-compile', 'dependency:build-classpath',
                        '-Dmdep.outputFile=' + str(classpath_file)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    classpath = os.pathsep.join([str(ROOT / 'target/test-classes'), str(ROOT / 'target/classes'),
                                classpath_file.read_text(encoding='utf-8').strip()])
    arguments = ['-Dfile.encoding=UTF-8', '-cp', classpath,
                 'com.anying.helpdesk.knowledge.answer.evaluation.KnowledgeAnswerRecoveryEvaluationCli',
                 str(output / 'dataset.json'), str(output)]
    argfile = output / 'java-arguments.txt'
    argfile.write_text('\n'.join('"' + v.replace('\\', '\\\\').replace('"', '\\"') + '"' for v in arguments), encoding='utf-8')
    with (output / 'runtime.log').open('w', encoding='utf-8') as log:
        with subprocess.Popen([shutil.which('java'), '@' + str(argfile)], cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace') as process:
            try:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    if line.startswith('RECOVERY '):
                        print(line.rstrip(), flush=True)
                code = process.wait()
            except BaseException:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                raise
    rows = [json.loads(line) for line in (output / 'calls.jsonl').read_text(encoding='utf-8').splitlines()]
    summary = {}
    for strategy in ('BASELINE', 'GROUNDED'):
        workflow = [r for r in rows if r['strategy'] == strategy and r['expectedApproved'] is None]
        checks = [r for r in rows if r['strategy'] == strategy and r['expectedApproved'] is not None]
        summary[strategy] = {'workflowAttempts': len(workflow),
                             'deliveredAwaitingManualCheck': sum('answer' in r for r in workflow),
                             'workflowFailures': sum('answer' not in r for r in workflow),
                             'reviewChecks': len(checks),
                             'reviewCorrect': sum(r['outcome'] == 'CHECK_PASSED' for r in checks),
                             'reviewIncorrect': sum(r['outcome'] == 'CHECK_FAILED' for r in checks),
                             'reviewTechnicalFailures': sum(r['outcome'] == 'FAILED' for r in checks)}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    raise SystemExit(code)


if __name__ == '__main__':
    main()
