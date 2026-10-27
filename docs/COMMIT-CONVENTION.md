# 提交约定

## 提交作者

本工程**强制要求**所有 commit 的 author/committer 必须为：

```
this_way <1026989012@qq.com>
```

## 实施方式

通过两层保障强制约束：

### 1. 本地仓库 git config（已配置）

仓库级（`--local`）的 user.name / user.email 已设置为 this_way，覆盖全局配置：

```bash
git config --local user.name "this_way"
git config --local user.email "1026989012@qq.com"
```

### 2. commit-msg 钩子（强校验）

`.git/hooks/commit-msg` 在每次 commit 时检查 `GIT_AUTHOR_NAME` / `GIT_AUTHOR_EMAIL`：

- 符合 `this_way <1026989012@qq.com>` → 通过
- 不符合 → 拒绝提交并打印修正命令

## 历史提交处理

历史提交如混有其他作者，需要一次性重写，使用：

```bash
git filter-branch -f --env-filter '
export GIT_AUTHOR_NAME="this_way"
export GIT_AUTHOR_EMAIL="1026989012@qq.com"
export GIT_COMMITTER_NAME="this_way"
export GIT_COMMITTER_EMAIL="1026989012@qq.com"
' -- --all
git push origin master --force
```

## 新机器 / 新克隆时

克隆后只需确认本地配置即可，钩子随仓库自带，无需额外安装：

```bash
git clone git@github.com:linuxhex/hubble.git
cd hubble
git config --local user.name "this_way"
git config --local user.email "1026989012@qq.com"
```