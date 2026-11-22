package com.step.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Paint only: no padding, sizing, focus target or pointer handler is added by these modifiers. */
@Immutable internal data class StepMaterialTones(
    val surfaceTop:Color,val surfaceBase:Color,val raisedTop:Color,val raisedBase:Color,
    val warmTop:Color,val warmBase:Color,val selectionTop:Color,val selectionBase:Color,
    val fieldTop:Color,val fieldBase:Color,val primaryTop:Color,val primaryBase:Color,
    val primaryInk:Color,val edge:Color,val shadow:Color,val hover:Color
)
private val DarkMaterial=StepMaterialTones(
    Color(0xFF2B352E),Color(0xFF27312B),Color(0xFF3A463D),Color(0xFF344038),
    Color(0xFF3B3D2C),Color(0xFF28342A),Color(0xFF414333),Color(0xFF303A2F),
    Color(0xFF19241E),Color(0xFF1D2821),Color(0xFFEFD697),Color(0xFFDDBB66),
    Color(0xFF17211D),Color(0xFFF6E4B5).copy(alpha=.12f),Color.Black.copy(alpha=.14f),Color.White.copy(alpha=.055f)
)
private val LightMaterial=StepMaterialTones(
    Color(0xFFFFFFFC),Color(0xFFF9FAF4),Color(0xFFF0F3EA),Color(0xFFE6EBE1),
    Color(0xFFF6EDD3),Color(0xFFEAF0E1),Color(0xFFECE6CC),Color(0xFFE0E8DA),
    Color(0xFFECF0E7),Color(0xFFF4F6EF),Color(0xFFEFD697),Color(0xFFDDBB66),
    Color(0xFF23392B),Color.White.copy(alpha=.75f),Color(0xFF233328).copy(alpha=.075f),Color(0xFF27392C).copy(alpha=.035f)
)
internal val StepMaterials:StepMaterialTones @Composable get()=if(LocalStepIsDark.current)DarkMaterial else LightMaterial

@Immutable internal data class StepPaintState(val hover:Float=0f,val pressed:Float=0f,val focused:Float=0f)
@Composable internal fun stepPaintState(source:MutableInteractionSource?,enabled:Boolean=true):StepPaintState {
    if(source==null)return StepPaintState()
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val hover by animateFloatAsState(if(enabled&&hovered)1f else 0f,tween(140),label="step-hover")
    val down by animateFloatAsState(if(enabled&&pressed)1f else 0f,tween(80),label="step-press")
    val focus by animateFloatAsState(if(enabled&&focused)1f else 0f,tween(120),label="step-focus")
    return StepPaintState(hover,down,focus)
}

/** One restrained surface hierarchy; emphasis and tint never alter hit testing or geometry. */
@Composable internal fun StepCard(
    modifier:Modifier=Modifier,
    emphasis:Boolean=false,
    accent:Color?=null,
    onClick:(()->Unit)?=null,
    content:@Composable ()->Unit
) {
    val source=remember {MutableInteractionSource()}
    val paint=stepPaintState(if(onClick!=null)source else null)
    val material=modifier.stepPanelSurface(emphasis=emphasis,accent=accent,paint=paint)
    if(onClick==null) Surface(modifier=material,shape=MaterialTheme.shapes.medium,color=Color.Transparent,
        contentColor=MaterialTheme.colorScheme.onSurface,content=content)
    else Surface(onClick=onClick,modifier=material,shape=MaterialTheme.shapes.medium,color=Color.Transparent,
        contentColor=MaterialTheme.colorScheme.onSurface,interactionSource=source,content=content)
}

