# 【Bug】execute2RespWithEL 会删除并篡改 data()/tag() 字符串字面量内的空格、换行与单引号（#I5ZS8I 回归）

> 归档背景：2026-09-19 RDS_EXECUTE 实测时暴露（SQL 含空格被 normalize 删净导致语法错误）。
> 项目侧绕行修复已落地于 `DatabusExecutor`（绕过 execute2RespWithEL，用 LiteFlowChainELBuilder 按原始 EL 建链）。
> 本文为提交 LiteFlow 官方仓库（Gitee）的 issue 草稿。

## 现象

通过 `FlowExecutor#execute2RespWithEL` 动态执行 EL 时，`.data("...")`、`.tag("...")` 字符串字面量的**内容被篡改**：

- 所有空白字符（空格、制表符、换行）被删除；
- 字面量内部的单引号 `'` 被替换为双引号 `"`。

SQL 被粘成一团导致语法错误：

```
原始: select userid, ext1 as idCard from orguser where ext1 = ?
实际: selectuserid,ext1asidCardfromorguserwhereext1=?
```

data 内是 JSON 且含单引号（如 SQL 字面量 `name = 'bob'`）时，JSON 结构也会被破坏。

## 最小复现

liteflow-spring-boot-starter 2.16.0（2.16.1、master 同样存在）：

```java
String el = "THEN(a.data(\"select id from t where name = 'bob'\"));";
LiteflowResponse resp = flowExecutor.execute2RespWithEL(el, null);
// 组件收到的 cmpData：空格被删、单引号变成双引号，等价于
// THEN(a.data("selectidfromtwherename="bob""));
```

期望收到 `select id from t where name = 'bob'`，实际被篡改。

## 根因

**1. `ElRegexUtil.normalize` 对整条 EL 无差别替换，不识别字符串字面量边界**（`com.yomahub.liteflow.util.ElRegexUtil`）：

```java
public static String normalize(String elStr) {
    // 剔除 EL 中多余空格，且将单引号变为双引号，并在末尾保留一个分号
    return elStr.replace("'", "\"").replaceAll("\\s", "").replaceFirst(";*$", ";");
}
```

**2. `FlowExecutor#execute2RespWithEL` 把 normalize 后的文本传给 `setEL` 参与编译**：

```java
String normalizedEl = ElRegexUtil.normalize(elStr);
String elMd5 = MD5.create().digestHex(normalizedEl);
...
LiteFlowChainELBuilder.createChain()
        .setChainId(chainId)
        .setEL(normalizedEl)   // ← 应为原始 elStr
        .build();
```

而 `LiteFlowChainELBuilder#setEL` 自身只用 normalize 算 MD5，chain 保存与编译的始终是原文。对照验证：绕过 `execute2RespWithEL`，直接用 builder 传原始 EL 建链，data 内容逐字符完好，qlexpress4 本身不删空格——唯一篡改点就是上述 `setEL(normalizedEl)`。

## 影响面

- 所有 `execute2RespWithEL` 公开重载；
- `data()`、`tag()` 等所有字符串字面量参数；
- SQL、JSON、模板文本、含缩进/换行的配置、含单引号的自然语言等场景；
- 官方 EL 组装 API（ELWrapper / SelectiveJavaEscaper）同样受影响（转义器不处理空格，空格在字面量中仍会被 normalize 删除）。

## 历史回归

#I5ZS8I「修复EL中定义的tag和data中的字符串的空格和换行被过滤掉了的现象」
https://gitee.com/dromara/liteFlow/issues/I5ZS8I

该修复在后续 EL 正则工具重构后回归，2.16.0 / 2.16.1 / master（2026-09 拉取）均存在。

## 修复建议

方案一（最小改动，推荐）：normalized 仅用于算 MD5，编译用原文，与 builder 现有行为对齐：

```java
.setEL(elStr)   // MD5 仍用 normalizedEl，缓存语义不变
```

方案二（治本）：`normalize` 改为词法感知实现，扫描时跟踪引号状态（含转义），只处理字符串字面量之外的空白与引号。

## 临时绕过

```java
String chainId = IdUtil.fastSimpleUUID();
LiteFlowChainELBuilder.createChain().setChainId(chainId).setEL(elStr).build();
LiteflowResponse response = flowExecutor.execute2Resp(chainId, param, context);
```
