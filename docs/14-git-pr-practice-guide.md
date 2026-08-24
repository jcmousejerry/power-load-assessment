# Git Pull Request 实践说明

Pull Request，简称 PR，可以理解为“申请把一个分支中的修改合并到另一个分支”。

本项目约定：

- `main` 保存稳定、可运行的项目版本。
- 开发新功能或修改文档时，从 `main` 创建独立分支。
- 修改完成后把开发分支推送到 GitHub，再创建 PR，请求合并回 `main`。
- PR 页面用于检查文件差异、讨论、运行自动检查和决定是否合并。

一次标准流程如下：

```text
main（稳定版本）
  └─ docs/pr-practice（本次修改分支）
       └─ 提交修改
            └─ 创建 PR，请求合并回 main
```

命令行负责创建分支、修改、提交和推送：

```powershell
git switch main
git pull
git switch -c docs/pr-practice
git add .
git commit -m "docs: 添加PR实践说明"
git push -u origin docs/pr-practice
```

推送后，在 GitHub 上选择：

```text
base: main
compare: docs/pr-practice
```

点击 `Create pull request` 后，PR才正式产生。检查无误后点击 `Merge pull request`，修改才会进入 `main`。

合并完成后，本地同步并删除已经完成使命的开发分支：

```powershell
git switch main
git pull
git branch -d docs/pr-practice
```

直接推送一个分支不会自动创建 PR；PR是一项需要明确创建、审查和合并的GitHub协作操作。
