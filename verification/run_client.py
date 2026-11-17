#!/usr/bin/env python3
"""用命令行启动本机 Forge 客户端（arm64 Mac），自动进入指定存档做验证。
用法: run_client.py <游戏目录> <存档文件夹名> <日志文件> [版本名，默认 1.12.2-Forge] [最大内存，默认 4G]"""
import json, os, subprocess, sys

game_dir, world, log_path = sys.argv[1], sys.argv[2], sys.argv[3]
version = sys.argv[4] if len(sys.argv) > 4 else "1.12.2-Forge"
memory = sys.argv[5] if len(sys.argv) > 5 else "4G"
mc = os.path.expanduser("~/Library/Application Support/minecraft")
ver = os.path.join(mc, "versions", version)
meta = json.load(open(os.path.join(ver, version + ".json"), encoding="utf-8"))
lib_root = os.path.join(mc, "libraries")

def allowed(lib):
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for rule in rules:
        os_name = rule.get("os", {}).get("name")
        if os_name is None or os_name == "osx":
            ok = rule["action"] == "allow"
    return ok

classpath = []
for lib in meta["libraries"]:
    if not allowed(lib) or "natives" in lib:
        continue
    parts = lib["name"].split(":")
    group, name, libver = parts[:3]
    classifier = parts[3] if len(parts) > 3 else None
    # arm64 上照 HMCL 的做法替换两个库
    if name == "java-objc-bridge":
        group, libver = "org.glavo.hmcl.mmachina", "1.1.0-mmachina.1"
    if name == "text2speech":
        libver = "1.11.3"
    if group == "optifine" and classifier == "installer":
        classifier = None  # HMCL 启动时用的是解出来的 OptiFine 本体
    file = f"{name}-{libver}-{classifier}.jar" if classifier else f"{name}-{libver}.jar"
    path = os.path.join(lib_root, group.replace(".", "/"), name, libver, file)
    if not os.path.isfile(path):
        print("缺库", path)
        continue
    if path not in classpath:
        classpath.append(path)
# 游戏本体要排在 OptiFine 前面，否则 Forge 校验原版类时读到的是 OptiFine 改过的类
optifine = [c for c in classpath if "/optifine/" in c]
classpath = [c for c in classpath if "/optifine/" not in c] + [os.path.join(ver, version + ".jar")] + optifine

java = "/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/bin/java"
cmd = [java, "-Xmx" + memory, "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC",
       "-Djava.library.path=" + os.path.join(ver, "natives-macos-arm64"),
       "-Dtfcwptest.world=" + world, "-Dtfcwptest.checks=true",
       "-Dfml.ignoreInvalidMinecraftCertificates=true",
       "-cp", os.pathsep.join(classpath), meta["mainClass"],
       ] + [a for t in meta["minecraftArguments"].split("--tweakClass ")[1:] for a in ("--tweakClass", t.split()[0])] + [
       "--username", "tester", "--version", version, "--gameDir", game_dir,
       "--assetsDir", os.path.join(mc, "assets"), "--assetIndex", "1.12",
       "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userType", "legacy",
       "--versionType", "Forge", "--width", "1280", "--height", "720"]
with open(log_path, "w", encoding="utf-8") as log:
    code = subprocess.call(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT, timeout=1500)
print("客户端退出码", code)
