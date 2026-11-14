#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建 Cloud TV APK（不依赖 Gradle），Windows / macOS / Linux 通用。

用法:
    python tools/build_apk.py                     # 使用仓库内 sdk/
    CLOUDTV_BT=... CLOUDTV_ANDROID_JAR=... python tools/build_apk.py

需要:
    JDK 17                      CLOUDTV_JDK 或 sdk/jdk/jdk-*
    build-tools（aapt2/d8/zipalign/apksigner）
                                CLOUDTV_BT 或 sdk/bt/android-*
    android.jar                 CLOUDTV_ANDROID_JAR 或 sdk/plat/android-34/android.jar
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("CLOUDTV_SDK", os.path.join(ROOT, "sdk"))

VERSION_CODE = "16"
VERSION_NAME = "1.9.6"

WIN = os.name == "nt"
EXE = ".exe" if WIN else ""


def pick(base, *keywords):
    if not os.path.isdir(base):
        return None
    for name in sorted(os.listdir(base)):
        p = os.path.join(base, name)
        if os.path.isdir(p) and all(k in name for k in keywords):
            return p
    return None


def tool(dirpath, name):
    """兼容 Windows 的 .exe / .bat 与 Unix 的无后缀脚本"""
    if not dirpath:
        return name
    candidates = [name + EXE]
    if WIN:
        candidates.append(name + ".bat")
    candidates.append(name)
    for cand in candidates:
        p = os.path.join(dirpath, cand)
        if os.path.exists(p):
            return p
    return os.path.join(dirpath, name + EXE)


JDK = os.environ.get("CLOUDTV_JDK") or pick(os.path.join(SDK, "jdk"), "jdk-")
BT = os.environ.get("CLOUDTV_BT") or pick(os.path.join(SDK, "bt"), "android-")
PLATFORM = pick(os.path.join(SDK, "plat"), "android-34") or pick(os.path.join(SDK, "plat"), "android-")
ANDROID_JAR = os.environ.get("CLOUDTV_ANDROID_JAR") or (
    os.path.join(PLATFORM, "android.jar") if PLATFORM else None)

for label, val in (("JDK", JDK), ("build-tools", BT), ("android.jar", ANDROID_JAR)):
    if not val or not os.path.exists(val):
        sys.exit("找不到 %s，请按 README 放置 sdk/ 目录，或用环境变量指定。当前: %s" % (label, val))

JAVAC = tool(os.path.join(JDK, "bin"), "javac")
JAR = tool(os.path.join(JDK, "bin"), "jar")
KEYTOOL = tool(os.path.join(JDK, "bin"), "keytool")
AAPT2 = tool(BT, "aapt2")
D8 = tool(BT, "d8")
ZIPALIGN = tool(BT, "zipalign")
APKSIGNER = tool(BT, "apksigner")

# aapt2 对非 ASCII 路径不友好，统一在临时目录里构建
WORK = os.path.join(tempfile.gettempdir(), "cloudtv_build")
APP_SRC = os.path.join(ROOT, "app")
APP = os.path.join(WORK, "app")
BUILD = os.path.join(WORK, "build")

shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(APP, exist_ok=True)
shutil.copytree(APP_SRC, APP, dirs_exist_ok=True)
os.makedirs(BUILD, exist_ok=True)
LOCAL_JAR = os.path.join(WORK, "android.jar")
shutil.copy(ANDROID_JAR, LOCAL_JAR)

env = dict(os.environ)
env["JAVA_HOME"] = JDK
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")


def run(cmd):
    print(">>", os.path.basename(cmd[0]), " ".join(cmd[1:])[:120])
    p = subprocess.run(cmd, env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (p.stdout or "") + (p.stderr or "")
    if out.strip():
        print(out.strip()[:2000])
    if p.returncode != 0:
        sys.exit("失败: %s" % cmd[0])


# 1) 资源
res_zip = os.path.join(BUILD, "res.zip")
run([AAPT2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip])

# 2) 链接 + R.java
gen = os.path.join(BUILD, "gen")
base_apk = os.path.join(BUILD, "base.apk")
run([AAPT2, "link",
     "-o", base_apk,
     "-I", LOCAL_JAR,
     "--manifest", os.path.join(APP, "AndroidManifest.xml"),
     "-R", res_zip,
     "-A", os.path.join(APP, "assets"),
     "--java", gen,
     "--min-sdk-version", "21",
     "--target-sdk-version", "29",
     "--version-code", VERSION_CODE,
     "--version-name", VERSION_NAME,
     "--auto-add-overlay"])

# 3) javac
classes = os.path.join(BUILD, "classes")
os.makedirs(classes, exist_ok=True)
srcs = []
for base in (os.path.join(APP, "src"), gen):
    for r, _, files in os.walk(base):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(r, f))
run([JAVAC, "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-nowarn",
     "-classpath", LOCAL_JAR, "-d", classes] + srcs)

# 4) d8（先打 jar，避免命令行过长）
dexdir = os.path.join(BUILD, "dex")
os.makedirs(dexdir, exist_ok=True)
classes_jar = os.path.join(BUILD, "classes.jar")
run([JAR, "cf", classes_jar, "-C", classes, "."])
run([D8, "--release", "--min-api", "21", "--lib", LOCAL_JAR, "--output", dexdir, classes_jar])

# 5) 打包
unsigned = os.path.join(BUILD, "unsigned.apk")
shutil.copy(base_apk, unsigned)
with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(dexdir, "classes.dex"), "classes.dex")

# 6) 对齐
aligned = os.path.join(BUILD, "aligned.apk")
run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])

# 7) 签名（没有自己的 keystore 就现场生成一个）
ks = os.environ.get("CLOUDTV_KEYSTORE") or os.path.join(WORK, "cloudtv.jks")
if not os.path.exists(ks):
    run([KEYTOOL, "-genkeypair",
         "-keystore", ks, "-alias", "cloudtv", "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10950", "-storepass", "123456", "-keypass", "123456",
         "-dname", "CN=CloudTV, OU=Dev, O=CloudTV, L=CN, ST=CN, C=CN"])

out_apk = os.path.join(WORK, "CloudTV-%s.apk" % VERSION_NAME)
run([APKSIGNER, "sign",
     "--ks", ks, "--ks-key-alias", "cloudtv",
     "--ks-pass", "pass:123456", "--key-pass", "pass:123456",
     "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
     "--out", out_apk, aligned])
run([APKSIGNER, "verify", "--verbose", out_apk])

final = os.path.join(ROOT, "CloudTV-%s.apk" % VERSION_NAME)
shutil.copy(out_apk, final)
print("OK ->", final, os.path.getsize(final), "bytes")
