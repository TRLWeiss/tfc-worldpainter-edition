#!/usr/bin/env python3
"""启动 Forge 专用服务器，等它开服完成后依次发控制台命令，最后 stop。
用法: run_server.py <服务器目录> <日志文件> [命令1] [命令2] ...
命令里写 wait:秒数 表示等待。"""
import subprocess, sys, time, threading

server_dir, log_path, commands = sys.argv[1], sys.argv[2], sys.argv[3:]
java = "/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home/bin/java"
proc = subprocess.Popen([java, "-Xmx3G", "-jar", "forge-1.12.2-14.23.5.2860.jar", "nogui"],
                        cwd=server_dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT, text=True, bufsize=1)
done = threading.Event()

def pump():
    with open(log_path, "w", encoding="utf-8") as log:
        for line in proc.stdout:
            log.write(line)
            log.flush()
            if "Done (" in line and "For help" in line:
                done.set()

threading.Thread(target=pump, daemon=True).start()
deadline = time.time() + 900
while not done.is_set() and proc.poll() is None and time.time() < deadline:
    time.sleep(1)
if not done.is_set():
    print("服务器没有正常开服，退出码", proc.poll())
    proc.kill()
    sys.exit(1)
for command in commands:
    if command.startswith("wait:"):
        time.sleep(float(command[5:]))
        continue
    proc.stdin.write(command + "\n")
    proc.stdin.flush()
    time.sleep(2)
proc.stdin.write("stop\n")
proc.stdin.flush()
try:
    proc.wait(timeout=180)
except subprocess.TimeoutExpired:
    proc.kill()
print("服务器已停止，退出码", proc.returncode)
