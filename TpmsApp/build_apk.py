#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Portable APK build script using Android SDK Command-line Tools (aapt2, d8, zipalign, apksigner)
Builds TpmsApp without requiring heavy Android Studio / Gradle.
"""

import os
import sys
import glob
import shutil
import zipfile
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))

def find_android_sdk():
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "/opt/homebrew/share/android-commandlinetools",
        os.path.expanduser("~/Library/Android/sdk"),
        os.path.expanduser("~/Android/Sdk"),
        "/usr/lib/android-sdk",
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None

def find_tool(tool_name, sdk_dir=None):
    # 1. Check PATH
    path = shutil.which(tool_name)
    if path:
        return path
    
    # 2. Check inside SDK
    if sdk_dir:
        # Search build-tools
        build_tools_dir = os.path.join(sdk_dir, "build-tools")
        if os.path.exists(build_tools_dir):
            versions = sorted(os.listdir(build_tools_dir), reverse=True)
            for v in versions:
                p = os.path.join(build_tools_dir, v, tool_name)
                if os.path.exists(p):
                    return p
        # Search in platform-tools / cmdline-tools
        for sub in ["platform-tools", "cmdline-tools/latest/bin", "tools/bin"]:
            p = os.path.join(sdk_dir, sub, tool_name)
            if os.path.exists(p):
                return p
    return None

def find_android_jar(sdk_dir):
    if not sdk_dir:
        return None
    platforms_dir = os.path.join(sdk_dir, "platforms")
    if not os.path.exists(platforms_dir):
        return None
    platforms = sorted(os.listdir(platforms_dir), reverse=True)
    for p in platforms:
        jar = os.path.join(platforms_dir, p, "android.jar")
        if os.path.exists(jar):
            return jar
    return None

def run_cmd(cmd, cwd=PROJECT_DIR, env=None):
    print(f">> {cmd}")
    res = subprocess.run(cmd, shell=True, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        raise RuntimeError(f"Command failed with code {res.returncode}: {cmd}")
    return res.stdout

def main():
    print("==================================================")
    print("  🚗 Building TpmsApp (CleanYourCar)")
    print("==================================================")
    
    sdk_dir = find_android_sdk()
    if not sdk_dir:
        print("[-] Error: Android SDK not found.")
        print("    Please set ANDROID_HOME or install Android Command-line Tools.")
        sys.exit(1)
    print(f"[+] Android SDK: {sdk_dir}")
    
    android_jar = find_android_jar(sdk_dir)
    if not android_jar:
        print("[-] Error: android.jar not found under SDK platforms.")
        sys.exit(1)
    print(f"[+] Android Framework: {android_jar}")
    
    aapt2 = find_tool("aapt2", sdk_dir)
    d8 = find_tool("d8", sdk_dir)
    zipalign = find_tool("zipalign", sdk_dir)
    apksigner = find_tool("apksigner", sdk_dir)
    
    missing = []
    if not aapt2: missing.append("aapt2")
    if not d8: missing.append("d8")
    if not zipalign: missing.append("zipalign")
    if not apksigner: missing.append("apksigner")
    if missing:
        print(f"[-] Error: Missing required CLI tools: {', '.join(missing)}")
        sys.exit(1)
        
    print(f"[+] Build Tools: aapt2, d8, zipalign, apksigner verified")
    
    env = os.environ.copy()
    if "JAVA_HOME" not in env and os.path.exists("/opt/homebrew/opt/openjdk"):
        env["JAVA_HOME"] = "/opt/homebrew/opt/openjdk"
        env["PATH"] = f"{env['JAVA_HOME']}/bin:" + env.get("PATH", "")
        
    build_dir = os.path.join(PROJECT_DIR, "build")
    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(os.path.join(build_dir, "obj"), exist_ok=True)
    
    # 1. Compile resources
    print("\n[1/6] Compiling resources with aapt2...")
    run_cmd(f'"{aapt2}" compile --dir res -o build/res.zip', env=env)
    
    # 2. Link resources & generate R.java
    print("[2/6] Linking resources with aapt2...")
    run_cmd(f'"{aapt2}" link -o build/unaligned.apk -I "{android_jar}" --manifest AndroidManifest.xml --java src build/res.zip --auto-add-overlay', env=env)
    
    # 3. Compile Java classes
    print("[3/6] Compiling Java classes with javac...")
    run_cmd(f'javac --release 11 -classpath "{android_jar}" -d build/obj src/com/car/tpms/*.java', env=env)
    
    # 4. Generate DEX bytecode
    print("[4/6] Generating classes.dex with d8...")
    run_cmd(f'"{d8}" --lib "{android_jar}" --output build/ build/obj/com/car/tpms/*.class', env=env)
    
    # Pack classes.dex into APK
    print("[5/6] Packaging DEX into APK...")
    with zipfile.ZipFile(os.path.join(build_dir, "unaligned.apk"), "a") as apk:
        apk.write(os.path.join(build_dir, "classes.dex"), "classes.dex")
        
    # Align APK
    run_cmd(f'"{zipalign}" -f -p 4 build/unaligned.apk build/aligned.apk', env=env)
    
    # 5. Sign APK with debug key
    print("[6/6] Signing APK...")
    keystore_path = os.path.join(PROJECT_DIR, "debug.keystore")
    if not os.path.exists(keystore_path):
        run_cmd(f'keytool -genkeypair -v -keystore "{keystore_path}" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"', env=env)
        
    out_apk = os.path.join(build_dir, "TpmsApp.apk")
    run_cmd(f'"{apksigner}" sign --ks "{keystore_path}" --ks-pass pass:android --out "{out_apk}" build/aligned.apk', env=env)
    
    size_kb = os.path.getsize(out_apk) / 1024
    print("\n" + "="*50)
    print(f"✔ Build SUCCESS!")
    print(f"  Target APK: {out_apk}")
    print(f"  File Size : {size_kb:.1f} KB")
    print("="*50)

if __name__ == "__main__":
    main()
