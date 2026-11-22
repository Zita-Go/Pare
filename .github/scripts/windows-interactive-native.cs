// Test-only Win32 adapter. Compiled in the acceptance PowerShell process, never shipped in Step.
// API contracts: https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-sendinput
// https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-openinputdesktop
// https://learn.microsoft.com/windows/win32/termserv/wm-wtssession-change
// TSF ABI/constants: microsoft/win32metadata, generation/WinSDK/RecompiledIdlHeaders/um/msctf.h
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;
using System.Windows.Forms;

public static class StepAcceptanceNative {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct MouseInput { public int X,Y; public uint Data,Flags,Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)] struct KeyInput { public ushort Key,Scan; public uint Flags,Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Explicit)] struct InputUnion {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyInput Keyboard;
    }
    [StructLayout(LayoutKind.Sequential)] struct Input { public uint Type; public InputUnion Data; }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct DisplayDevice {
        public int Size;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string Name;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Description;
        public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Id;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Key;
    }
    delegate bool EnumProc(IntPtr window, IntPtr parameter);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback, IntPtr parameter);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int w, int h, uint flags);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window, uint message, IntPtr wparam, IntPtr lparam);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window, StringBuilder text, int count);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window, StringBuilder text, int count);
    [DllImport("user32.dll", SetLastError=true)] static extern uint SendInput(uint count, Input[] input, int size);
    [DllImport("user32.dll", SetLastError=true)] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool GetUserObjectInformation(IntPtr h, int index, StringBuilder text, uint size, out uint needed);
    [DllImport("user32.dll")] static extern IntPtr GetThreadDesktop(uint thread);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll", SetLastError=true)] static extern bool WTSQuerySessionInformation(IntPtr server, int session, int infoClass, out IntPtr buffer, out int bytes);
    [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr buffer);
    [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr window);
    [DllImport("user32.dll")] static extern IntPtr GetWindowDpiAwarenessContext(IntPtr window);
    [DllImport("user32.dll")] static extern int GetAwarenessFromDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("user32.dll")] static extern int GetKeyboardLayoutList(int count, [Out] IntPtr[] layouts);
    [DllImport("user32.dll")] static extern IntPtr ActivateKeyboardLayout(IntPtr layout, uint flags);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool EnumDisplayDevices(string device, uint number, ref DisplayDevice details, uint flags);
    [DllImport("user32.dll", SetLastError=true)] public static extern bool LockWorkStation();
    [DllImport("wtsapi32.dll", SetLastError=true)] static extern bool WTSRegisterSessionNotification(IntPtr window, uint flags);
    [DllImport("wtsapi32.dll")] static extern bool WTSUnRegisterSessionNotification(IntPtr window);

    public sealed class WindowInfo { public long Handle; public string Title; public int Width, Height; public bool Foreground; public uint Dpi; public int DpiAwareness; }
    public static WindowInfo[] Windows(int process) {
        var result=new List<WindowInfo>();
        EnumWindows(delegate(IntPtr w, IntPtr p) {
            uint pid; GetWindowThreadProcessId(w,out pid);
            if(pid==process && IsWindowVisible(w)) {
                var text=new StringBuilder(256); GetWindowText(w,text,text.Capacity); Rect r; GetWindowRect(w,out r);
                result.Add(new WindowInfo {Handle=w.ToInt64(),Title=text.ToString(),Width=r.Right-r.Left,Height=r.Bottom-r.Top,
                    Foreground=GetForegroundWindow()==w,Dpi=GetDpiForWindow(w),DpiAwareness=GetAwarenessFromDpiAwarenessContext(GetWindowDpiAwarenessContext(w))});
            }
            return true;
        },IntPtr.Zero);
        return result.ToArray();
    }
    static string DesktopName(IntPtr handle) {
        var name=new StringBuilder(256); uint needed;
        return GetUserObjectInformation(handle,2,name,512,out needed) ? name.ToString() : null;
    }
    public sealed class DesktopInfo { public bool UserInteractive, InputDesktopAccessible; public int SessionId, WtsState, LastError; public uint ConsoleSessionId; public string InputDesktop, ThreadDesktop; }
    public static DesktopInfo Desktop() {
        var result=new DesktopInfo {UserInteractive=Environment.UserInteractive,SessionId=Process.GetCurrentProcess().SessionId,
            ConsoleSessionId=WTSGetActiveConsoleSessionId(),WtsState=-1,ThreadDesktop=DesktopName(GetThreadDesktop(GetCurrentThreadId()))};
        IntPtr buffer; int size;
        if(WTSQuerySessionInformation(IntPtr.Zero,result.SessionId,8,out buffer,out size)) {
            try {result.WtsState=Marshal.ReadInt32(buffer);} finally {WTSFreeMemory(buffer);}
        }
        var desktop=OpenInputDesktop(0,false,1);
        result.InputDesktopAccessible=desktop!=IntPtr.Zero;
        if(desktop!=IntPtr.Zero) {try {result.InputDesktop=DesktopName(desktop);} finally {CloseDesktop(desktop);}}
        else result.LastError=Marshal.GetLastWin32Error();
        return result;
    }
    public static string[] Displays() {
        var result=new List<string>();
        for(uint n=0;n<32;n++) {var d=new DisplayDevice();d.Size=Marshal.SizeOf(d);if(!EnumDisplayDevices(null,n,ref d,0))break;
            if((d.Flags&1)!=0)result.Add(d.Name+" | "+d.Description+" | flags="+d.Flags);}
        return result.ToArray();
    }
    static Input Key(ushort key, ushort scan, uint flags) {return new Input {Type=1,Data=new InputUnion {Keyboard=new KeyInput {Key=key,Scan=scan,Flags=flags}}};}
    static void Send(Input[] input) {
        if(SendInput((uint)input.Length,input,Marshal.SizeOf(typeof(Input)))!=input.Length)
            throw new InvalidOperationException("SendInput did not insert every event; Win32="+Marshal.GetLastWin32Error());
    }
    public static void Chord(ushort[] keys) {
        var events=new List<Input>();foreach(var key in keys)events.Add(Key(key,0,0));
        for(int n=keys.Length-1;n>=0;n--)events.Add(Key(keys[n],0,2));Send(events.ToArray());
    }
    public static void UnicodeText(string text) {
        // KEYEVENTF_UNICODE is deliberately separate from IME typing.
        foreach(char c in text)Send(new [] {Key(0,c,4),Key(0,c,6)});
    }
    public static void AsciiKeys(string text) {
        foreach(char c in text) {if(c<'a'||c>'z')throw new ArgumentException("Lowercase Latin letters only");Chord(new [] {(ushort)Char.ToUpperInvariant(c)});Thread.Sleep(110);}
    }
    public static long WindowKeyboardLayout(IntPtr window) {uint pid;return GetKeyboardLayout(GetWindowThreadProcessId(window,out pid)).ToInt64();}
    public static bool RequestChineseInput(IntPtr window) {
        var layouts=new IntPtr[32];int count=GetKeyboardLayoutList(layouts.Length,layouts);
        for(int n=0;n<count;n++)if((layouts[n].ToInt64()&0xffff)==0x804) {
            // TSF ActivateProfile requires a matching current language. Change only to an
            // already-loaded locale, then request the same language for the actual Step thread.
            ActivateKeyboardLayout(layouts[n],0);
            return PostMessage(window,0x50,IntPtr.Zero,layouts[n]);
        }
        return false;
    }
    public static bool CandidateVisible() {
        bool found=false;
        EnumWindows(delegate(IntPtr w,IntPtr p) {if(IsWindowVisible(w)) {var s=new StringBuilder(256);GetClassName(w,s,256);
            if(s.ToString().IndexOf("Candidate",StringComparison.OrdinalIgnoreCase)>=0)found=true;}return true;},IntPtr.Zero);
        return found;
    }
    public static bool VisibleNotificationText(string marker, int timeoutMillis) {
        bool found=false;
        var worker=new Thread(delegate() {try {
            var condition=new PropertyCondition(AutomationElement.NameProperty,marker);
            var matches=AutomationElement.RootElement.FindAll(TreeScope.Descendants,condition);
            for(int n=0;n<matches.Count;n++) {var current=matches[n].Current;
                if(!current.IsOffscreen&&!current.BoundingRectangle.IsEmpty) {found=true;break;}}
        }catch {} });worker.IsBackground=true;worker.SetApartmentState(ApartmentState.MTA);worker.Start();
        return worker.Join(timeoutMillis)&&found;
    }
    public static void CaptureWindow(IntPtr window, string path) {
        if(GetForegroundWindow()!=window)throw new InvalidOperationException("Screenshot requires foreground Step window");
        Rect r;GetWindowRect(window,out r);
        using(var bitmap=new Bitmap(r.Right-r.Left,r.Bottom-r.Top)) {using(var g=Graphics.FromImage(bitmap))g.CopyFromScreen(r.Left,r.Top,0,0,bitmap.Size);bitmap.Save(path,System.Drawing.Imaging.ImageFormat.Png);}
    }
    [ComImport,Guid("71c6e74c-0f28-11d8-a82a-00065b84435c"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface Profiles {
        [PreserveSig] int ActivateProfile(uint type, ushort language, ref Guid clsid, ref Guid profile, IntPtr hkl, uint flags);
    }
    public static int ActivatePinyin() {
        object manager=Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("33c53a50-f456-4884-b049-85fd643ecfed")));
        try {var clsid=new Guid("81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E");var profile=new Guid("FA550B04-5AD7-411F-A5AC-CA038EC515D7");
            return ((Profiles)manager).ActivateProfile(1,0x804,ref clsid,ref profile,IntPtr.Zero,0x20000000);
        }finally {Marshal.ReleaseComObject(manager);}
    }
    public static int RestoreKeyboard(long layout) {
        object manager=Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("33c53a50-f456-4884-b049-85fd643ecfed")));
        try {var zero=Guid.Empty;return ((Profiles)manager).ActivateProfile(2,(ushort)(layout&0xffff),ref zero,ref zero,new IntPtr(layout),0x20000000);}
        finally {Marshal.ReleaseComObject(manager);}
    }
    public sealed class LockObserver : NativeWindow, IDisposable {
        public bool Registered, Locked;
        public LockObserver() {CreateHandle(new CreateParams());Registered=WTSRegisterSessionNotification(Handle,0);}
        protected override void WndProc(ref Message m) {
            if(m.Msg==0x2b1&&m.WParam.ToInt32()==7&&m.LParam.ToInt32()==Process.GetCurrentProcess().SessionId)Locked=true;
            base.WndProc(ref m);
        }
        public bool WaitForLock(int timeoutMillis) {
            var timer=Stopwatch.StartNew();while(!Locked&&timer.ElapsedMilliseconds<timeoutMillis) {Application.DoEvents();Thread.Sleep(50);}return Locked;
        }
        public void Dispose() {if(Registered)WTSUnRegisterSessionNotification(Handle);DestroyHandle();}
    }
}
