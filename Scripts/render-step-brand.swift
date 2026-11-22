import AppKit

// Approved 2026-11-06: fuller/material refinement of review-08 C (review-09). One geometry feeds every platform.
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
    p.m(12.1,84.6);p.l(19.2,72.4);p.l(43.3,72.4);p.q(45,72.4,45,70.7)
    p.l(45,27.1);p.q(45,26.4,45.8,25.7);p.l(51.3,20.7);p.q(52.2,20,53.3,20)
    // Optical separation for single-color menu/notification sizes only.
    let inset:CGFloat=forMonochrome ? 4.5 : 0
    p.l(85.7-inset,20);p.l(78.6-inset,32.2);p.l(59.3,32.2);p.q(57.5,32.2,57.5,34)
    p.l(57.5,77.5);p.q(57.5,78.2,56.7,78.9);p.l(51.2,83.9);p.q(50.4,84.6,49.3,84.6)
    p.close();return p
}
func upperFace() -> VectorPath {
    let p=VectorPath();p.m(0,0);p.l(108,0);p.l(108,68);p.l(57.5,34);p.l(45.4,26.2);p.l(0,26.2);p.close();return p
}
func lowerFace() -> VectorPath {
    let p=VectorPath();p.m(0,70.7);p.l(45,70.7);p.l(57.5,79);p.l(108,108);p.l(0,108);p.close();return p
}
let shape=outline(), monoShape=outline(forMonochrome:true), upper=upperFace(), lower=lowerFace()
let goal=NSRect(x:87.6,y:20.5,width:10,height:10)
let goalSVG="M97.6,25.5A5,5 0,1 0,87.6,25.5A5,5 0,1 0,97.6,25.5Z"
func color(_ value: UInt32) -> NSColor { NSColor(srgbRed:CGFloat((value>>16)&255)/255,green:CGFloat((value>>8)&255)/255,blue:CGFloat(value&255)/255,alpha:1) }
let topColors:[UInt32]=[0x64C49A,0x289A72], middleColors:[UInt32]=[0x13AF86,0x007F61], bottomColors:[UInt32]=[0x10876D,0x005E4C], goalColors:[UInt32]=[0xFFF0BC,0xE8BF5F,0xC69131]
let ink=color(0x172A21)
func gradient(_ colors:[UInt32], in path:NSBezierPath, from:NSPoint = .init(x:18,y:20), to:NSPoint = .init(x:90,y:88)) {
    NSGraphicsContext.saveGraphicsState();path.addClip()
    NSGradient(starting:color(colors[0]),ending:color(colors[1]))!.draw(from:from,to:to,options:[.drawsBeforeStartingLocation,.drawsAfterEndingLocation])
    NSGraphicsContext.restoreGraphicsState()
}
func drawMark(_ box:NSRect, mono:NSColor?=nil) {
    NSGraphicsContext.saveGraphicsState()
    var transform=AffineTransform();transform.translate(x:box.minX,y:box.maxY);transform.scale(x:box.width/108,y:-box.height/108)
    (transform as NSAffineTransform).concat()
    if let mono {
        mono.setFill();monoShape.native.fill();NSBezierPath(ovalIn:goal).fill()
        NSGraphicsContext.restoreGraphicsState();return
    }
    // Shallow contact shadow, never a thick extrusion.
    NSGraphicsContext.saveGraphicsState()
    let s=NSShadow();s.shadowColor=color(0x173D30).withAlphaComponent(0.13);s.shadowBlurRadius=0.65;s.shadowOffset = .init(width:0.10,height:-0.45);s.set()
    color(0x008F6D).setFill();shape.native.fill();NSGraphicsContext.restoreGraphicsState()
    gradient([0x13AF86,0x007F61],in:shape.native,from:.init(x:43,y:26),to:.init(x:58,y:82))
    NSGraphicsContext.saveGraphicsState();shape.native.addClip()
    gradient([0x64C49A,0x289A72],in:upper.native,from:.init(x:51,y:17),to:.init(x:81,y:40))
    gradient([0x10876D,0x005E4C],in:lower.native,from:.init(x:29,y:67),to:.init(x:38,y:89))
    // Delicate edge light makes the upper plane catch light without introducing a bevel band.
    let edge=NSBezierPath();edge.move(to:.init(x:53.2,y:20.17));edge.line(to:.init(x:85.35,y:20.17));edge.lineWidth=0.23
    NSColor.white.withAlphaComponent(0.34).setStroke();edge.stroke()
    let bottomEdge=NSBezierPath();bottomEdge.move(to:.init(x:19.3,y:72.55));bottomEdge.line(to:.init(x:43.2,y:72.55));bottomEdge.lineWidth=0.18
    NSColor.white.withAlphaComponent(0.18).setStroke();bottomEdge.stroke()
    NSGraphicsContext.restoreGraphicsState()
    // A soft spherical gradient, with no sparkle or outline.
    NSGradient(colorsAndLocations:(color(0xFFF0BC),0),(color(0xE8BF5F),0.52),(color(0xC69131),1))!.draw(in:NSBezierPath(ovalIn:goal),relativeCenterPosition:.init(x:-0.38,y:-0.40))
    NSGraphicsContext.restoreGraphicsState()
}
// Fit the monochrome silhouette, including a separately visible goal, inside the target.
func drawMono(_ rect:NSRect, color:NSColor) {
    let s=min(rect.width/85.5,rect.height/64.6)
    let x=rect.midX-54.85*s, y=rect.midY-(108-52.3)*s
    drawMark(.init(x:x,y:y,width:108*s,height:108*s),mono:color)
}
func appIcon(_ box:NSRect,circle:Bool=false,shadow:Bool=true) {
    let tile=box.insetBy(dx:box.width*0.055,dy:box.height*0.055)
    let p = circle ? NSBezierPath(ovalIn:tile) : NSBezierPath(roundedRect:tile,xRadius:tile.width*0.225,yRadius:tile.height*0.225)
    NSGraphicsContext.saveGraphicsState()
    let s=NSShadow();s.shadowColor=NSColor.black.withAlphaComponent(0.15);s.shadowBlurRadius=box.width*0.012;s.shadowOffset = .init(width:0,height:-box.height*0.004);if shadow { s.set() }
    NSColor.white.setFill();p.fill();NSGraphicsContext.restoreGraphicsState()
    NSGradient(colorsAndLocations:(color(0xF2F5F2),0),(NSColor.white,0.72),(NSColor.white,1))!.draw(in:p,angle:90)
    NSGraphicsContext.saveGraphicsState();p.addClip();drawMark(tile)
    NSGraphicsContext.restoreGraphicsState()
    let rim = circle ? NSBezierPath(ovalIn:tile.insetBy(dx:0.5,dy:0.5)) : NSBezierPath(roundedRect:tile.insetBy(dx:0.5,dy:0.5),xRadius:tile.width*0.225,yRadius:tile.height*0.225)
    rim.lineWidth=box.width*0.0008;NSColor.white.withAlphaComponent(0.80).setStroke();rim.stroke()
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
drawMono(.init(x:1.8,y:2.75,width:15.5,height:11.5),color:.black)
NSGraphicsContext.restoreGraphicsState();pdf.endPDFPage();pdf.closePDF()

let defs="""
<defs>
 <clipPath id="body"><path d="\(shape.svg)"/></clipPath>
 <linearGradient id="upper" gradientUnits="userSpaceOnUse" x1="51" y1="17" x2="81" y2="40"><stop stop-color="\(hex(topColors[0]))"/><stop offset="1" stop-color="\(hex(topColors[1]))"/></linearGradient>
 <linearGradient id="middle" gradientUnits="userSpaceOnUse" x1="43" y1="26" x2="58" y2="82"><stop stop-color="\(hex(middleColors[0]))"/><stop offset="1" stop-color="\(hex(middleColors[1]))"/></linearGradient>
 <linearGradient id="lower" gradientUnits="userSpaceOnUse" x1="29" y1="67" x2="38" y2="89"><stop stop-color="\(hex(bottomColors[0]))"/><stop offset="1" stop-color="\(hex(bottomColors[1]))"/></linearGradient>
 <radialGradient id="gold" gradientUnits="userSpaceOnUse" cx="90.7" cy="23.5" r="7.76"><stop stop-color="\(hex(goalColors[0]))"/><stop offset="0.52" stop-color="\(hex(goalColors[1]))"/><stop offset="1" stop-color="\(hex(goalColors[2]))"/></radialGradient>
 <linearGradient id="tile" x1="0" y1="1" x2="0" y2="0"><stop stop-color="#F2F5F2"/><stop offset="0.72" stop-color="white"/><stop offset="1" stop-color="white"/></linearGradient>
 <filter id="contact" x="-10%" y="-10%" width="120%" height="120%"><feDropShadow dx="0.1" dy="0.45" stdDeviation="0.325" flood-color="#173D30" flood-opacity="0.13"/></filter>
</defs>
"""
let svgBody="""
<path fill="url(#middle)" filter="url(#contact)" d="\(shape.svg)"/>
<g clip-path="url(#body)"><path fill="url(#upper)" d="\(upper.svg)"/><path fill="url(#lower)" d="\(lower.svg)"/><path d="M53.2 20.17H85.35" fill="none" stroke="white" stroke-opacity="0.34" stroke-width="0.23"/><path d="M19.3 72.55H43.2" fill="none" stroke="white" stroke-opacity="0.18" stroke-width="0.18"/></g>
<circle cx="92.6" cy="25.5" r="5" fill="url(#gold)"/>
"""
try write("Resources/Brand/step-mark.svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><title>Step — approved C refinement</title>\(defs)\(svgBody)</svg>\n")
try write("Resources/Brand/step-app-icon.svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><title>Step — approved C refinement</title>\(defs)<rect x=\"6\" y=\"6\" width=\"96\" height=\"96\" rx=\"21.6\" fill=\"url(#tile)\"/><g transform=\"translate(6 6) scale(.88888889)\">\(svgBody)</g></svg>\n")
try write("Resources/Brand/step-monochrome.svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><path fill=\"currentColor\" d=\"\(monoShape.svg)\"/><circle cx=\"92.6\" cy=\"25.5\" r=\"5\" fill=\"currentColor\"/></svg>\n")

func hex(_ c:UInt32)->String { String(format:"#%06X",c) }
func androidGradientPath(_ data:String,_ cs:[UInt32],start:(Double,Double)=(18,20),end:(Double,Double)=(90,88))->String {
    "<path android:pathData=\"\(data)\"><aapt:attr name=\"android:fillColor\"><gradient android:type=\"linear\" android:startX=\"\(start.0)\" android:startY=\"\(start.1)\" android:endX=\"\(end.0)\" android:endY=\"\(end.1)\" android:startColor=\"\(hex(cs[0]))\" android:endColor=\"\(hex(cs[1]))\"/></aapt:attr></path>"
}
let androidGold="<path android:pathData=\"\(goalSVG)\"><aapt:attr name=\"android:fillColor\"><gradient android:type=\"radial\" android:centerX=\"90.7\" android:centerY=\"23.5\" android:gradientRadius=\"7.76\"><item android:offset=\"0\" android:color=\"\(hex(goalColors[0]))\"/><item android:offset=\"0.52\" android:color=\"\(hex(goalColors[1]))\"/><item android:offset=\"1\" android:color=\"\(hex(goalColors[2]))\"/></gradient></aapt:attr></path>"
let androidColorBody=androidGradientPath(shape.svg,middleColors,start:(43,26),end:(58,82))+"\n<group><clip-path android:pathData=\"\(shape.svg)\"/>"+androidGradientPath(upper.svg,topColors,start:(51,17),end:(81,40))+androidGradientPath(lower.svg,bottomColors,start:(29,67),end:(38,89))+"<path android:pathData=\"M53.2,20.17H85.35\" android:strokeColor=\"#57FFFFFF\" android:strokeWidth=\"0.23\"/><path android:pathData=\"M19.3,72.55H43.2\" android:strokeColor=\"#2EFFFFFF\" android:strokeWidth=\"0.18\"/></group>\n"+androidGold
let androidMonoBody="<path android:fillColor=\"#FFFFFFFF\" android:pathData=\"\(monoShape.svg)\"/><path android:fillColor=\"#FFFFFFFF\" android:pathData=\"\(goalSVG)\"/>"
func vector(_ body:String,viewport:Int=108,size:Int=108)->String { "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by Scripts/render-step-brand.swift; approved C. -->\n<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:aapt=\"http://schemas.android.com/aapt\" android:width=\"\(size)dp\" android:height=\"\(size)dp\" android:viewportWidth=\"\(viewport)\" android:viewportHeight=\"\(viewport)\">\n\(body)\n</vector>\n" }
// Android's outer 18 dp is for launcher motion/cropping. 0.62 keeps all ink in the central safe circle.
let adaptiveGroup="<group android:pivotX=\"54\" android:pivotY=\"54\" android:scaleX=\"0.62\" android:scaleY=\"0.62\">"
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
    label("Step · 已批准 C 精修的正式资源",40,595,28)
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
    if shape.native.contains(point) || NSBezierPath(ovalIn:goal).contains(point) { precondition(hypot((x-54)*0.62,(y-54)*0.62) < 33,"Adaptive mark outside safe circle") }
} }
print("Generated approved Step C refined artwork; adaptive safe-circle check passed.")
