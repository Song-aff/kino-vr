//! 系统主音量静音（Windows CoreAudio，通过 PowerShell 子进程）。
//!
//! 背景：Sunshine 只有在存在 `virtual_sink` 虚拟音频设备时才能「只串流、静音主机」
//! （`src/audio.cpp`: "Prefer the virtual sink if host playback is disabled"）。
//! 本机没有装 VB-CABLE / Steam Streaming Speakers，所以 `localAudioPlayMode=0`
//! 仍会从真实声卡出声。这里不改 Sunshine 配置、不装驱动，改由 PC 管理端在
//! 串流会话开始时静音系统主音量、结束时恢复。
//!
//! 零第三方依赖：CoreAudio 的 `IAudioEndpointVolume` 不是 IDispatch，PowerShell
//! 无法后期绑定，因此用 `Add-Type` 定义 COM 接口的 vtable 顺序后调用。
//! 每次调用启动一个隐藏的 PowerShell 进程（约 0.5s），只在会话状态翻转时触发。

use crate::util::{app_dir, log, run_hidden};

/// C# COM 互操作定义。接口方法顺序必须与 Windows SDK 的 vtable 完全一致，
/// 只列出到 GetMute 为止（后面的方法用不到，按 vtable 顺序省略是安全的）。
const CS: &str = r#"using System;
using System.Runtime.InteropServices;
[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioEndpointVolume {
  int RegisterControlChangeNotify(IntPtr pNotify);
  int UnregisterControlChangeNotify(IntPtr pNotify);
  int GetChannelCount(out int pnChannelCount);
  int SetMasterVolumeLevel(float fLevelDB, ref Guid pguidEventContext);
  int SetMasterVolumeLevelScalar(float fLevel, ref Guid pguidEventContext);
  int GetMasterVolumeLevel(out float pfLevelDB);
  int GetMasterVolumeLevelScalar(out float pfLevel);
  int SetChannelVolumeLevel(uint nChannel, float fLevelDB, ref Guid pguidEventContext);
  int SetChannelVolumeLevelScalar(uint nChannel, float fLevel, ref Guid pguidEventContext);
  int GetChannelVolumeLevel(uint nChannel, out float pfLevelDB);
  int GetChannelVolumeLevelScalar(uint nChannel, out float pfLevel);
  int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, ref Guid pguidEventContext);
  int GetMute([MarshalAs(UnmanagedType.Bool)] out bool pbMute);
}
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDevice {
  int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
  int OpenPropertyStore(int stgmAccess, out IntPtr ppProperties);
  int GetId(out IntPtr ppstrId);
  int GetState(out int pdwState);
}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDeviceEnumerator {
  int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IntPtr ppDevices);
  int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint);
  int GetDevice(IntPtr pwstrId, out IMMDevice ppDevice);
  int RegisterEndpointNotificationCallback(IntPtr pClient);
  int UnregisterEndpointNotificationCallback(IntPtr pClient);
}
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
public class MMDeviceEnumerator { }
public static class VrAudio {
  static IAudioEndpointVolume GetVol() {
    var e = (IMMDeviceEnumerator)new MMDeviceEnumerator();
    IMMDevice dev;
    int hr = e.GetDefaultAudioEndpoint(0, 0, out dev);
    if (hr != 0) Marshal.ThrowExceptionForHR(hr);
    Guid iid = typeof(IAudioEndpointVolume).GUID;
    object o;
    hr = dev.Activate(ref iid, 23, IntPtr.Zero, out o);
    if (hr != 0) Marshal.ThrowExceptionForHR(hr);
    return (IAudioEndpointVolume)o;
  }
  public static bool GetMute() { bool m; int hr = GetVol().GetMute(out m); if (hr != 0) Marshal.ThrowExceptionForHR(hr); return m; }
  public static void SetMute(bool value) { Guid g = Guid.Empty; int hr = GetVol().SetMute(value, ref g); if (hr != 0) Marshal.ThrowExceptionForHR(hr); }
}"#;

fn invoke(op: &str) -> Option<String> {
    // PowerShell here-string 的结束标记 '@ 必须在行首，这里用 \n 保证。
    let action = match op {
        "mute" => "$was = [VrAudio]::GetMute(); if (-not $was) { [VrAudio]::SetMute($true) }; if ($was) { 'WAS=1' } else { 'WAS=0' }",
        "unmute" => "[VrAudio]::SetMute($false); 'SET=0'",
        _ => "if ([VrAudio]::GetMute()) { 'MUTED=1' } else { 'MUTED=0' }",
    };
    let script = format!("Add-Type -TypeDefinition @'\n{}\n'@\n{}", CS, action);
    let out = run_hidden("powershell", &["-NoProfile", "-NonInteractive", "-Command", &script])?;
    if !out.status.success() {
        log(&format!("音量控制失败: {}", String::from_utf8_lossy(&out.stderr).trim()));
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

/// 读取当前系统主静音状态。
pub fn get_muted() -> Option<bool> {
    let text = invoke("get")?;
    if text.contains("MUTED=1") { Some(true) }
    else if text.contains("MUTED=0") { Some(false) }
    else { None }
}

/// 串流开始时调用。返回 Some(原本是否已静音)：
/// `Some(false)` = 原本有声、已由我们静音；`Some(true)` = 用户自己已静音，不接管。
pub fn mute_if_unmuted() -> Option<bool> {
    let text = invoke("mute")?;
    if text.contains("WAS=1") { Some(true) }
    else if text.contains("WAS=0") { Some(false) }
    else { None }
}

/// 恢复系统主音量（仅在确认是由我们静音时调用）。
pub fn unmute() -> bool {
    matches!(invoke("unmute"), Some(text) if text.contains("SET=0"))
}

/// 供 `--audio-selftest` 使用：读 → 静音 → 读 → 恢复 → 读，最终状态不变。
pub fn selftest() -> String {
    let before = get_muted();
    let changed = mute_if_unmuted();
    let during = get_muted();
    let restored = unmute();
    let after = get_muted();
    let text = format!("before={:?} changed={:?} during={:?} restored={} after={:?}",
        before, changed, during, restored, after);
    let _ = std::fs::write(app_dir().join("audio-selftest.txt"), &text);
    text
}
