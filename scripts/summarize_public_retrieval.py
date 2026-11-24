#!/usr/bin/env python3
"""将本地真实评测结果压缩为可提交的排名证据，不复制公开制度全文或保存身份凭据。"""

import argparse
import hashlib
from pathlib import Path

from evaluate_public_retrieval import BENCHMARK, CACHE, read, write, corpus, normalized


def compact(report, documents, cases):
    """保留参数、指标、候选摘要及命中证据组，原始正文只留在本地运行记录。"""
    if not report.get("environmentStable") or len(report["results"]) != report["planned"]:
        raise ValueError("only completed runs with stable environments can be archived")
    result = {k: v for k, v in report.items() if k != "results"}
    result["results"] = []
    for row in report["results"]:
        item = {k: v for k, v in row.items() if k != "response"}
        item["candidates"] = []
        for hit in row.get("response", {}).get("results", []):
            source = hit["metadata"]["sourceKey"].removeprefix("benchmark:")
            evidence_groups = cases[row["id"]]["evidenceGroups"]
            matched = [index for index, group in enumerate(evidence_groups) if any(
                e["document"] == source and normalized(documents[source]["paragraphs"][e["paragraph"]])
                in normalized(hit["content"]) for e in group)]
            candidate = {k: hit[k] for k in ["rank", "chunkSequence", "rrfScore", "bm25Rank", "vectorRank"]}
            candidate.update({"source": source, "contentSha256": hashlib.sha256(hit["content"].encode()).hexdigest(),
                              "matchedEvidenceGroups": matched})
            item["candidates"].append(candidate)
        result["results"].append(item)
    return result


def main():
    """归档开发集和测试集快照，生成统一口径的对比表。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dev", type=Path, default=CACHE / "dev-run.json")
    parser.add_argument("--test", type=Path, default=CACHE / "test-run.json")
    parser.add_argument("--output", type=Path, default=BENCHMARK / "runs/2026-11-24-initial.json")
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("archive already exists; choose another output name")
    _, documents = corpus()
    cases_path = BENCHMARK / "cases.json"
    cases = {c["id"]: c for c in read(cases_path)["cases"]}
    dev, test = read(args.dev), read(args.test)
    current_hash = hashlib.sha256(cases_path.read_bytes()).hexdigest()
    if any(report["casesSha256"] != current_hash for report in [dev, test]):
        raise ValueError("labels have changed since evaluation")
    for key in ["settings", "sourcesSha256", "model", "chunking", "implementationSha256"]:
        if dev[key] != test[key]:
            raise ValueError("dev/test environments differ: " + key)
    write(args.output, {"dev": compact(dev, documents, cases), "test": compact(test, documents, cases)})
    lines = ["# 公开制度检索初测结果", "", "标注状态：AI 辅助构题及逐段核对，未经过独立人工复核。结果不是正式人工标注基准或生产线上指标。", "",
             f"语料：{len(test['documents'])} 份文档、{sum(d['chunkCount'] for d in test['documents'])} 个 chunk。开发集 {dev['planned']} 题，测试集 {test['planned']} 题；测试集参数未调优。", "",
             "以下为测试集宏平均，各题权重相同；K 是片段数量，文档及证据组命中均去重。", "",
             "| 方案 | 参考文档 Recall@5 | 证据段落 Recall@5 | 证据 Hit@5 | 证据齐全率@5 | 证据 MRR@5 |",
             "|---|---:|---:|---:|---:|---:|"]
    for method in ["BM25", "VECTOR", "RRF"]:
        m = test["summary"]["methods"][method]["5"]
        lines.append(f"| {method} | {m['documentRecall']:.2%} | {m['evidenceRecall']:.2%} | {m['evidenceHit']:.2%} | {m['evidenceComplete']:.2%} | {m['evidenceMRR']:.4f} |")
    lines += ["", f"测试技术失败：{test['summary']['technicalFailures']}/{test['planned']}。环境前后校验一致。",
              f"共享检索请求 P95：{test['summary']['sharedRequestLatencyP95Ms']} ms（单次串行测试、预热一次，含查询向量化、双路检索、准入复核及 HTTP；不是单路延迟、完整问答延迟或负载测试）。", "",
              "## 分类型证据召回率 @5", "", "| 类型 | 题数 | BM25 | VECTOR | RRF |", "|---|---:|---:|---:|---:|"]
    for category, values in test["byCategory"].items():
        scores = " | ".join(f"{values['methods'][m]['5']['evidenceRecall']:.2%}" for m in ["BM25", "VECTOR", "RRF"])
        lines.append(f"| {category} | {values['count']} | {scores} |")
    lines += ["", "## 至少一种方案未找齐证据的题目", "", "以下保留全部测试失败/部分命中案例，数值为该题证据 Recall@5。", "",
              "| 题号 | 问题 | BM25 | VECTOR | RRF |", "|---|---|---:|---:|---:|"]
    for row in test["results"]:
        if "error" in row:
            lines.append(f"| {row['id']} | {row['question']} | 技术失败 | 技术失败 | 技术失败 |")
        elif any(row["metrics"][m]["5"]["evidenceComplete"] < 1 for m in ["BM25", "VECTOR", "RRF"]):
            scores = " | ".join(f"{row['metrics'][m]['5']['evidenceRecall']:.0%}" for m in ["BM25", "VECTOR", "RRF"])
            lines.append(f"| {row['id']} | {row['question']} | {scores} |")
    lines += ["", "完整参数、各 K 指标及逐题候选见同名 JSON；语料来源、标注口径和复现方法见上级 README。", ""]
    args.output.with_suffix(".md").write_text("\n".join(lines), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
