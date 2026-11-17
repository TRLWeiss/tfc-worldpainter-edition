#!/usr/bin/env python3
"""把编译好的改动类覆盖进原版群峦 jar，生成改造版群峦。
用法: make_jar.py <原版群峦jar> <改动类jar> <输出jar>"""
import json, sys, zipfile

original, patch, output = sys.argv[1:4]
with zipfile.ZipFile(patch) as pz:
    patched = {n: pz.read(n) for n in pz.namelist() if n.endswith('.class')}
outers = {n.split('$')[0].replace('.class', '') for n in patched}

replaced = removed = 0
with zipfile.ZipFile(original) as oz, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as out:
    for info in oz.infolist():
        name = info.filename
        if name in ('META-INF/fml_cache_annotation.json', 'META-INF/fml_cache_class_versions.json'):
            removed += 1
            continue  # 注解缓存删掉，让 Forge 重新扫描改过的类
        if name.endswith('.class') and name.split('$')[0].replace('.class', '') in outers:
            if name in patched:
                replaced += 1
            else:
                removed += 1  # 改过的类不再产生的旧内部类
            continue
        data = oz.read(name)
        if name == 'mcmod.info':
            info_json = json.loads(data.decode('utf-8'))
            info_json[0]['description'] += " [WorldPainter edition: also generates TFC content in non-TFC overworlds]"
            data = json.dumps(info_json, ensure_ascii=False, indent=2).encode('utf-8')
        out.writestr(info, data)
    for name, data in sorted(patched.items()):
        out.writestr(name, data)
print(f"改动类 {len(patched)} 个（替换 {replaced}，新增 {len(patched) - replaced}），去掉旧文件 {removed} 个 -> {output}")
