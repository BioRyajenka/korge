package samples.minesweeper

import com.soywiz.kds.*
import com.soywiz.kmem.*
import com.soywiz.korau.sound.*
import com.soywiz.korev.*
import com.soywiz.korge.component.*
import com.soywiz.korge.scene.ScaledScene
import com.soywiz.korge.time.*
import com.soywiz.korge.util.CancellableGroup
import com.soywiz.korge.view.*
import com.soywiz.korim.format.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.lang.Closeable
import kotlinx.coroutines.*
import kotlin.reflect.*

abstract class Process(parent: Container) : Container() {
	init {
		parent.addChild(this)
	}

	override val stage: Stage get() = super.stage!!
    val processRoot: SContainer get() = parent.findFirstAscendant { it.name == "process.root" } as SContainer
    val scene: ScaledScene get() = processRoot.getExtraTyped<ScaledScene>("scene")!!
	val views: Views get() = stage.views
	var fps: Double = 60.0

	val key: KeyV get() = scene.key
	val mouse: MouseV get() = scene.mouseV
	val audio: AudioV get() = scene.audioV

	suspend fun frame() {
		delayFrame()
	}

	fun action(action: KSuspendFunction0<Unit>) {
		throw ChangeActionException(action)
	}

	abstract suspend fun main()

	private val job: Job = scene.launchAsap {
		var action = ::main
		while (true) {
			try {
				action()
				break
			} catch (e: ChangeActionException) {
				action = e.action
			}
		}
	}

	init {
		addComponent(object : StageComponent {
			override val view: View = this@Process

			override fun added(views: Views) {
				//println("added: $views")
			}

			override fun removed(views: Views) {
				//println("removed: $views")
				job.cancel()
				onDestroy()
			}
		})
	}

	fun destroy() {
		removeFromParent()
	}

	protected open fun onDestroy() {
	}

	class ChangeActionException(val action: KSuspendFunction0<Unit>) : Exception()

	inline fun <reified T : View> collision(): T? = views.stage.findCollision(this)
}

class KeyV(val views: ScaledScene) {
	operator fun get(key: Key): Boolean = views.keysPressed[key] == true
}

class MouseV(val scene: ScaledScene) {
	val left: Boolean get() = pressing[0]
	val right: Boolean get() = pressing[1] || pressing[2]
	val x: Int get() = (scene.sceneView.localMouseX(scene.views)).toInt()
	val y: Int get() = (scene.sceneView.localMouseY(scene.views)).toInt()
	val pressing = BooleanArray(8)
	val pressed = BooleanArray(8)
	val released = BooleanArray(8)
	val _pressed = BooleanArray(8)
	val _released = BooleanArray(8)
}

class AudioV(val views: ScaledScene) {
	fun play(sound: Sound, repeat: Int = 0) {
		val times = (1 + repeat).playbackTimes
		sound.play(views.coroutineContext, times)
	}
}

val ScaledScene.keysPressed by Extra.Property { LinkedHashMap<Key, Boolean>() }
val ScaledScene.key by Extra.PropertyThis<ScaledScene, KeyV> { KeyV(this) }

val ScaledScene.mouseV by Extra.PropertyThis<ScaledScene, MouseV> { MouseV(this) }
val ScaledScene.audioV by Extra.PropertyThis<ScaledScene, AudioV> { AudioV(this) }

fun ScaledScene.registerProcessSystem(): Closeable {
	views.registerStageComponent()

    val closeable = CancellableGroup()

    closeable += stage.addEventListener<MouseEvent> { e ->
		when (e.type) {
			MouseEvent.Type.MOVE -> Unit
			MouseEvent.Type.DRAG -> Unit
			MouseEvent.Type.UP -> {
				mouseV.pressing[e.button.id] = false
				mouseV._released[e.button.id] = true
			}
			MouseEvent.Type.DOWN -> {
				mouseV.pressing[e.button.id] = true
				mouseV._pressed[e.button.id] = true
			}
			MouseEvent.Type.CLICK -> Unit
			MouseEvent.Type.ENTER -> Unit
			MouseEvent.Type.EXIT -> Unit
			MouseEvent.Type.SCROLL -> Unit
		}
	}
	// @TODO: Use onAfterRender
    closeable += views.onBeforeRender {
		arraycopy(mouseV._released, 0, mouseV.released, 0, 8)
		arraycopy(mouseV._pressed, 0, mouseV.pressed, 0, 8)

		mouseV._released.fill(false)
		mouseV._pressed.fill(false)
	}
    closeable += stage.addEventListener<KeyEvent> { e ->
		keysPressed[e.key] = e.type == KeyEvent.Type.DOWN
	}

    return closeable
}

suspend fun readImage(path: String) = resourcesVfs["minesweeper/$path"].readBitmapSlice()
suspend fun readSound(path: String) = resourcesVfs["minesweeper/$path"].readSound()
