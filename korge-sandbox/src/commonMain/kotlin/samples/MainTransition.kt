package samples

import com.soywiz.korge.scene.MaskTransition
import com.soywiz.korge.scene.Scene
import com.soywiz.korge.scene.TransitionView
import com.soywiz.korge.ui.uiButton
import com.soywiz.korge.view.*
import com.soywiz.korge.view.filter.*
import com.soywiz.korim.color.Colors

class MainTransition : Scene() {
    override suspend fun SContainer.sceneMain() {
        val transition = TransitionView().addTo(this).xy(300, 100)
        transition.startNewTransition(SolidRect(100, 100, Colors.RED))
        transition.startNewTransition(SolidRect(100, 100, Colors.BLUE), MaskTransition(
            TransitionFilter.Transition.CIRCULAR
        ))
        transition.ratio = 0.5
        transition.filters(DropshadowFilter(shadowColor = Colors.PURPLE))

        solidRect(100, 100, Colors.GREEN).filters(DropshadowFilter(shadowColor = Colors.PURPLE))

        uiButton("HELLO").xy(200, 400).scale(4)
    }
}
