package com.soywiz.korge.testing

import com.soywiz.korge.Korge
import com.soywiz.korge.view.anchor
import com.soywiz.korge.view.container
import com.soywiz.korge.view.position
import com.soywiz.korge.view.solidRect
import com.soywiz.korim.color.Colors
import com.soywiz.korma.geom.ISizeInt
import com.soywiz.korma.geom.degrees
import org.junit.Test

class KorgeScreenshotTest {
    val DIFF_BY_PIXELS_SETTINGS = KorgeScreenshotValidationSettings(
        listOf(AbsolutePixelDifferenceValidator(1000))
    )
    @Test
    fun test1() = korgeScreenshotTest(
        Korge.Config(
            windowSize = ISizeInt.invoke(512, 512),
            virtualSize = ISizeInt(512, 512),
            bgcolor = Colors.RED
        ),
    ) {
        val maxDegrees = (+16).degrees

        val rect1 = solidRect(100, 100, Colors.RED) {
            rotation = maxDegrees
            anchor(.5, .5)
            scale = 0.8
            position(200, 200)
        }

        it.recordGolden(this, "initial1")

        val rect2 = solidRect(150, 150, Colors.YELLOW) {
            rotation = maxDegrees
            anchor(.5, .5)
            scale = 0.8
            position(350, 350)
        }

        it.recordGolden(rect2, "initial2")

        val rect3 = solidRect(150, 150, Colors.GREEN) {
            rotation = maxDegrees
            anchor(.5, .5)
            scale = 0.8
            position(100, 350)
        }

        it.recordGolden(this, "initial3")

        val rectContainer = container {
            val a = 100
            solidRect(a, a, Colors.BROWN)
            solidRect(a / 2, a / 2, Colors.YELLOW)
        }

        it.recordGolden(rectContainer, "initial4", DIFF_BY_PIXELS_SETTINGS)

        it.endTest()
    }

}
