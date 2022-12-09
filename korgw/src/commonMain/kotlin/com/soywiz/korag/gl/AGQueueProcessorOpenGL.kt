package com.soywiz.korag.gl

import com.soywiz.kds.*
import com.soywiz.kds.iterators.*
import com.soywiz.kds.lock.*
import com.soywiz.kgl.*
import com.soywiz.kmem.*
import com.soywiz.korag.*
import com.soywiz.korag.shader.*
import com.soywiz.korag.shader.gl.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korio.annotations.*
import com.soywiz.korio.lang.*

@OptIn(KorIncomplete::class, KorInternal::class)
class AGQueueProcessorOpenGL(
    private val gl: KmlGl,
    val glGlobalState: GLGlobalState,
) : AGQueueProcessor {
    val globalState: AGGlobalState = glGlobalState.agGlobalState

    class FastResources<T : Any>(val create: (id: Int) -> T) {
        private val resources = arrayListOf<T?>()
        operator fun get(id: Int): T? = getOrNull(id)
        fun getOrNull(id: Int): T? = resources.getOrNull(id)
        fun getOrCreate(id: Int): T = getOrNull(id) ?: create(id).also {
            while (resources.size <= id) resources.add(null)
            resources[id] = it
        }
        fun tryGetAndDelete(id: Int): T? = getOrNull(id).also { delete(id) }
        fun delete(id: Int) {
            if (id < resources.size) resources[id] = null
        }
    }

    val config: GlslConfig = GlslConfig(
        gles = gl.gles,
        android = gl.android,
    )

    val contextVersion: Int get() = globalState.contextVersion

    override fun listStart() {
        if (globalState.renderThreadId == -1L) {
            globalState.renderThreadId = currentThreadId
            globalState.renderThreadName = currentThreadName
            if (currentThreadName?.contains("DefaultDispatcher-worker") == true) {
                println("DefaultDispatcher-worker!")
                printStackTrace()
            }
        }
        if (globalState.renderThreadId != currentThreadId) {
            println("AGQueueProcessorOpenGL.listStart: CALLED FROM DIFFERENT THREAD! ${globalState.renderThreadName}:${globalState.renderThreadId} != $currentThreadName:$currentThreadId")
            printStackTrace()
        }
    }

    override fun contextLost() {
        globalState.contextVersion++
        gl.handleContextLost()
        gl.graphicExtensions // Ensure extensions are available outside the GL thread
    }

    //var doPrintTimer = Stopwatch().also { it.start() }
    //var doPrint = false
    override fun flush() {
        gl.flush()
    }

    override fun finish() {
        gl.flush()
        //gl.finish()

        deletePendingObjects()

       //doPrint = if (doPrintTimer.elapsed >= 1.seconds) {
       //    println("---------------------------------")
       //    doPrintTimer.restart()
       //    true
       //} else {
       //    false
       //}

    }

    override fun enableDisable(kind: AGEnable, enable: Boolean) {
        gl.enableDisable(kind.toGl(), enable)
    }

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        gl.colorMask(red, green, blue, alpha)
    }

    override fun blendEquation(rgb: AGBlendEquation, a: AGBlendEquation) {
        gl.blendEquationSeparate(rgb.toGl(), a.toGl())
    }

    override fun blendFunction(srcRgb: AGBlendFactor, dstRgb: AGBlendFactor, srcA: AGBlendFactor, dstA: AGBlendFactor) {
        gl.blendFuncSeparate(srcRgb.toGl(), dstRgb.toGl(), srcA.toGl(), dstA.toGl())
    }

    override fun cullFace(face: AGCullFace) {
        gl.cullFace(face.toGl())
    }

    override fun frontFace(face: AGFrontFace) {
        gl.frontFace(face.toGl())
    }

    override fun depthFunction(depthTest: AGCompareMode) {
        gl.depthFunc(depthTest.toGl())
    }

    ///////////////////////////////////////
    // PROGRAMS
    ///////////////////////////////////////

    internal class ProgramInfo(val id: Int) {
        internal var glProgramInfo: GLProgramInfo? = null
    }

    private val programs = FastResources { ProgramInfo(it) }
    private var currentProgram: GLProgramInfo? = null

    override fun programCreate(programId: Int, program: Program, config: ProgramConfig?) {
        programs.getOrCreate(programId).glProgramInfo = GLShaderCompiler.programCreate(
            gl,
            this.config.copy(programConfig = config ?: this.config.programConfig),
            program, debugName = program.name
        )
    }

    override fun programDelete(programId: Int) {
        val program = programs.tryGetAndDelete(programId) ?: return
        program.glProgramInfo?.delete(gl)
        if (currentProgram === program.glProgramInfo) {
            currentProgram = null
        }
        program.glProgramInfo = null
    }

    override fun programUse(programId: Int) {
        programUseExt(programs[programId]?.glProgramInfo)
    }

    private fun programUseExt(program: GLProgramInfo?) {
        program?.use(gl)
        currentProgram = program
    }

    // BUFFERS
    class BufferInfo(val id: Int) {
        var glId = 0
        var cachedVersion = -1
    }

    private fun deletePendingObjects() {
        while (true) {
            glGlobalState.objectsToDeleteLock {
                if (glGlobalState.objectsToDelete.isNotEmpty()) {
                    glGlobalState.objectsToDelete.toList().also {
                        glGlobalState.objectsToDelete.clear()
                    }
                } else {
                    return
                }
            }.fastForEach {
                it.delete()
            }
        }

    }

    fun <T : AGObject> T.update(block: (T) -> Unit) {
        if (this._cachedVersion != this._version) {
            this._cachedVersion = this._version
            block(this)
        }
    }

    private fun bindBuffer(buffer: AGBuffer, target: AGBufferKind) {
        val bufferInfo = buffer.gl
        gl.bindBuffer(target.toGl(), bufferInfo.id)
        buffer.update {
            val mem = buffer.mem ?: Buffer(0)
            gl.bufferData(target.toGl(), mem.sizeInBytes, mem, KmlGl.STATIC_DRAW)
        }
    }

    ///////////////////////////////////////
    // DRAW
    ///////////////////////////////////////
    override fun draw(
        type: AGDrawType,
        vertexCount: Int,
        offset: Int,
        instances: Int,
        indexType: AGIndexType,
        indices: AGBuffer?
    ) {
        indices?.let { bindBuffer(it, AGBufferKind.INDEX) }

        if (indexType != AGIndexType.NONE) {
            if (instances != 1) {
                gl.drawElementsInstanced(type.toGl(), vertexCount, indexType.toGl(), offset, instances)
            } else {
                gl.drawElements(type.toGl(), vertexCount, indexType.toGl(), offset)
            }
        } else {
            if (instances != 1) {
                gl.drawArraysInstanced(type.toGl(), offset, vertexCount, instances)
            } else {
                gl.drawArrays(type.toGl(), offset, vertexCount)
            }
        }
    }

    ///////////////////////////////////////
    // UNIFORMS
    ///////////////////////////////////////
    override fun depthMask(depth: Boolean) {
        gl.depthMask(depth)
    }

    override fun depthRange(near: Float, far: Float) {
        gl.depthRangef(near, far)
    }

    override fun stencilFunction(compareMode: AGCompareMode, referenceValue: Int, readMask: Int) {
        gl.stencilFunc(compareMode.toGl(), referenceValue, readMask)
    }

    // @TODO: Separate
    override fun stencilOperation(
        actionOnDepthFail: AGStencilOp,
        actionOnDepthPassStencilFail: AGStencilOp,
        actionOnBothPass: AGStencilOp
    ) {
        gl.stencilOp(actionOnDepthFail.toGl(), actionOnDepthPassStencilFail.toGl(), actionOnBothPass.toGl())
    }

    // @TODO: Separate
    override fun stencilMask(writeMask: Int) {
        gl.stencilMask(writeMask)
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        gl.scissor(x, y, width, height)
        //println("SCISSOR: $x, $y, $width, $height")
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {
        gl.viewport(x, y, width, height)
        //println("VIEWPORT: $x, $y, $width, $height")
    }

    override fun clear(color: Boolean, depth: Boolean, stencil: Boolean) {
        var mask = 0
        if (color) mask = mask or KmlGl.COLOR_BUFFER_BIT
        if (depth) mask = mask or KmlGl.DEPTH_BUFFER_BIT
        if (stencil) mask = mask or KmlGl.STENCIL_BUFFER_BIT
        gl.clear(mask)
    }

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        gl.clearColor(red, green, blue, alpha)
    }

    override fun clearDepth(depth: Float) {
        gl.clearDepthf(depth)
    }

    override fun clearStencil(stencil: Int) {
        gl.clearStencil(stencil)
    }

    override fun vaoUnuse(vao: AGVertexArrayObject) {
        vao.list.fastForEach { entry ->
            val vattrs = entry.layout.attributes
            vattrs.fastForEach { att ->
                if (att.active) {
                    val loc = att.fixedLocation
                    if (loc >= 0) {
                        if (att.divisor != 0) {
                            gl.vertexAttribDivisor(loc, 0)
                        }
                        gl.disableVertexAttribArray(loc)
                    }
                }
            }
        }
    }

    override fun vaoUse(vao: AGVertexArrayObject) {
        vao.list.fastForEach { entry ->
            val vertices = entry.buffer
            val vertexLayout = entry.layout

            val vattrs = vertexLayout.attributes
            val vattrspos = vertexLayout.attributePositions

            //if (vertices.kind != AG.BufferKind.VERTEX) invalidOp("Not a VertexBuffer")

            bindBuffer(vertices, AGBufferKind.VERTEX)
            val totalSize = vertexLayout.totalSize
            for (n in 0 until vattrspos.size) {
                val att = vattrs[n]
                if (!att.active) continue
                val off = vattrspos[n]
                val loc = att.fixedLocation
                val glElementType = att.type.toGl()
                val elementCount = att.type.elementCount
                if (loc >= 0) {
                    gl.enableVertexAttribArray(loc)
                    gl.vertexAttribPointer(
                        loc,
                        elementCount,
                        glElementType,
                        att.normalized,
                        totalSize,
                        off.toLong()
                    )
                    if (att.divisor != 0) {
                        gl.vertexAttribDivisor(loc, att.divisor)
                    }
                }
            }
        }
    }

    // UBO

    override fun uniformsSet(uniforms: AGUniformValues) {
        val glProgram = currentProgram ?: return

        //if (doPrint) println("-----------")

        var textureUnit = -1
        //for ((uniform, value) in uniforms) {
        uniforms.fastForEach { value ->
            val uniform = value.uniform
            val uniformName = uniform.name
            val uniformType = uniform.type
            val location = glProgram.getUniformLocation(gl, uniformName)
            val declArrayCount = uniform.arrayCount

            when (uniformType) {
                VarType.Sampler2D, VarType.SamplerCube -> {
                    textureUnit++
                    val unit = value.nativeValue?.fastCastTo<AGTextureUnit>() ?: AGTextureUnit(textureUnit, null)
                    //val textureUnit = unit.index
                    //println("unit=${unit.texture}")
                    //textureUnit = glProgram.getTextureUnit(uniform, unit)

                    //if (cacheTextureUnit[textureUnit] != unit) {
                    //    cacheTextureUnit[textureUnit] = unit.clone()
                    selectTextureUnit(textureUnit)
                    value.i32[0] = textureUnit

                    val tex = unit.texture
                    if (tex != null) {
                        // @TODO: This might be enqueuing commands, we shouldn't do that here.
                        textureBind(tex, when (uniformType) {
                            VarType.Sampler2D -> AGTextureTargetKind.TEXTURE_2D
                            else -> AGTextureTargetKind.TEXTURE_CUBE_MAP
                        })
                        textureSetWrap(tex)
                        textureSetFilter(tex, unit.linear, unit.trilinear ?: unit.linear)
                    } else {
                        gl.bindTexture(KmlGl.TEXTURE_2D, 0)
                    }
                    //}
                }
                else -> Unit
            }

            val oldValue = glProgram.cache[uniform]
            if (value == oldValue) {
                return@fastForEach
            }
            glProgram.cache[uniform] = value

            //println("uniform: $uniform, arrayCount=${uniform.arrayCount}, stride=${uniform.elementCount}, value=$value old=$oldValue")

            // Store into a direct buffer
            //arraycopy(value.data, 0, tempData, 0, value.data.size)
            val data = value.data

            //println("uniform=$uniform, data=${value.data}")

            when (uniformType.kind) {
                VarKind.TFLOAT -> when (uniform.type) {
                    VarType.Mat2 -> gl.uniformMatrix2fv(location, declArrayCount, false, data)
                    VarType.Mat3 -> gl.uniformMatrix3fv(location, declArrayCount, false, data)
                    VarType.Mat4 -> gl.uniformMatrix4fv(location, declArrayCount, false, data)
                    else -> when (uniformType.elementCount) {
                        1 -> gl.uniform1fv(location, declArrayCount, data)
                        2 -> gl.uniform2fv(location, declArrayCount, data)
                        3 -> gl.uniform3fv(location, declArrayCount, data)
                        4 -> gl.uniform4fv(location, declArrayCount, data)
                    }
                }
                else -> when (uniformType.elementCount) {
                    1 -> gl.uniform1iv(location, declArrayCount, data)
                    2 -> gl.uniform2iv(location, declArrayCount, data)
                    3 -> gl.uniform3iv(location, declArrayCount, data)
                    4 -> gl.uniform4iv(location, declArrayCount, data)
                }
            }
        }
    }


    fun textureSetFilter(tex: AGTexture, linear: Boolean, trilinear: Boolean = linear) {
        val minFilter = if (tex.mipmaps) {
            when {
                linear -> when {
                    trilinear -> KmlGl.LINEAR_MIPMAP_LINEAR
                    else -> KmlGl.LINEAR_MIPMAP_NEAREST
                }
                else -> when {
                    trilinear -> KmlGl.NEAREST_MIPMAP_LINEAR
                    else -> KmlGl.NEAREST_MIPMAP_NEAREST
                }
            }
        } else {
            if (linear) KmlGl.LINEAR else KmlGl.NEAREST
        }
        val magFilter = if (linear) KmlGl.LINEAR else KmlGl.NEAREST

        gl.texParameteri(tex.implForcedTexTarget.toGl(), KmlGl.TEXTURE_MIN_FILTER, minFilter)
        gl.texParameteri(tex.implForcedTexTarget.toGl(), KmlGl.TEXTURE_MAG_FILTER, magFilter)
    }

    fun textureSetWrap(tex: AGTexture) {
        gl.texParameteri(tex.implForcedTexTarget.toGl(), KmlGl.TEXTURE_WRAP_S, KmlGl.CLAMP_TO_EDGE)
        gl.texParameteri(tex.implForcedTexTarget.toGl(), KmlGl.TEXTURE_WRAP_T, KmlGl.CLAMP_TO_EDGE)
        if (tex.implForcedTexTarget.dims >= 3) gl.texParameteri(tex.implForcedTexTarget.toGl(), KmlGl.TEXTURE_WRAP_R, KmlGl.CLAMP_TO_EDGE)
    }

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, data: Any, kind: AGReadKind) {
        val bytesPerPixel = when (data) {
            is IntArray -> 4
            is FloatArray -> 4
            is ByteArray -> 1
            else -> TODO()
        }
        val area = width * height
        BufferTemp(area * bytesPerPixel) { buffer ->
            when (kind) {
                AGReadKind.COLOR -> gl.readPixels(x, y, width, height, KmlGl.RGBA, KmlGl.UNSIGNED_BYTE, buffer)
                AGReadKind.DEPTH -> gl.readPixels(x, y, width, height, KmlGl.DEPTH_COMPONENT, KmlGl.FLOAT, buffer)
                AGReadKind.STENCIL -> gl.readPixels(x, y, width, height, KmlGl.STENCIL_INDEX, KmlGl.UNSIGNED_BYTE, buffer)
            }
            when (data) {
                is IntArray -> buffer.getArrayInt32(0, data, size = area)
                is FloatArray -> buffer.getArrayFloat32(0, data, size = area)
                is ByteArray -> buffer.getArrayInt8(0, data, size = area)
                else -> TODO()
            }
            //println("readColor.HASH:" + bitmap.computeHash())
        }
    }

    override fun readPixelsToTexture(tex: AGTexture, x: Int, y: Int, width: Int, height: Int, kind: AGReadKind) {
        //println("BEFORE:" + gl.getError())
        //textureBindEnsuring(tex)
        textureBind(tex, AGTextureTargetKind.TEXTURE_2D)
        //println("BIND:" + gl.getError())
        gl.copyTexImage2D(KmlGl.TEXTURE_2D, 0, KmlGl.RGBA, x, y, width, height, 0)

        //val data = Buffer.alloc(800 * 800 * 4)
        //for (n in 0 until 800 * 800) data.setInt(n, Colors.RED.value)
        //gl.texImage2D(KmlGl.TEXTURE_2D, 0, KmlGl.RGBA, 800, 800, 0, KmlGl.RGBA, KmlGl.UNSIGNED_BYTE, data)
        //println("COPY_TEX:" + gl.getError())
    }

    // TEXTURES
    class TextureInfo(val id: Int) {
        var glId: Int = -1
    }

    override fun textureBind(tex: AGTexture?, target: AGTextureTargetKind) {
        val glTex = tex?.gl
        gl.bindTexture(target.toGl(), glTex?.id ?: 0)
        val texBitmap = tex?.bitmap
        if (glTex != null && texBitmap != null) {
            if (glTex.cachedContentVersion != texBitmap.contentVersion) {
                glTex.cachedContentVersion = texBitmap.contentVersion
                tex._cachedVersion = -1
                tex._version++
            }
            tex.update {
                //gl.texImage2D(target.toGl(), 0, type, source.width, source.height, 0, type, KmlGl.UNSIGNED_BYTE, null)
                val rbmp = tex.bitmap
                val bmps = (rbmp as? MultiBitmap?)?.bitmaps ?: listOf(rbmp)
                val requestMipmaps: Boolean = tex.requestMipmaps
                tex.mipmaps = tex.doMipmaps(rbmp, requestMipmaps)

                //println("UPDATE BITMAP")

                for ((index, bmp) in bmps.withIndex()) {
                    val isFloat = bmp is FloatBitmap32

                    val type = when {
                        bmp is Bitmap8 -> KmlGl.LUMINANCE
                        else -> KmlGl.RGBA //if (source is NativeImage) KmlGl.BGRA else KmlGl.RGBA
                    }

                    val texTarget = when (target) {
                        AGTextureTargetKind.TEXTURE_CUBE_MAP -> KmlGl.TEXTURE_CUBE_MAP_POSITIVE_X + index
                        else -> target.toGl()
                    }

                    //val tex = textures.getOrNull(textureId)
                    //println("_textureUpdate: texId=$textureId, id=${tex?.id}, glId=${tex?.glId}, target=$target, source=${source.width}x${source.height}")
                    //println(buffer)
                    val internalFormat = when {
                        isFloat && (gl.webgl2 || !gl.webgl) -> KmlGl.RGBA32F
                        //isFloat && (gl.webgl) -> KmlGl.FLOAT
                        //isFloat && (gl.webgl) -> KmlGl.RGBA
                        else -> type
                    }
                    val format = type
                    val texType = when {
                        isFloat -> KmlGl.FLOAT
                        else -> KmlGl.UNSIGNED_BYTE
                    }


                    if (gl.linux) {
                        //println("prepareTexImage2D")
                        //gl.pixelStorei(GL_UNPACK_LSB_FIRST, KmlGl.TRUE)
                        gl.pixelStorei(KmlGl.UNPACK_LSB_FIRST, KmlGl.GFALSE)
                        gl.pixelStorei(KmlGl.UNPACK_SWAP_BYTES, KmlGl.GTRUE)
                    }

                    when (bmp) {
                        null -> gl.texImage2D(target.toGl(), 0, type, tex.width, tex.height, 0, type, KmlGl.UNSIGNED_BYTE, null)
                        is NativeImage -> if (bmp.area != 0) {
                            gl.texImage2D(texTarget, 0, type, type, KmlGl.UNSIGNED_BYTE, bmp)
                        }
                        is NullBitmap -> {
                            //gl.texImage2DMultisample(texTarget, fb.ag.nsamples, KmlGl.RGBA, fb.ag.width, fb.ag.height, false)
                            gl.texImage2D(texTarget, 0, internalFormat, bmp.width, bmp.height, 0, format, texType, null)
                        }
                        else -> {
                            val buffer = createBufferForBitmap(bmp)
                            if (buffer != null && bmp.width != 0 && bmp.height != 0 && buffer.size != 0) {
                                //println("actualSyncUpload: webgl=$webgl, internalFormat=${internalFormat.hex}, format=${format.hex}, textype=${texType.hex}")
                                gl.texImage2D(texTarget, 0, internalFormat, bmp.width, bmp.height, 0, format, texType, buffer)
                            }
                        }
                    }

                    if (tex.mipmaps) {
                        gl.generateMipmap(texTarget)
                    }
                }
            }
        }
    }

    private val tempTextureUnit = 7

    override fun textureSetFromFrameBuffer(tex: AGTexture, x: Int, y: Int, width: Int, height: Int) {
        val old = selectTextureUnit(tempTextureUnit)
        gl.bindTexture(gl.TEXTURE_2D, tex.gl.id)
        gl.copyTexImage2D(gl.TEXTURE_2D, 0, gl.RGBA, x, y, width, height, 0)
        gl.bindTexture(gl.TEXTURE_2D, 0)
        selectTextureUnit(old)
    }

    private fun createBufferForBitmap(bmp: Bitmap?): Buffer? = when (bmp) {
        null -> null
        is NativeImage -> unsupported("Should not call createBufferForBitmap with a NativeImage")
        is Bitmap8 -> Buffer(bmp.area).also { mem -> arraycopy(bmp.data, 0, mem.i8, 0, bmp.area) }
        is FloatBitmap32 -> Buffer(bmp.area * 4 * 4).also { mem -> arraycopy(bmp.data, 0, mem.f32, 0, bmp.area * 4) }
        else -> Buffer(bmp.area * 4).also { mem ->
            val abmp: Bitmap32 = if (bmp.premultiplied) bmp.toBMP32IfRequired().premultipliedIfRequired() else bmp.toBMP32IfRequired().depremultipliedIfRequired()
            arraycopy(abmp.ints, 0, mem.i32, 0, abmp.area)
        }
    }

    // FRAME BUFFER
    inner class FrameBufferInfo {
        var cachedVersion = -1
        var texColor = -1
        var renderbuffer = -1
        var framebuffer = -1
        var width = -1
        var height = -1
        var hasDepth = false
        var hasStencil = false
        var nsamples: Int = 1

        val hasStencilAndDepth: Boolean get() = when {
            //gl.android -> hasStencil || hasDepth // stencil8 causes strange bug artifacts in Android (at least in one of my devices)
            else -> hasStencil && hasDepth
        }
    }

    private var currentTextureUnit = 0
    private fun selectTextureUnit(index: Int): Int {
        val old = currentTextureUnit
        currentTextureUnit = index
        gl.activeTexture(KmlGl.TEXTURE0 + index)
        return old
    }

    override fun frameBufferSet(frameBuffer: AGFrameBuffer) {
        // Ensure everything has been executed already. @TODO: We should remove this since this is a bottleneck
        val fb = frameBuffer.gl
        val tex = fb.ag.tex
        // http://wangchuan.github.io/coding/2016/05/26/multisampling-fbo.html
        val doMsaa = false
        val internalFormat = when {
            fb.ag.hasStencilAndDepth -> KmlGl.DEPTH_STENCIL
            fb.ag.hasStencil -> KmlGl.STENCIL_INDEX8 // On android this is buggy somehow?
            fb.ag.hasDepth -> KmlGl.DEPTH_COMPONENT
            else -> 0
        }
        val texTarget = when {
            doMsaa -> KmlGl.TEXTURE_2D_MULTISAMPLE
            else -> KmlGl.TEXTURE_2D
        }

        frameBuffer.update {
            tex.bitmap = NullBitmap(frameBuffer.width, frameBuffer.height, false)
            val old = selectTextureUnit(tempTextureUnit)
            textureBind(tex, AGTextureTargetKind.TEXTURE_2D)
            selectTextureUnit(old)
            gl.texParameteri(texTarget, KmlGl.TEXTURE_MAG_FILTER, KmlGl.LINEAR)
            gl.texParameteri(texTarget, KmlGl.TEXTURE_MIN_FILTER, KmlGl.LINEAR)
            //gl.texImage2D(texTarget, 0, KmlGl.RGBA, fb.ag.width, fb.ag.height, 0, KmlGl.RGBA, KmlGl.UNSIGNED_BYTE, null)
            gl.bindTexture(texTarget, 0)
            gl.bindRenderbuffer(KmlGl.RENDERBUFFER, fb.renderBufferId)
            if (internalFormat != 0) {
                //gl.renderbufferStorageMultisample(KmlGl.RENDERBUFFER, fb.nsamples, internalFormat, fb.width, fb.height)
                gl.renderbufferStorage(KmlGl.RENDERBUFFER, internalFormat, fb.width, fb.height)
            }
            gl.bindRenderbuffer(KmlGl.RENDERBUFFER, 0)
            //gl.renderbufferStorageMultisample()
        }

        gl.bindFramebuffer(KmlGl.FRAMEBUFFER, fb.frameBufferId)
        gl.framebufferTexture2D(KmlGl.FRAMEBUFFER, KmlGl.COLOR_ATTACHMENT0, KmlGl.TEXTURE_2D, fb.ag.tex.gl.id, 0)
        if (internalFormat != 0) {
            gl.framebufferRenderbuffer(KmlGl.FRAMEBUFFER, internalFormat, KmlGl.RENDERBUFFER, fb.renderBufferId)
        } else {
            gl.framebufferRenderbuffer(KmlGl.FRAMEBUFFER, KmlGl.STENCIL_ATTACHMENT, KmlGl.RENDERBUFFER, 0)
            gl.framebufferRenderbuffer(KmlGl.DEPTH_ATTACHMENT, KmlGl.STENCIL_ATTACHMENT, KmlGl.RENDERBUFFER, 0)
        }
        //val status = gl.checkFramebufferStatus(KmlGl.FRAMEBUFFER)
        //if (status != KmlGl.FRAMEBUFFER_COMPLETE) { gl.bindFramebuffer(KmlGl.FRAMEBUFFER, 0); error("Error getting framebuffer") }
    }

    private val AGBuffer.gl: GLBuffer get() = gl(glGlobalState)
    private val AGFrameBuffer.gl: GLFrameBuffer get() = gl(glGlobalState)
    private val AGTexture.gl: GLTexture get() = gl(glGlobalState)
}
