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
for folder in ["Resources/Brand","docs/brand/step/production","android/app/src/main/res/mipmap-anydpi-v26","android/app/src/main/res/mipmap-anydpi-v33"] { try FileManager.default.createDirectory(atPath:folder,withIntermediateDirectories:true) }
try png("Resources/AppIcon.png",width:1024,height:1024) { appIcon(.init(x:0,y:0,width:1024,height:1024)) }
try png("docs/images/app-icon.png",width:512,height:512) { appIcon(.init(x:0,y:0,width:512,height:512)) }

var media=CGRect(x:0,y:0,width:18,height:18)
let pdf=CGContext(URL(fileURLWithPath:"Resources/StepStatusIcon.pdf") as CFURL,mediaBox:&media,nil)!
pdf.beginPDFPage(nil);NSGraphicsContext.saveGraphicsState();NSGraphicsContext.current=NSGraphicsContext(cgContext:pdf,flipped:false)
drawMono(.init(x:1.5,y:3.5,width:15.5,height:9.5),color:.black)
NSGraphicsContext.restoreGraphicsState();pdf.endPDFPage();pdf.closePDF()

let defs="""
<defs>
 <clipPath id="body"><path d="\(shape.svg)"/></clipPath>
 <linearGradient id="upper" gradientUnits="userSpaceOnUse" x1="18" y1="20" x2="90" y2="88"><stop stop-color="\(hex(topColors[0]))"/><stop offset="1" stop-color="\(hex(topColors[1]))"/></linearGradient>
 <linearGradient id="middle" gradientUnits="userSpaceOnUse" x1="18" y1="20" x2="90" y2="88"><stop stop-color="\(hex(middleColors[0]))"/><stop offset="1" stop-color="\(hex(middleColors[1]))"/></linearGradient>
 <linearGradient id="lower" gradientUnits="userSpaceOnUse" x1="18" y1="20" x2="90" y2="88"><stop stop-color="\(hex(bottomColors[0]))"/><stop offset="1" stop-color="\(hex(bottomColors[1]))"/></linearGradient>
 <linearGradient id="gold" gradientUnits="userSpaceOnUse" x1="89" y1="28" x2="96" y2="35"><stop stop-color="\(hex(goalColors[0]))"/><stop offset="1" stop-color="\(hex(goalColors[1]))"/></linearGradient>
</defs>
"""
let svgBody="""
<path fill="url(#middle)" d="\(shape.svg)"/>
<g clip-path="url(#body)"><path fill="url(#upper)" d="\(upper.svg)"/><path fill="url(#lower)" d="\(lower.svg)"/></g>
<circle cx="92.6" cy="31.5" r="4.7" fill="url(#gold)"/>
"""
try write("Resources/Brand/step-mark.svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><title>Step — approved C</title>\(defs)\(svgBody)</svg>\n")
try write("Resources/Brand/step-app-icon.svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><title>Step — approved C</title>\(defs)<rect x=\"6\" y=\"6\" width=\"96\" height=\"96\" rx=\"21.6\" fill=\"white\"/><g transform=\"translate(6 6) scale(.88888889)\">\(svgBody)</g></svg>\n")
try write("Resources/Brand/step-monochrome.svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><path fill=\"currentColor\" d=\"\(monoShape.svg)\"/><circle cx=\"92.6\" cy=\"31.5\" r=\"4.7\" fill=\"currentColor\"/></svg>\n")

