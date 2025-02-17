package samples.pong

import com.soywiz.korge.input.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.ui.*
import com.soywiz.korge.view.*
import com.soywiz.korim.color.*

class MenuScene() : Scene() {
	override suspend fun SContainer.sceneInit() {
		// set a background color
		//views.clearColor = Colors.BLACK

		// Add a text to show the name of the game
		var gameNameText = textOld("Super Pong Bros II") {
			position(sceneWidth / 2 - 128, sceneHeight / 2 - 128)
		}

		var playButton = uiButton(256.0, 32.0) {
			text = "Play"
			position(sceneWidth / 2 - 128, sceneHeight / 2 - 64)
			onClick {
				sceneContainer.changeTo<PlayScene>()
			}
		}
		var exitButton = uiButton(256.0, 32.0) {
			text = "Exit"
			position(sceneWidth / 2 - 128, sceneHeight / 2)
			onClick {
				views.gameWindow.close()
			}
		}
	}

}
