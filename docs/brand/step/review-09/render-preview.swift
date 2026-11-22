// REVIEW ONLY. Does not write production resources or install an app.
import AppKit

// Approved 2026-11-02: review-08 C. One geometry feeds every platform.
// Run from repo root: swift Scripts/render-step-brand.swift
final class VectorPath {
    let native = NSBezierPath()
    var svg = ""
    func m(_ x: CGFloat, _ y: CGFloat) { native.move(to: .init(x:x,y:y)); svg += "M\(x),\(y)" }
    func l(_ x: CGFloat, _ y: CGFloat) { native.line(to: .init(x:x,y:y)); svg += "L\(x),\(y)" }
    func q(_ cx: CGFloat, _ cy: CGFloat, _ x: CGFloat, _ y: CGFloat) {
        let p = native.currentPoint
        native.curve(to:.init(x:x,y:y), controlPoint1:.init(x:p.x+(cx-p.x)*2/3,y:p.y+(cy-p.y)*2/3), controlPoint2:.init(x:x+(cx-x)*2/3,y:y+(cy-y)*2/3))
        svg += "Q\(cx),\(cy) \(x),\(y)"
    }
    func close() { native.close(); svg += "Z" }
}
func outline(forMonochrome:Bool=false) -> VectorPath {
    let p=VectorPath()
    p.m(12.1,78.8); p.l(18,68.6); p.l(44.7,68.6); p.q(46.3,68.6,46.3,67)
    p.l(46.3,32.5); p.q(46.3,31.9,47.1,31.2); p.l(51,27.6); p.q(51.9,26.9,52.9,26.9)
    // At menu/notification sizes the slanted tip and goal can share antialiased pixels.
    // Shorten only the mono terminal slightly so the goal remains visibly separate.
    let opticalInset:CGFloat=forMonochrome ? 4.5 : 0
    p.l(85.7-opticalInset,26.9); p.l(79.7-opticalInset,37.2); p.l(58.5,37.2); p.q(57.1,37.2,57.1,38.8)
    p.l(57.1,73.2); p.q(57.1,73.9,56.3,74.6); p.l(52.3,78.1); p.q(51.6,78.8,50.8,78.8)
    p.close(); return p
}
func upperFace() -> VectorPath {
    let p=VectorPath();p.m(0,0);p.l(108,0);p.l(108,60);p.l(57.1,38.8);p.l(47.1,31.2);p.l(0,31.2);p.close();return p
}
func lowerFace() -> VectorPath {
    let p=VectorPath();p.m(0,67);p.l(46.3,67);p.l(57.1,74.6);p.l(108,99);p.l(108,108);p.l(0,108);p.close();return p
}
let shape=outline(), monoShape=outline(forMonochrome:true), upper=upperFace(), lower=lowerFace()
let goal=NSRect(x:87.9,y:26.8,width:9.4,height:9.4)
let goalSVG="M97.3,31.5A4.7,4.7 0,1 0,87.9,31.5A4.7,4.7 0,1 0,97.3,31.5Z"
func color(_ value: UInt32) -> NSColor { NSColor(srgbRed:CGFloat((value>>16)&255)/255,green:CGFloat((value>>8)&255)/255,blue:CGFloat(value&255)/255,alpha:1) }
let topColors:[UInt32]=[0x4AB68D,0x35A47F], middleColors:[UInt32]=[0x009A78,0x008C6A], bottomColors:[UInt32]=[0x027B66,0x006553], goalColors:[UInt32]=[0xF6D982,0xD8A94E]
let ink=color(0x172A21)
func gradient(_ colors:[UInt32], in path:NSBezierPath, from:NSPoint = .init(x:18,y:20), to:NSPoint = .init(x:90,y:88)) {
    NSGraphicsContext.saveGraphicsState();path.addClip()
    NSGradient(starting:color(colors[0]),ending:color(colors[1]))!.draw(from:from,to:to,options:[.drawsBeforeStartingLocation,.drawsAfterEndingLocation])
    NSGraphicsContext.restoreGraphicsState()
}
func drawMark(_ box:NSRect, mono:NSColor?=nil) {
    NSGraphicsContext.saveGraphicsState()
    var t=AffineTransform();t.translate(x:box.minX,y:box.maxY);t.scale(x:box.width/108,y:-box.height/108);(t as NSAffineTransform).concat()
    if let mono { mono.setFill();monoShape.native.fill();NSBezierPath(ovalIn:goal).fill() }
    else {
        gradient(middleColors,in:shape.native)
        NSGraphicsContext.saveGraphicsState();shape.native.addClip();gradient(topColors,in:upper.native);gradient(bottomColors,in:lower.native);NSGraphicsContext.restoreGraphicsState()
        gradient(goalColors,in:NSBezierPath(ovalIn:goal),from:.init(x:89,y:28),to:.init(x:96,y:35))
    }
    NSGraphicsContext.restoreGraphicsState()
}
// Fit the monochrome silhouette, including a separately visible goal, inside the target.
func drawMono(_ rect:NSRect, color:NSColor) {
    let s=min(rect.width/85.2,rect.height/52)
    let x=rect.midX-54.7*s, y=rect.midY-(108-52.8)*s
    drawMark(.init(x:x,y:y,width:108*s,height:108*s),mono:color)
}
func appIcon(_ box:NSRect, circle:Bool=false, shadow:Bool=true) {
    let tile=box.insetBy(dx:box.width*0.055,dy:box.height*0.055)
    let p = circle ? NSBezierPath(ovalIn:tile) : NSBezierPath(roundedRect:tile,xRadius:tile.width*0.225,yRadius:tile.height*0.225)
    NSGraphicsContext.saveGraphicsState()
    if shadow { let s=NSShadow();s.shadowColor=NSColor.black.withAlphaComponent(0.16);s.shadowBlurRadius=box.width*0.012;s.shadowOffset = .init(width:0,height:-box.height*0.004);s.set() }
    NSColor.white.setFill();p.fill();NSGraphicsContext.restoreGraphicsState()
    NSGradient(starting:.white,ending:color(0xF9FAF9))!.draw(in:p,angle:270)
    NSGraphicsContext.saveGraphicsState();p.addClip();drawMark(tile);NSGraphicsContext.restoreGraphicsState()
}
func png(_ path:String,width:Int,height:Int,draw:()->Void) throws {
    let rep=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:width,pixelsHigh:height,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:width*4,bitsPerPixel:32)!
    rep.size = .init(width:width,height:height)
    NSGraphicsContext.saveGraphicsState();NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:rep)
    NSColor.clear.setFill();NSRect(x:0,y:0,width:width,height:height).fill(using:.copy);draw();NSGraphicsContext.restoreGraphicsState()
    try rep.representation(using:.png,properties:[:])!.write(to:URL(fileURLWithPath:path))
}
func write(_ path:String,_ value:String) throws { try value.write(toFile:path,atomically:true,encoding:.utf8) }
// Review 09: fuller proportions, restrained material. All coordinates are in a 108-unit tile.
let output="docs/brand/step/review-09"
func fullerOutline()->VectorPath {
    let p=VectorPath()
    p.m(12.1,84.6);p.l(19.2,72.4);p.l(43.3,72.4);p.q(45,72.4,45,70.7)
    p.l(45,27.1);p.q(45,26.4,45.8,25.7);p.l(51.3,20.7);p.q(52.2,20,53.3,20)
    p.l(85.7,20);p.l(78.6,32.2);p.l(59.3,32.2);p.q(57.5,32.2,57.5,34)
    p.l(57.5,77.5);p.q(57.5,78.2,56.7,78.9);p.l(51.2,83.9);p.q(50.4,84.6,49.3,84.6)
    p.close();return p
}
let fuller=fullerOutline()
let fullerUpper=VectorPath()
fullerUpper.m(0,0);fullerUpper.l(108,0);fullerUpper.l(108,68);fullerUpper.l(57.5,34);fullerUpper.l(45.4,26.2);fullerUpper.l(0,26.2);fullerUpper.close()
let fullerLower=VectorPath()
fullerLower.m(0,70.7);fullerLower.l(45,70.7);fullerLower.l(57.5,79);fullerLower.l(108,108);fullerLower.l(0,108);fullerLower.close()
let fullerGoal=NSBezierPath(ovalIn:.init(x:87.6,y:20.5,width:10,height:10))