@Composable internal fun Modifier.stepPanelSurface(
    emphasis:Boolean=false,accent:Color?=null,radius:Dp=14.dp,paint:StepPaintState=StepPaintState(),elevation:Dp=if(emphasis)5.dp else 3.dp
):Modifier {
    val tones=StepMaterials
    val gold=StepGold
    return shadow(elevation,RoundedCornerShape(radius),clip=false,ambientColor=tones.shadow,spotColor=tones.shadow)
        .stepMaterialPaint(
            radius=radius,top=if(emphasis)tones.warmTop else tones.surfaceTop,
            bottom=if(emphasis)tones.warmBase else tones.surfaceBase,
            border=if(emphasis)gold.copy(alpha=.24f)else StepLine,
            highlight=tones.edge.copy(alpha=if(emphasis)tones.edge.alpha else tones.edge.alpha*.42f),
            tint=accent?.copy(alpha=.045f)?:Color.Transparent,hoverColor=tones.hover,paint=paint,focusColor=gold
        )
}

@Composable internal fun Modifier.stepWindowBackground():Modifier {
    val base=MaterialTheme.colorScheme.background
    val top=StepGold.copy(alpha=if(LocalStepIsDark.current).022f else .012f).compositeOver(base)
    val bottom=Color.Black.copy(alpha=if(LocalStepIsDark.current).025f else .008f).compositeOver(base)
    return drawWithCache {
        val fill=Brush.linearGradient(listOf(top,base,bottom),Offset.Zero,Offset(size.width*.5f,size.height))
        onDrawWithContent {drawRect(fill);drawContent()}
    }
}

@Composable internal fun Modifier.stepSidebarBackground():Modifier {
    val base=LocalStepPalette.current.sidebar
    val dark=LocalStepIsDark.current
    val top=Color.White.copy(alpha=if(dark).018f else .22f).compositeOver(base)
    val bottom=Color.Black.copy(alpha=if(dark).045f else .012f).compositeOver(base)
    val edge=MaterialTheme.colorScheme.onSurface.copy(alpha=if(dark).055f else .065f)
    return drawWithCache {
        val fill=Brush.verticalGradient(listOf(top,base,bottom))
        onDrawWithContent {
            drawRect(fill);drawContent()
            drawLine(edge,Offset(size.width-.5f,0f),Offset(size.width-.5f,size.height),strokeWidth=1f)
        }
    }
}

@Composable internal fun Modifier.stepSelectionSurface(selected:Boolean):Modifier {
    val tones=StepMaterials
    val gold=StepGold
    return stepMaterialPaint(
        radius=8.dp,top=if(selected)tones.selectionTop else Color.Transparent,
        bottom=if(selected)tones.selectionBase else Color.Transparent,
        border=if(selected)gold.copy(alpha=.11f)else Color.Transparent,
        highlight=if(selected)tones.edge.copy(alpha=tones.edge.alpha*.65f)else Color.Transparent
    ).drawWithCache {
        val markWidth=18.dp.toPx();val markHeight=2.dp.toPx()
        onDrawWithContent {
            drawContent()
            if(selected)drawRoundRect(gold.copy(alpha=.85f),Offset((size.width-markWidth)/2f,size.height-4.dp.toPx()),
                androidx.compose.ui.geometry.Size(markWidth,markHeight),CornerRadius(markHeight))
        }
    }
}

internal enum class StepActionKind { PRIMARY, SECONDARY, TERTIARY }
@Composable internal fun Modifier.stepActionSurface(kind:StepActionKind,source:MutableInteractionSource,enabled:Boolean):Modifier {
    val tones=StepMaterials;val paint=stepPaintState(source,enabled)
    val top=when { !enabled&&kind!=StepActionKind.TERTIARY->tones.raisedBase.copy(alpha=.45f);kind==StepActionKind.PRIMARY->tones.primaryTop;kind==StepActionKind.SECONDARY->tones.raisedTop;else->Color.Transparent }
    val bottom=when { !enabled&&kind!=StepActionKind.TERTIARY->tones.raisedBase.copy(alpha=.45f);kind==StepActionKind.PRIMARY->tones.primaryBase;kind==StepActionKind.SECONDARY->tones.raisedBase;else->Color.Transparent }
    val base=if(kind==StepActionKind.PRIMARY&&enabled)shadow(1.dp,RoundedCornerShape(8.dp),clip=false,ambientColor=tones.shadow,spotColor=tones.shadow)else this
    return base.stepMaterialPaint(
        radius=8.dp,top=top,bottom=bottom,
        border=if(kind==StepActionKind.SECONDARY)StepLine else Color.Transparent,
        highlight=if(enabled&&kind!=StepActionKind.TERTIARY)tones.edge else Color.Transparent,
        hoverColor=tones.hover,paint=paint,focusColor=StepGold
    )
}

