# libs/ —— 编译期依赖

## touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar

车万女仆（Touhou Little Maid）1.21.1 / NeoForge 版发布 jar，本模组的**硬前置**。

- 来源：Modrinth 项目 `touhou-little-maid`，版本 `1.5.3-neoforge+mc1.21.1`
- 直链：https://cdn.modrinth.com/data/R0bDWFAW/versions/tXG1TkGx/touhoulittlemaid-1.5.3-neoforge%2Bmc1.21.1.jar
- 用法：`build.gradle` 里以 `implementation files(...)` 引入，**只用于编译，不会打进本模组的 jar**。
  玩家实际游玩时需要自己在 mods 目录放同款 TLM。
- 为什么不需要 deobf：NeoForge 1.21 开发环境使用 Mojang 官方映射，
  与 TLM 发布 jar 的编译映射一致，因此可以直接当普通 jar 依赖使用。

## tlm-sources.jar

同一版本的源码包，仅用于**对照 API**（尤其是 `IMaidTask`、`EntityMaid` 的方法签名）。
不参与编译，解压后的内容已在 `.gitignore` 中排除（`tlm-src/`）。

如果这两个文件丢了，可用下面的命令重新拉取（需要能访问 cdn.modrinth.com）：

```bash
curl -L -o libs/touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar \
  "https://cdn.modrinth.com/data/R0bDWFAW/versions/tXG1TkGx/touhoulittlemaid-1.5.3-neoforge%2Bmc1.21.1.jar"
```