func materialMark(_ box:NSRect) {
    NSGraphicsContext.saveGraphicsState()
    var transform=AffineTransform();transform.translate(x:box.minX,y:box.maxY);transform.scale(x:box.width/108,y:-box.height/108)
    (transform as NSAffineTransform).concat()
    // Shallow contact shadow, never a thick extrusion.
    NSGraphicsContext.saveGraphicsState()
    let s=NSShadow();s.shadowColor=color(0x173D30).withAlphaComponent(0.13);s.shadowBlurRadius=0.65;s.shadowOffset = .init(width:0.10,height:-0.45);s.set()
    color(0x008F6D).setFill();fuller.native.fill();NSGraphicsContext.restoreGraphicsState()
    gradient([0x13AF86,0x007F61],in:fuller.native,from:.init(x:43,y:26),to:.init(x:58,y:82))
    NSGraphicsContext.saveGraphicsState();fuller.native.addClip()
    gradient([0x64C49A,0x289A72],in:fullerUpper.native,from:.init(x:51,y:17),to:.init(x:81,y:40))
    gradient([0x10876D,0x005E4C],in:fullerLower.native,from:.init(x:29,y:67),to:.init(x:38,y:89))
    // Delicate edge light makes the upper plane catch light without introducing a bevel band.
    let edge=NSBezierPath();edge.move(to:.init(x:53.2,y:20.17));edge.line(to:.init(x:85.35,y:20.17));edge.lineWidth=0.23
    NSColor.white.withAlphaComponent(0.34).setStroke();edge.stroke()
    let bottomEdge=NSBezierPath();bottomEdge.move(to:.init(x:19.3,y:72.55));bottomEdge.line(to:.init(x:43.2,y:72.55));bottomEdge.lineWidth=0.18
    NSColor.white.withAlphaComponent(0.18).setStroke();bottomEdge.stroke()
    NSGraphicsContext.restoreGraphicsState()
    // A soft spherical gradient, with no sparkle or outline.
    NSGradient(colorsAndLocations:(color(0xFFF0BC),0),(color(0xE8BF5F),0.52),(color(0xC69131),1))!.draw(in:fullerGoal,relativeCenterPosition:.init(x:-0.38,y:-0.40))
    NSGraphicsContext.restoreGraphicsState()
}
func candidateIcon(_ box:NSRect) {
    let tile=box.insetBy(dx:box.width*0.055,dy:box.height*0.055)
    let p=NSBezierPath(roundedRect:tile,xRadius:tile.width*0.225,yRadius:tile.height*0.225)
    NSGraphicsContext.saveGraphicsState()
    let s=NSShadow();s.shadowColor=NSColor.black.withAlphaComponent(0.15);s.shadowBlurRadius=box.width*0.012;s.shadowOffset = .init(width:0,height:-box.height*0.004);s.set()
    NSColor.white.setFill();p.fill();NSGraphicsContext.restoreGraphicsState()
    NSGradient(colorsAndLocations:(color(0xF2F5F2),0),(NSColor.white,0.72),(NSColor.white,1))!.draw(in:p,angle:90)
    NSGraphicsContext.saveGraphicsState();p.addClip();materialMark(tile)
    NSGraphicsContext.restoreGraphicsState()
    let rim=NSBezierPath(roundedRect:tile.insetBy(dx:0.5,dy:0.5),xRadius:tile.width*0.225,yRadius:tile.height*0.225)
    rim.lineWidth=box.width*0.0008;NSColor.white.withAlphaComponent(0.80).setStroke();rim.stroke()
}
func text(_ value:String,_ rect:NSRect,size:CGFloat=18,color:NSColor=ink,weight:NSFont.Weight = .regular,center:Bool=false) {
    let para=NSMutableParagraphStyle();para.alignment=center ? .center : .left
    (value as NSString).draw(in:rect,withAttributes:[.font:NSFont.systemFont(ofSize:size,weight:weight),.foregroundColor:color,.paragraphStyle:para])
}
func flatBG(_ c:UInt32,_ w:Int,_ h:Int) { color(c).setFill();NSRect(x:0,y:0,width:w,height:h).fill() }
func rounded(_ rect:NSRect,_ radius:CGFloat,_ c:NSColor) { c.setFill();NSBezierPath(roundedRect:rect,xRadius:radius,yRadius:radius).fill() }

