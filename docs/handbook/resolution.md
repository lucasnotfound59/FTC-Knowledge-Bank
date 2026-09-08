# 校验、适用范围与确定性裁决

校验整个知识根目录：

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
```

为指定队伍和赛季解析 active 规则：

```bash
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
```

解析规则如下：

- 只有 `approved` 规则能够生效；`candidate`、`deprecated` 和 `rejected` 都不生效。
- `applicability.teams` 和 `applicability.seasons` 分别按队号和赛季过滤；空列表表示不限制该维度。
- 同一规范主题且适用于当前上下文时，优先级为 `OFFICIAL > TEAM > SHARED`。
- 同一主题在最高有效权威层级有两条或更多适用规则时会产生 conflict，该主题没有 active 胜者。
- 文本模式遇到 conflict 时输出冲突并抑制 active 行，退出 2。机器消费者使用 --json，并依据 ok、conflicts 和退出码判断；存在冲突不能当作成功。
- 没有冲突时，不同主题互不覆盖；CLI 的 active 输出按规则 `id` 确定性排序。
