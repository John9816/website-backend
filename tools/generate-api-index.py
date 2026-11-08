#!/usr/bin/env python3
"""Render the checked compatibility baseline as a human-readable route index."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
contract = json.loads((root / 'src/test/resources/contracts/public-api-and-entities.json').read_text())
lines = [
    '# 后端接口契约索引', '',
    '由 `tools/generate-api-index.py` 从重构前的契约基线生成。`ArchitectureContractTests` 每次构建会从实际 Spring 路由、Jackson 模型和 JPA 实体重新采集并比较，重构不得通过重写基线绕过差异。', '',
    f"共 {len(contract['routes'])} 条路由映射（包含同一 handler 的路径别名）、{len(contract['jsonModels'])} 个 JSON DTO/枚举、{len(contract['entities'])} 个数据库实体。路径、参数注解、返回类型、JSON 属性、实体注解均纳入比较；Java 包名前缀不纳入对外契约。", '',
    '请求与响应示例见 [前端接口文档](frontend-api.md)，汽水的上游协议、独立扫码及后端适配见 [黑源接口文档](tunefree-next-qishui-api.md)。', '',
    '| 方法与路径映射 | 返回类型 |', '| --- | --- |',
]
for route, detail in contract['routes'].items():
    route = route.replace('|', '&#124;')
    returns = detail['returns'].replace('java.lang.', '').replace('java.util.', '')
    lines.append(f'| `{route}` | `{returns}` |')
(root / 'docs/api-contract-index.md').write_text('\n'.join(lines) + '\n')