try png(output+"/step-fuller-material-1024.png",width:1024,height:1024) { candidateIcon(.init(x:0,y:0,width:1024,height:1024)) }
for size in [16,24,32,48,64,128,256,512] {
    try png(output+"/candidate-\(size).png",width:size,height:size) { candidateIcon(.init(x:0,y:0,width:size,height:size)) }
    try png(output+"/current-\(size).png",width:size,height:size) { appIcon(.init(x:0,y:0,width:size,height:size)) }
}
try png(output+"/comparison.png",width:1440,height:900) {
    flatBG(0xF0F2EF,1440,900)
    text("Step · 更饱满，轻质感",.init(x:64,y:810,width:1250,height:48),size:34,weight:.semibold)
    text("C 的细化预览 · 保留白底、折面 S 与金色目标球",.init(x:64,y:770,width:1250,height:32),size:20,color:color(0x65756B))
    appIcon(.init(x:100,y:202,width:530,height:530));candidateIcon(.init(x:810,y:202,width:530,height:530))
    text("当前版本",.init(x:100,y:142,width:530,height:40),size:26,weight:.medium,center:true)
    text("主体高度 48% · 现有材质",.init(x:100,y:98,width:530,height:30),size:19,color:color(0x65756B),center:true)
    text("本次预览",.init(x:810,y:142,width:530,height:40),size:26,weight:.medium,center:true)
    text("主体高度 60% · 略加厚 · 柔和折面与球面高光",.init(x:765,y:98,width:620,height:30),size:19,color:color(0x65756B),center:true)
    text("仅设计预览，尚未替换应用图标",.init(x:64,y:32,width:1280,height:28),size:16,color:color(0x7D897F),center:true)
}

