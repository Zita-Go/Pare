// Test-only Win32 adapter. Compiled in the acceptance PowerShell process, never shipped in Step.
// API contracts: https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-sendinput
// https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-openinputdesktop
// https://learn.microsoft.com/windows/win32/termserv/wm-wtssession-change
// TSF ABI/constants: microsoft/win32metadata, generation/WinSDK/RecompiledIdlHeaders/um/msctf.h
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Automation;
using System.Windows.Forms;

public static class StepAcceptanceNative {
    // Use the .NET-created Process/OS handle directly. PowerShell 5.1 Start-Process can
    // otherwise return a wrapper whose ExitCode is unavailable after a short-lived child.
    // Drain both redirected pipes concurrently, including after the 256 KiB log limit.
    public sealed class ProcessLog {
        public readonly Process Process;
        public readonly string StandardOutputPath, StandardErrorPath;
        readonly Task outputTask,errorTask;
        static async Task Pump(Stream source,string path) {
            using(var file=new FileStream(path,FileMode.Create,FileAccess.Write,FileShare.Read,4096,true)) {
                byte[] buffer=new byte[4096];int total=0,count;
                while((count=await source.ReadAsync(buffer,0,buffer.Length).ConfigureAwait(false))>0) {
                    int keep=Math.Min(count,Math.Max(0,262144-total));
                    if(keep>0) {await file.WriteAsync(buffer,0,keep).ConfigureAwait(false);await file.FlushAsync().ConfigureAwait(false);total+=keep;}
                }
            }
        }
        public ProcessLog(string executable,string arguments,string stdout,string stderr,bool cleanPowerShellModules) {
            StandardOutputPath=stdout;StandardErrorPath=stderr;
            var info=new ProcessStartInfo(executable,arguments) {UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true};
            if(cleanPowerShellModules)info.EnvironmentVariables["PSModulePath"]=Path.Combine(Path.GetDirectoryName(executable),"Modules");
            Process=new Process {StartInfo=info};
            if(!Process.Start())throw new InvalidOperationException("Process.Start returned false");
            if(Process.Handle==IntPtr.Zero)throw new InvalidOperationException("Started process has no retained handle");
            outputTask=Pump(Process.StandardOutput.BaseStream,stdout);errorTask=Pump(Process.StandardError.BaseStream,stderr);
        }
        public int ReadExitCode() {Process.Refresh();if(!Process.HasExited)throw new InvalidOperationException("Process has not exited");return Process.ExitCode;}
        public bool Flush(int timeoutMillis) {return Task.WaitAll(new [] {outputTask,errorTask},timeoutMillis);}
    }
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct Point { public int X,Y; }
    [StructLayout(LayoutKind.Sequential)] struct GuiThreadInfo {
        public int Size; public uint Flags;
        public IntPtr Active,Focus,Capture,MenuOwner,MoveSize,Caret; public Rect CaretRect;
    }
    [StructLayout(LayoutKind.Sequential)] struct MonitorInfo { public int Size; public Rect Monitor, Work; public uint Flags; }
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
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr window,out Rect rect);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr window,ref Point point);
    [DllImport("user32.dll")] static extern bool IsChild(IntPtr parent,IntPtr child);
    [DllImport("user32.dll",SetLastError=true)] static extern bool GetGUIThreadInfo(uint thread,ref GuiThreadInfo info);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr window, uint attribute, out Rect rect, int size);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr window,uint flags);
    [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool GetMonitorInfo(IntPtr monitor,ref MonitorInfo info);
    [DllImport("user32.dll",SetLastError=true)] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll")] public static extern IntPtr GetOpenClipboardWindow();
    [DllImport("user32.dll")] public static extern IntPtr GetClipboardOwner();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int w, int h, uint flags);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window, uint message, IntPtr wparam, IntPtr lparam);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    public static int WindowProcessId(IntPtr window) {uint processId;GetWindowThreadProcessId(window,out processId);return (int)processId;}
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

    public sealed class WindowBounds {
        public Rect WindowRect, VisibleFrame, MonitorBounds, WorkingArea;
        public bool GeometryAvailable, PhysicalPixels, UsedDwm, FitsWorkingArea;
        public int DwmHresult, OverflowLeft, OverflowTop, OverflowRight, OverflowBottom;
        public string BoundsSource, Error;
    }
    public sealed class InputSurfaceState {
        public bool Foreground,NativeFocusWithinWindow;
        public long FocusWindow; public string FocusClass,Error;
        public int Width,Height,DistinctColors,SampledPixels,ChangedPixels;
        internal int[] Pixels;
    }
    public static InputSurfaceState InputSurface(IntPtr window,InputSurfaceState previous) {
        var state=new InputSurfaceState();IntPtr context=IntPtr.Zero;
        try {
            state.Foreground=GetForegroundWindow()==window;
            uint pid;uint thread=GetWindowThreadProcessId(window,out pid);
            var gui=new GuiThreadInfo();gui.Size=Marshal.SizeOf(gui);
            if(!GetGUIThreadInfo(thread,ref gui)) {state.Error="GetGUIThreadInfo failed";return state;}
            state.FocusWindow=gui.Focus.ToInt64();
            state.NativeFocusWithinWindow=gui.Focus==window||IsChild(window,gui.Focus);
            var name=new StringBuilder(128);GetClassName(gui.Focus,name,name.Capacity);state.FocusClass=name.ToString();
            if(!state.Foreground)return state;
            context=SetThreadDpiAwarenessContext(new IntPtr(-4));
            if(context==IntPtr.Zero)context=SetThreadDpiAwarenessContext(new IntPtr(-3));
            if(context==IntPtr.Zero){state.Error="Physical-pixel context unavailable";return state;}
            Rect client;var origin=new Point();
            if(!GetClientRect(window,out client)||!ClientToScreen(window,ref origin)){state.Error="Client geometry unavailable";return state;}
            state.Width=client.Right-client.Left;state.Height=client.Bottom-client.Top;
            if(state.Width<100||state.Height<100)return state;
            state.Pixels=new int[48*32];var colors=new HashSet<int>();int index=0;
            using(var bitmap=new Bitmap(state.Width,state.Height)) {
                using(var graphics=Graphics.FromImage(bitmap))graphics.CopyFromScreen(origin.X,origin.Y,0,0,bitmap.Size);
                for(int y=0;y<32;y++)for(int x=0;x<48;x++) {
                    int color=bitmap.GetPixel((2*x+1)*state.Width/96,(2*y+1)*state.Height/64).ToArgb()&0x00f8f8f8;
                    state.Pixels[index++]=color;colors.Add(color);
                }
            }
            state.SampledPixels=state.Pixels.Length;state.DistinctColors=colors.Count;
            state.ChangedPixels=state.SampledPixels;
            if(previous!=null&&previous.Pixels!=null&&previous.Width==state.Width&&previous.Height==state.Height) {
                state.ChangedPixels=0;for(int i=0;i<state.Pixels.Length;i++)if(state.Pixels[i]!=previous.Pixels[i])state.ChangedPixels++;
            }
        } catch(Exception error){state.Error=error.GetType().Name+": "+error.Message;}
        finally{if(context!=IntPtr.Zero)SetThreadDpiAwarenessContext(context);}
        return state;
    }
    public static WindowBounds Bounds(IntPtr window) {
        var result=new WindowBounds();IntPtr previous=IntPtr.Zero;
        try {
            // DWM extended frames are physical pixels, whereas GetWindowRect can be DPI
            // virtualized. Read every rectangle under a temporary per-monitor-aware context
            // on this test thread; never change the target app or the system's scale.
            previous=SetThreadDpiAwarenessContext(new IntPtr(-4));
            if(previous==IntPtr.Zero)previous=SetThreadDpiAwarenessContext(new IntPtr(-3));
            result.PhysicalPixels=previous!=IntPtr.Zero;
            if(!result.PhysicalPixels) {result.Error="Cannot establish physical-pixel coordinate context";return result;}
            Rect outer;
            if(!GetWindowRect(window,out outer)) {result.Error="GetWindowRect failed";return result;}
            result.WindowRect=outer;
            Rect frame;result.DwmHresult=DwmGetWindowAttribute(window,9,out frame,Marshal.SizeOf(typeof(Rect)));
            result.UsedDwm=result.DwmHresult==0&&frame.Right>frame.Left&&frame.Bottom>frame.Top;
            result.VisibleFrame=result.UsedDwm?frame:outer;
            result.BoundsSource=result.UsedDwm?"DWM extended frame bounds (invisible resize border excluded)":"GetWindowRect fallback (includes resize border; strict zero tolerance)";
            var monitor=MonitorFromWindow(window,2);var info=new MonitorInfo();info.Size=Marshal.SizeOf(info);
            if(monitor==IntPtr.Zero||!GetMonitorInfo(monitor,ref info)) {result.Error="Monitor working area unavailable";return result;}
            result.MonitorBounds=info.Monitor;result.WorkingArea=info.Work;
            var visible=result.VisibleFrame;
            result.GeometryAvailable=visible.Right>visible.Left&&visible.Bottom>visible.Top&&info.Work.Right>info.Work.Left&&info.Work.Bottom>info.Work.Top;
            result.OverflowLeft=Math.Max(0,info.Work.Left-visible.Left);result.OverflowTop=Math.Max(0,info.Work.Top-visible.Top);
            result.OverflowRight=Math.Max(0,visible.Right-info.Work.Right);result.OverflowBottom=Math.Max(0,visible.Bottom-info.Work.Bottom);
            result.FitsWorkingArea=result.GeometryAvailable&&result.OverflowLeft==0&&result.OverflowTop==0&&result.OverflowRight==0&&result.OverflowBottom==0;
        } catch(Exception error) {result.Error=error.GetType().Name+": "+error.Message;}
        finally {if(previous!=IntPtr.Zero)SetThreadDpiAwarenessContext(previous);}
        return result;
    }
    public sealed class WindowInfo { public long Handle; public uint ProcessId; public string Title, ClassName; public int Width, Height; public bool Foreground, Visible; public uint Dpi; public int DpiAwareness; public WindowBounds Bounds; }
    public static WindowInfo[] Windows(int process) {return Windows(process,true);}
    public static WindowInfo[] Windows(int process,bool visibleOnly) {
        var result=new List<WindowInfo>();
        EnumWindows(delegate(IntPtr w, IntPtr p) {
            uint pid; GetWindowThreadProcessId(w,out pid);
            if(pid==process && (!visibleOnly || IsWindowVisible(w))) {
                var text=new StringBuilder(256); GetWindowText(w,text,text.Capacity); Rect r; GetWindowRect(w,out r);
                var className=new StringBuilder(256);GetClassName(w,className,256);
                result.Add(new WindowInfo {Handle=w.ToInt64(),ProcessId=pid,Title=text.ToString(),ClassName=className.ToString(),Visible=IsWindowVisible(w),Width=r.Right-r.Left,Height=r.Bottom-r.Top,
                    Foreground=GetForegroundWindow()==w,Dpi=GetDpiForWindow(w),DpiAwareness=GetAwarenessFromDpiAwarenessContext(GetWindowDpiAwarenessContext(w)),Bounds=Bounds(w)});
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
        // KEYEVENTF_UNICODE is deliberately separate from IME typing. Match the ASCII
        // 110 ms character cadence; the independent paste case covers bulk insertion.
        foreach(char c in text) {Send(new [] {Key(0,c,4),Key(0,c,6)});Thread.Sleep(110);}
    }
    public static void AsciiKeys(string text) {
        foreach(char c in text) {if(c<'a'||c>'z')throw new ArgumentException("Lowercase Latin letters only");Chord(new [] {(ushort)Char.ToUpperInvariant(c)});Thread.Sleep(110);}
    }
    public static long WindowKeyboardLayout(IntPtr window) {uint pid;return GetKeyboardLayout(GetWindowThreadProcessId(window,out pid)).ToInt64();}
    public static bool RequestKeyboardInput(IntPtr window,long layout) {
        // TSF activation in this harness is not acknowledgement from Step's GUI thread.
        // DefWindowProc handles this request on the target thread and changes its HKL.
        // The caller must observe WindowKeyboardLayout afterward, not trust PostMessage.
        // https://learn.microsoft.com/windows/win32/winmsg/wm-inputlangchangerequest
        return layout!=0&&PostMessage(window,0x50,IntPtr.Zero,new IntPtr(layout));
    }
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
    public static long[] CandidateWindows() {
        // Handles let the Enter regression distinguish a newly shown candidate from a
        // pre-existing window. This remains observation, not a candidate-layout verdict.
        var found=new List<long>();
        EnumWindows(delegate(IntPtr w,IntPtr p) {if(IsWindowVisible(w)) {var s=new StringBuilder(256);GetClassName(w,s,256);
            if(s.ToString().IndexOf("Candidate",StringComparison.OrdinalIgnoreCase)>=0)found.Add(w.ToInt64());}return true;},IntPtr.Zero);
        return found.ToArray();
    }
    public static bool CandidateVisible() {return CandidateWindows().Length>0;}
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