func hex(_ c:UInt32)->String { String(format:"#%06X",c) }
func androidGradientPath(_ data:String,_ cs:[UInt32],start:(Double,Double)=(18,20),end:(Double,Double)=(90,88))->String {
    "<path android:pathData=\"\(data)\"><aapt:attr name=\"android:fillColor\"><gradient android:type=\"linear\" android:startX=\"\(start.0)\" android:startY=\"\(start.1)\" android:endX=\"\(end.0)\" android:endY=\"\(end.1)\" android:startColor=\"\(hex(cs[0]))\" android:endColor=\"\(hex(cs[1]))\"/></aapt:attr></path>"
}
let androidColorBody=androidGradientPath(shape.svg,middleColors)+"\n<group><clip-path android:pathData=\"\(shape.svg)\"/>"+androidGradientPath(upper.svg,topColors)+androidGradientPath(lower.svg,bottomColors)+"</group>\n"+androidGradientPath(goalSVG,goalColors,start:(89,28),end:(96,35))
let androidMonoBody="<path android:fillColor=\"#FFFFFFFF\" android:pathData=\"\(monoShape.svg)\"/><path android:fillColor=\"#FFFFFFFF\" android:pathData=\"\(goalSVG)\"/>"
func vector(_ body:String,viewport:Int=108,size:Int=108)->String { "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by Scripts/render-step-brand.swift; approved C. -->\n<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:aapt=\"http://schemas.android.com/aapt\" android:width=\"\(size)dp\" android:height=\"\(size)dp\" android:viewportWidth=\"\(viewport)\" android:viewportHeight=\"\(viewport)\">\n\(body)\n</vector>\n" }
// Android's outer 18 dp is for launcher motion/cropping. 0.66 keeps all ink in the central safe circle.
let adaptiveGroup="<group android:pivotX=\"54\" android:pivotY=\"54\" android:scaleX=\"0.66\" android:scaleY=\"0.66\">"
try write("android/app/src/main/res/drawable/step_launcher_foreground.xml",vector(adaptiveGroup+androidColorBody+"</group>"))
try write("android/app/src/main/res/drawable/step_launcher_monochrome.xml",vector(adaptiveGroup+androidMonoBody+"</group>"))
let bg="<path android:fillColor=\"#FFFFFFFF\" android:pathData=\"M24.3,0H83.7Q108,0 108,24.3V83.7Q108,108 83.7,108H24.3Q0,108 0,83.7V24.3Q0,0 24.3,0Z\"/>"
// Keep the established resource name for widget compatibility and native preview checks.
let legacyVector=vector(bg+androidColorBody).replacingOccurrences(of:"xmlns:aapt=\"http://schemas.android.com/aapt\"",with:"xmlns:aapt=\"http://schemas.android.com/aapt\" xmlns:tools=\"http://schemas.android.com/tools\" tools:ignore=\"UnusedResources\"")
try write("android/app/src/main/res/drawable/pare_icon.xml",legacyVector)
try write("android/app/src/main/res/drawable/pare_notification.xml",vector("<group android:scaleX=\"0.2347\" android:scaleY=\"0.2347\" android:translateX=\"-0.84\" android:translateY=\"-0.39\">"+androidMonoBody+"</group>",viewport:24,size:24))
try write("android/app/src/main/res/mipmap-anydpi-v26/step_launcher.xml","<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\"><background android:drawable=\"@color/step_icon_background\"/><foreground android:drawable=\"@drawable/step_launcher_foreground\"/><monochrome android:drawable=\"@drawable/step_launcher_monochrome\"/></adaptive-icon>\n")
try write("android/app/src/main/res/mipmap-anydpi-v33/step_launcher.xml","<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\"><background android:drawable=\"@color/step_icon_background\"/><foreground android:drawable=\"@drawable/step_launcher_foreground\"/><monochrome android:drawable=\"@drawable/step_launcher_monochrome\"/></adaptive-icon>\n")
try write("android/app/src/main/res/values/step_brand.xml","<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources><color name=\"step_icon_background\">#FFFFFF</color></resources>\n")

func label(_ text:String,_ x:CGFloat,_ y:CGFloat,_ size:CGFloat=18,_ foreground:NSColor=ink) { (text as NSString).draw(at:.init(x:x,y:y),withAttributes:[.font:NSFont.systemFont(ofSize:size),.foregroundColor:foreground]) }
try png("docs/brand/step/production/native-resources.png",width:1440,height:660) {
    color(0xF4F5F1).setFill();NSRect(x:0,y:0,width:1440,height:660).fill()
    label("Step · 已批准 C 的正式资源",40,595,28)
    appIcon(.init(x:40,y:235,width:300,height:300));label("macOS",145,195)
    appIcon(.init(x:390,y:250,width:260,height:260),circle:true,shadow:false);label("圆形裁切对照",455,195)
    for (i,size) in [16,18,24,32,64].enumerated() {
        let x=CGFloat(750+i*120)
        appIcon(.init(x:x,y:410,width:CGFloat(size),height:CGFloat(size)))
        label("\(size)px",x,370,15)
        drawMono(.init(x:x,y:290,width:CGFloat(size),height:CGFloat(size)),color:ink)
    }
    label("彩色与单色 / 原像素尺寸",748,480,18)
    let dark=NSRect(x:730,y:65,width:660,height:135);ink.setFill();NSBezierPath(roundedRect:dark,xRadius:16,yRadius:16).fill()
    for (i,size) in [16,18,24,32,64].enumerated() { drawMono(.init(x:750+CGFloat(i*120),y:112,width:CGFloat(size),height:CGFloat(size)),color:.white) }
    label("单色深色背景",752,77,15,.white)
}
for size in [16,18,24,32,64] {
    try png("docs/brand/step/production/mono-\(size).png",width:size,height:size) { drawMono(.init(x:1,y:2,width:CGFloat(size-2),height:CGFloat(size-4)),color:.black) }
}
// Check the full adaptive artwork, including the goal, against the central 66 dp safe circle.
for x in stride(from:0.0,through:108.0,by:0.25) { for y in stride(from:0.0,through:108.0,by:0.25) {
    let point=NSPoint(x:x,y:y)
    if shape.native.contains(point) || NSBezierPath(ovalIn:goal).contains(point) { precondition(hypot((x-54)*0.66,(y-54)*0.66) < 33,"Adaptive mark outside safe circle") }
} }
print("Generated approved Step C artwork; adaptive safe-circle check passed.")
