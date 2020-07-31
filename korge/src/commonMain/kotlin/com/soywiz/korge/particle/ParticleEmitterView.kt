package com.soywiz.korge.particle

import com.soywiz.kds.iterators.*
import com.soywiz.klock.*
import com.soywiz.korag.*
import com.soywiz.korge.render.*
import com.soywiz.korge.time.*
import com.soywiz.korge.view.*
import com.soywiz.korio.util.*
import com.soywiz.korma.geom.*

inline fun Container.particleEmitter(
	emitter: ParticleEmitter, emitterPos: IPoint = IPoint(),
    time: TimeSpan = TimeSpan.NIL,
	callback: ParticleEmitterView.() -> Unit = {}
) = ParticleEmitterView(emitter, emitterPos).apply { this.timeUntilStop = time }.addTo(this, callback)

suspend fun Container.attachParticleAndWait(
    particle: ParticleEmitter,
    x: Double,
    y: Double,
    time: TimeSpan = TimeSpan.NIL,
    speed: Double = 1.0
) {
    val p = particle.create(x, y, time)
    p.speed = speed
    this += p
    p.waitComplete()
    this -= p
}

class ParticleEmitterView(val emitter: ParticleEmitter, emitterPos: IPoint = IPoint()) : View() {
	val simulator = ParticleEmitterSimulator(emitter, emitterPos)

	var timeUntilStop by simulator::timeUntilStop.redirected()
	val emitterPos by simulator::emitterPos.redirected()
	var emitting by simulator::emitting.redirected()
	val aliveCount by simulator::aliveCount.redirected()
	val anyAlive by simulator::anyAlive.redirected()

	init {
		addUpdater { dt ->
			simulator.simulate(dt)
		}
	}

    fun restart() {
        simulator.restart()
    }

	suspend fun waitComplete() {
		while (anyAlive) waitFrame()
	}

    private var cachedBlending = AG.Blending.NORMAL

	// @TODO: Make ultra-fast rendering flushing ctx and using a custom shader + vertices + indices
	override fun renderInternal(ctx: RenderContext) {
		if (!visible) return
		//ctx.flush()

        if (cachedBlending.srcRGB != emitter.blendFuncSource || cachedBlending.dstRGB != emitter.blendFuncDestination) {
            cachedBlending = AG.Blending(emitter.blendFuncSource, emitter.blendFuncDestination)
        }

		val context = ctx.ctx2d
		val texture = emitter.texture ?: return
		val cx = texture.width * 0.5
		val cy = texture.height * 0.5
		context.keep {
			context.blendFactors = cachedBlending
			context.setMatrix(globalMatrix)

			simulator.particles.fastForEach { p ->
                if (p.alive) {
                    val scale = p.scale
                    context.multiplyColor = p.color
                    context.imageScale(ctx.getTex(texture), p.x - cx * scale, p.y - cy * scale, scale)
                }
			}
		}
	}
}