@Composable internal fun Modifier.stepInputSurface(source:MutableInteractionSource,enabled:Boolean):Modifier {
    val tones=StepMaterials;val paint=stepPaintState(source,enabled)
    val border=MaterialTheme.colorScheme.outline.copy(alpha=if(enabled).33f else .20f)
    val insetShade=Color.Black.copy(alpha=if(LocalStepIsDark.current).19f else .045f)
    return stepMaterialPaint(8.dp,tones.fieldTop,tones.fieldBase,border=border,
        highlight=Color.Transparent,hoverColor=tones.hover.copy(alpha=tones.hover.alpha*.5f),paint=paint,focusColor=StepGold)
        .drawWithCache {
            val topInset=Brush.verticalGradient(listOf(insetShade,Color.Transparent),endY=7.dp.toPx())
            onDrawWithContent {
                // The inner shade stops inside the existing field outline; it adds no layout or hit area.
                drawRoundRect(topInset,cornerRadius=CornerRadius(8.dp.toPx()))
                drawContent()
            }
        }
}

/** Draws within existing bounds. Rim widths are stable across every interaction state. */
private fun Modifier.stepMaterialPaint(
    radius:Dp,top:Color,bottom:Color,border:Color=Color.Transparent,highlight:Color=Color.Transparent,
    tint:Color=Color.Transparent,hoverColor:Color=Color.Transparent,paint:StepPaintState=StepPaintState(),focusColor:Color=Color.Transparent
):Modifier = drawWithCache {
    val corner=radius.toPx()
    val fill=Brush.linearGradient(listOf(top,bottom),Offset.Zero,Offset(size.width*.55f,size.height))
    val rim=Brush.verticalGradient(listOf(highlight,Color.Transparent,Color.Black.copy(alpha=highlight.alpha*.20f)))
    val tintFill=Brush.linearGradient(listOf(tint.copy(alpha=(tint.alpha*1.8f).coerceAtMost(1f)),tint.copy(alpha=tint.alpha*.55f),Color.Transparent),Offset.Zero,Offset(size.width,size.height))
    val stroke=1.dp.toPx()
    onDrawWithContent {
        drawRoundRect(fill,cornerRadius=CornerRadius(corner))
        if(tint.alpha>0f)drawRoundRect(tintFill,cornerRadius=CornerRadius(corner))
        if(paint.hover>0f)drawRoundRect(hoverColor.copy(alpha=hoverColor.alpha*paint.hover),cornerRadius=CornerRadius(corner))
        if(paint.pressed>0f)drawRoundRect(Color.Black.copy(alpha=.105f*paint.pressed),cornerRadius=CornerRadius(corner))
        drawContent()
        inset(stroke/2f) {
            val edgeCorner=CornerRadius((corner-stroke/2f).coerceAtLeast(0f))
            drawRoundRect(border,cornerRadius=edgeCorner,style=Stroke(stroke))
            drawRoundRect(rim,cornerRadius=edgeCorner,style=Stroke(stroke*.6f))
            if(paint.focused>0f)drawRoundRect(focusColor.copy(alpha=.68f*paint.focused),cornerRadius=edgeCorner,style=Stroke(stroke))
        }
    }
}
