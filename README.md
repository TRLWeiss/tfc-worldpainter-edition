# TerraFirmaCraft 1.12.2 · WorldPainter Edition（非官方改造版）

> English summary below.

基于群峦 TerraFirmaCraft 1.12.2 的 **1.7.23.181** 版（官方仓库 tag `v1.7.23.181`，提交 `23e6a43`）改造。这是**非官方**版本，和 TerraFirmaCraft 团队无关，出了问题请不要去官方报告。

## 做什么用

原版群峦只在自己的世界类型（`tfc_classic`）里生成资源。用 WorldPainter 等工具做的原版地图，装上群峦后什么都不会长。

改造版让**原版世界类型的主世界里也生成群峦的全部资源**：

- 矿脉：长在原版石头里，和其他模组的矿一样。附属（Metallum、rocksplus 等）通过群峦矿脉配置加的矿也会出现。
- 地表：散石、矿苗、树枝、大岩石、黏土、泥炭。
- 植物：群峦的树（装了 Dynamic Trees TFC 就是动态树）、植物、野生作物、浆果丛、果树。
- 动物：群峦动物成群出现。

**原版的石头、泥土、草地、沙子、水都不替换**，地图外观不变，世界类型也不改，其他模组照常工作。

为了能在原版地图里玩，顺带改了几处：

- 原版水可以直接喝。海洋、沙滩群系里的水按咸水算。
- 原版水也能给群峦耕地保湿。
- 在原版耕地上种群峦种子，会自动换成同位置岩层的群峦耕地（群峦作物靠群峦耕地记录养分）。
- 群峦按海平面 Y=144 设计的矿脉高度、岩层分界、海拔气温，都换算到原版海平面（默认 63）。海平面以下按比例压缩，以上一格对一格。
- 群峦的刷怪规则在主世界生效，和群峦世界一样，可以在群峦配置里关。

**正常的群峦世界不受影响**。同一个种子新建群峦世界，改造版和官方版生成的矿石数完全相同，其余差异比官方版自己两次生成之间的随机差异还小。

## 安装

1. 删掉 `mods` 里官方的 `TerraFirmaCraft-MC1.12.2-1.7.23.181.jar`，两个都留着会冲突。
2. 放入 [Releases](../../releases) 里的 `TerraFirmaCraft-MC1.12.2-1.7.23.181-wp1.jar`。
3. 服务器和客户端都要换。

版本号没变，依赖群峦的附属照常加载。只适用于 1.7.23.181，别的群峦版本请不要用。

## 配合 WorldPainter

- 导出时世界类型用默认（Default）就行。
- WorldPainter 默认水面在 Y=62，对应配置 `nonTFCSeaLevel=63`（默认值）。如果把水面改成 N，就把它设成 N+1。
- WorldPainter 默认会用 Resources 图层往地下铺原版矿石。只想要群峦的矿，就在 WorldPainter 里关掉这个图层。
- WorldPainter 导出的区块一般标成"已装饰"，Minecraft 不会再装饰它们。改造版会在这些区块第一次加载时补生成一次群峦内容，只补一次，不会重复。

## 配置

`config/TerraFirmaCraft - General.cfg` 的 world 分类里，带 `[WorldPainter edition]` 注释的几项：

| 配置 | 默认 | 作用 |
|---|---|---|
| enableInNonTFCWorlds | true | 总开关，关掉就和官方版一样 |
| nonTFCSeaLevel | 63 | 原版世界的海平面 |
| retrogenPopulatedChunks | true | 已装饰的区块补生成群峦内容 |
| retrogenOtherMods | true | 补生成时把其他模组用标准接口注册的生成器也跑一遍 |
| retrogenChunksPerTick | 4 | 每个游戏刻最多补多少个区块 |
| nonTFCAnimals | true | 生成时成群刷出群峦动物 |

关于 `retrogenOtherMods`：

- 打开时，WorldPainter 地图也能长出其他模组的东西。但 Dynamic Trees 会给原版群系再种一遍树，森林会变密。不想要就关掉，只补群峦的内容。
- 拿一个已经被其他模组装饰过的旧存档来用时，必须关掉，不然其他模组的矿会重复生成。群峦自己的内容不会重复。

## 已知限制

- 矿石方块的底色是该位置的群峦岩石，嵌在原版石头里看得出是另一种石头。要做成原版石头底色，得给群峦新加一种岩石，按岩石生成方块的附属都会多出一套缺贴图的方块，所以没做。
- 原版石头不会塌方，也没有群峦岩石的采掘规则。石器时代靠地表散石。
- 群峦的咸水植物只长在群峦咸水里，原版海洋里没有。
- 群峦动物只在区块生成时出现，之后不会在原版群系里自然再刷新。
- 原版矿石（WorldPainter 铺的或原版生成的）仍然存在，能不能用取决于整合包配方。

## 测试

见 [verification/RESULTS.md](verification/RESULTS.md)。在 Forge 专用服务器、单人客户端和一个约 190 个模组的群峦整合包里测过。没有用真正的 WorldPainter 导出文件测过，也没测多人服务器。

## 从源码构建

本仓库只放改动过的 26 个群峦源码文件（其中 1 个新增），和官方源码的逐行对照见 [docs/changes-vs-tfc-1.7.23.181.diff](docs/changes-vs-tfc-1.7.23.181.diff)。

1. 把官方 `TerraFirmaCraft-MC1.12.2-1.7.23.181.jar` 放进 `libs/`。
2. 用 JDK 8 编译：

   ```bash
   ./gradlew build
   ```

3. 把编译出的类覆盖进官方 jar：

   ```bash
   python3 tools/make_jar.py libs/TerraFirmaCraft-MC1.12.2-1.7.23.181.jar build/libs/tfc-patch-classes-1.7.23.181-wp1.jar TerraFirmaCraft-MC1.12.2-1.7.23.181-wp1.jar
   ```

`make_jar.py` 会替换改动过的类，并删掉 jar 里 Forge 的注解缓存（`META-INF/fml_cache_*.json`），让 Forge 重新扫描。其余文件原样保留。

## 许可

TerraFirmaCraft 采用 [EUPL 1.2](LICENSE)，本改造版同样采用 EUPL 1.2。源码文件头保留了原作者的版权声明，改动处用"改造版"注释标出。

---

## English summary

An **unofficial** modification of TerraFirmaCraft 1.12.2 **1.7.23.181** (tag `v1.7.23.181`) that makes TFC generate its content — ore veins, loose rocks and ores, sticks, clay, peat, trees, plants, wild crops, berry bushes, fruit trees and animals — in overworlds that are **not** the TFC world type, such as maps made with WorldPainter. Vanilla stone, dirt, grass, sand and water are kept; TFC ores replace stone the way other mods' ores do. TFC heights are rescaled from sea level 144 to the vanilla sea level, vanilla water is drinkable, and TFC seeds can be planted on vanilla farmland. Regular TFC worlds behave the same as the official build.

Replace the official jar with the release jar on both server and client. Settings are in `config/TerraFirmaCraft - General.cfg` under `world` (look for `[WorldPainter edition]`). Not affiliated with the TerraFirmaCraft team; please do not report issues with this build to them. Licensed under EUPL 1.2.