// Desktop mockups contain only public system app icons, no user content or actual Dock screenshot.
let finder=NSImage(contentsOfFile:"/System/Library/CoreServices/Finder.app/Contents/Resources/Finder.icns")!
let notes=NSImage(contentsOfFile:"/System/Applications/Notes.app/Contents/Resources/AppIcon.icns")!
let settings=NSImage(contentsOfFile:"/System/Applications/System Settings.app/Contents/Resources/SystemSettings.icns")!
let terminal=NSImage(contentsOfFile:"/System/Applications/Utilities/Terminal.app/Contents/Resources/Terminal.icns")!
let systemIcons=[finder,notes,settings,terminal]
func dock(_ rect:NSRect,dark:Bool,new:Bool) {
    let back=NSBezierPath(roundedRect:rect,xRadius:24,yRadius:24)
    NSGradient(starting:color(dark ? 0x20342E:0xC8D6CE),ending:color(dark ? 0x13211D:0xE9ECE3))!.draw(in:back,angle:40)
    text(new ? "本次预览" : "当前版本",.init(x:rect.minX+20,y:rect.maxY-39,width:220,height:26),size:15,color:dark ? color(0xD9E4DD):ink,weight:.medium)
    let rail=NSRect(x:rect.minX+27,y:rect.minY+26,width:rect.width-54,height:100)
    rounded(rail,22,NSColor.white.withAlphaComponent(dark ? 0.12:0.40))
    let sizes:CGFloat=64,gap:CGFloat=18,total:CGFloat=5*64+4*18
    let x0=rail.midX-total/2
    for i in 0..<5 {
        let r=NSRect(x:x0+CGFloat(i)*(sizes+gap),y:rail.minY+22,width:sizes,height:sizes)
        if i==2 { if new { candidateIcon(r) } else { appIcon(r) } }
        else { systemIcons[i<2 ? i:i-1].draw(in:r) }
        if i==2 { rounded(.init(x:r.midX-2,y:rail.minY+11,width:4,height:4),2,dark ? .white:ink) }
    }
}
try png(output+"/desktop-small-sizes.png",width:1440,height:1140) {
    flatBG(0xF4F5F2,1440,1140)
    text("桌面小尺寸效果",.init(x:64,y:1044,width:1250,height:48),size:34,weight:.semibold)
    text("Dock 为合成示意；浅色与深色背景使用同一个候选图标",.init(x:64,y:1003,width:1280,height:32),size:20,color:color(0x65756B))
    dock(.init(x:64,y:778,width:640,height:192),dark:false,new:false)
    dock(.init(x:736,y:778,width:640,height:192),dark:false,new:true)
    dock(.init(x:64,y:562,width:640,height:192),dark:true,new:false)
    dock(.init(x:736,y:562,width:640,height:192),dark:true,new:true)
    text("同一图形直接栅格化 · 原始像素尺寸",.init(x:64,y:481,width:1250,height:34),size:23,weight:.medium)
    let widths:[CGFloat]=[128,64,48,32,24,16]
    let positions:[CGFloat]=[335,575,775,955,1115,1265]
    for i in widths.indices {
        text("\(Int(widths[i])) px",.init(x:positions[i]-80,y:433,width:160,height:28),size:18,color:color(0x65756B),center:true)
        appIcon(.init(x:positions[i]-widths[i]/2,y:298-widths[i]/2,width:widths[i],height:widths[i]))
        candidateIcon(.init(x:positions[i]-widths[i]/2,y:142-widths[i]/2,width:widths[i],height:widths[i]))
    }
    text("当前",.init(x:74,y:282,width:160,height:30),size:22,weight:.medium)
    text("预览",.init(x:74,y:126,width:160,height:30),size:22,weight:.medium)
    text("聊天可能缩放整张图；打开预览页面可按 32 / 48 / 64 pt 查看。此页为设计评审，非实际安装截图。",.init(x:64,y:24,width:1310,height:28),size:16,color:color(0x7D897F))
}
print("Created review-only large image, comparison, desktop mockup, and exact-size PNGs in \(output)")
