#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CleanYourCar - Car Head Unit TPMS Diagnostic Reader
Query live tire pressure from UIS7870 (FYT / Winbo WC Toyota CANbus)
"""

import sys
import time
import re
import argparse
import subprocess

def run_adb(cmd, device=None):
    dev_arg = f"-s {device}" if device else ""
    full_cmd = f"adb {dev_arg} {cmd}"
    res = subprocess.run(full_cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout.strip()

def get_target_device(device_arg):
    if device_arg:
        # Check connection
        subprocess.run(f"adb connect {device_arg}", shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return device_arg
    # Auto detect
    out = subprocess.run("adb devices", shell=True, stdout=subprocess.PIPE, text=True).stdout
    devices = [line.split()[0] for line in out.splitlines()[1:] if "\tdevice" in line]
    if not devices:
        print("[-] Error: No ADB device connected. Use --device <IP:PORT>")
        sys.exit(1)
    return devices[0]

def read_tire_pressure(device):
    print(f"[*] Target vehicle device: {device}")
    
    # Check if currently focused on tire pressure screen
    focus = run_adb("shell dumpsys window | grep mCurrentFocus", device)
    if "CamryTireActWC" not in focus and "MainActivity" not in focus:
        print("[*] Launching CANbus Vehicle Info Activity...")
        run_adb("shell am start -n com.syu.canbus/.ActivityLauncher", device)
        time.sleep(1.2)
        print("[*] Navigating to Tire Pressure page...")
        run_adb("shell input tap 600 1035", device)
        time.sleep(1.2)
    
    # Dump UI hierarchy
    run_adb("shell uiautomator dump /sdcard/tire_tmp.xml", device)
    xml_data = run_adb("shell cat /sdcard/tire_tmp.xml", device)
    
    pressures = re.findall(r'text="([0-9]+(?:\s*(?:Psi|kPa|bar)))"', xml_data)
    statuses = re.findall(r'text="(●?\s*正常|胎壓[^"]+)"', xml_data)
    
    fl_val = pressures[0] if len(pressures) > 0 else "N/A"
    fr_val = pressures[1] if len(pressures) > 1 else "N/A"
    rl_val = pressures[2] if len(pressures) > 2 else "N/A"
    rr_val = pressures[3] if len(pressures) > 3 else "N/A"
    
    fl_st = statuses[0] if len(statuses) > 0 else "正常"
    fr_st = statuses[1] if len(statuses) > 1 else "正常"
    rl_st = statuses[2] if len(statuses) > 2 else "正常"
    rr_st = statuses[3] if len(statuses) > 3 else "正常"
    
    print("\n" + "="*45)
    print("        🚗 Live TPMS Diagnostic Report         ")
    print("="*45)
    print(f"  [Front-Left  FL] : {fl_val:<10} ({fl_st})")
    print(f"  [Front-Right FR] : {fr_val:<10} ({fr_st})")
    print(f"  [Rear-Left   RL] : {rl_val:<10} ({rl_st})")
    print(f"  [Rear-Right  RR] : {rr_val:<10} ({rr_st})")
    print("="*45)
    print("""
            ┌─────────┐
    [FL] ───│  FRONT  │─── [FR]
    {:^9}│         │{:^9}
            │         │
    [RL] ───│  REAR   │─── [RR]
    {:^9}└─────────┘{:^9}
    """.format(fl_val, fr_val, rl_val, rr_val))
    print("="*45)

def main():
    parser = argparse.ArgumentParser(description="Vehicle TPMS Diagnostic Reader via ADB")
    parser.add_argument("--device", "-d", default=None, help="ADB target device (e.g. 192.168.1.100:5555)")
    args = parser.parse_args()
    
    device = get_target_device(args.device)
    read_tire_pressure(device)

if __name__ == "__main__":
    main()
