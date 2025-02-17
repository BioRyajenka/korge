package com.soywiz.korag

import com.soywiz.kds.iterators.*
import com.soywiz.klogger.*
import com.soywiz.kmem.*
import com.soywiz.kmem.unit.*
import com.soywiz.korag.gl.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korio.lang.*
import com.soywiz.korma.geom.*

internal interface AGNativeObject {
    fun markToDelete()
}

open class AGObject : Closeable {
    internal var _native: AGNativeObject? = null
    internal var _cachedContextVersion: Int = -1
    internal var _cachedVersion: Int = -2
    internal var _version: Int = -1

    protected fun markAsDirty() {
        _version++
    }

    override fun close() {
        _native?.markToDelete()
        _native = null
    }
}

class AGBuffer : AGObject() {
    internal var mem: Buffer? = null

    fun upload(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): AGBuffer = upload(Int8Buffer(data, offset, length).buffer)
    fun upload(data: FloatArray, offset: Int = 0, length: Int = data.size - offset): AGBuffer = upload(Float32Buffer(data, offset, length).buffer)
    fun upload(data: IntArray, offset: Int = 0, length: Int = data.size - offset): AGBuffer = upload(Int32Buffer(data, offset, length).buffer)
    fun upload(data: ShortArray, offset: Int = 0, length: Int = data.size - offset): AGBuffer = upload(Int16Buffer(data, offset, length).buffer)
    fun upload(data: Buffer, offset: Int, length: Int = data.size - offset): AGBuffer = upload(data.sliceWithSize(offset, length))
    fun upload(data: Buffer): AGBuffer {
        mem = data.clone()
        markAsDirty()
        return this
    }

    override fun toString(): String = "AGBuffer(${mem?.sizeInBytes ?: 0})"
}

data class AGTextureUnit constructor(
    val index: Int,
    var texture: AGTexture? = null,
    var linear: Boolean = true,
    var trilinear: Boolean? = null,
    var wrap: AGWrapMode = AGWrapMode.CLAMP_TO_EDGE,
) {
    fun set(texture: AGTexture?, linear: Boolean, trilinear: Boolean? = null) {
        this.texture = texture
        this.linear = linear
        this.trilinear = trilinear
    }
}

class AGTexture(
    val premultiplied: Boolean = true,
    val targetKind: AGTextureTargetKind = AGTextureTargetKind.TEXTURE_2D
) : AGObject(), Closeable {
    var isFbo: Boolean = false
    var requestMipmaps: Boolean = false

    /** [MultiBitmap] for multiple bitmaps (ie. cube map) */
    var bitmap: Bitmap? = null
    var mipmaps: Boolean = false; internal set
    var forcedTexId: ForcedTexId? = null
    val implForcedTexId: Int get() = forcedTexId?.forcedTexId ?: -1
    val implForcedTexTarget: AGTextureTargetKind get() = forcedTexId?.forcedTexTarget?.let { AGTextureTargetKind.fromGl(it) } ?: targetKind
    var estimatedMemoryUsage: ByteUnits = ByteUnits.fromBytes(0L)
    val width: Int get() = bitmap?.width ?: 0
    val height: Int get() = bitmap?.height ?: 0
    val depth: Int get() = (bitmap as? MultiBitmap?)?.bitmaps?.size ?: 1

    private fun checkBitmaps(bmp: Bitmap) {
        if (!bmp.premultiplied) {
            Console.error("Trying to upload a non-premultiplied bitmap: $bmp. This will cause rendering artifacts")
        }
    }

    fun upload(list: List<Bitmap>, width: Int, height: Int): AGTexture {
        list.fastForEach { checkBitmaps(it) }
        return upload(MultiBitmap(width, height, list))
    }

    fun upload(bmp: Bitmap?, mipmaps: Boolean = false): AGTexture {
        bmp?.let { checkBitmaps(it) }
        this.forcedTexId = (bmp as? ForcedTexId?)
        this.bitmap = bmp
        estimatedMemoryUsage = ByteUnits.fromBytes(width * height * depth * 4)
        markAsDirty()
        this.requestMipmaps = mipmaps
        return this
    }

    fun upload(bmp: BitmapSlice<Bitmap>?, mipmaps: Boolean = false): AGTexture {
        // @TODO: Optimize to avoid copying?
        return upload(bmp?.extract(), mipmaps)
    }

    fun doMipmaps(bitmap: Bitmap?, requestMipmaps: Boolean): Boolean {
        val width = bitmap?.width ?: 0
        val height = bitmap?.height ?: 0
        return requestMipmaps && width.isPowerOfTwo && height.isPowerOfTwo
    }

    override fun toString(): String = "AGTexture(pre=$premultiplied)"
}

open class AGFrameBufferBase(val isMain: Boolean) : AGObject() {
    val isTexture: Boolean get() = !isMain
    val tex: AGTexture = AGTexture(premultiplied = true).also { it.isFbo = true }
    var estimatedMemoryUsage: ByteUnits = ByteUnits.fromBytes(0)

    override fun close() {
        tex.close()
        //ag.frameRenderBuffers -= this
    }

    override fun toString(): String = "AGFrameBufferBase(isMain=$isMain)"
}

open class AGFrameBuffer(val base: AGFrameBufferBase) : Closeable {
    constructor(isMain: Boolean = false) : this(AGFrameBufferBase(isMain))
    val isTexture: Boolean get() = base.isTexture
    val isMain: Boolean get() = base.isMain
    val tex: AGTexture get() = base.tex
    val info: AGFrameBufferInfo get() = AGFrameBufferInfo(0).withSize(width, height).withSamples(nsamples).withHasDepth(hasDepth).withHasStencil(hasStencil)
    companion object {
        const val DEFAULT_INITIAL_WIDTH = 128
        const val DEFAULT_INITIAL_HEIGHT = 128
    }

    var nsamples: Int = 1; protected set
    val hasStencilAndDepth: Boolean get() = hasDepth && hasStencil
    var hasStencil: Boolean = true; protected set
    var hasDepth: Boolean = true; protected set

    var x = 0
    var y = 0
    var width = DEFAULT_INITIAL_WIDTH
    var height = DEFAULT_INITIAL_HEIGHT
    var fullWidth = DEFAULT_INITIAL_WIDTH
    var fullHeight = DEFAULT_INITIAL_HEIGHT
    private val _scissor = RectangleInt()
    var scissor: RectangleInt? = null

    open fun setSize(width: Int, height: Int) {
        setSize(0, 0, width, height)
    }

    open fun setSize(x: Int, y: Int, width: Int, height: Int, fullWidth: Int = width, fullHeight: Int = height) {
        if (this.x == x && this.y == y && this.width == width && this.height == height && this.fullWidth == fullWidth && this.fullHeight == fullHeight) return
        tex.upload(NullBitmap(width, height))

        base.estimatedMemoryUsage = ByteUnits.fromBytes(fullWidth * fullHeight * (4 + 4))

        this.x = x
        this.y = y
        this.width = width
        this.height = height
        this.fullWidth = fullWidth
        this.fullHeight = fullHeight
        markAsDirty()
    }

    fun scissor(scissor: RectangleInt?) {
        this.scissor = scissor?.let { _scissor.setTo(it) }
    }

    override fun close() {
        base.close()
        //ag.frameRenderBuffers -= this
    }


    fun setSamples(samples: Int) {
        if (this.nsamples == samples) return
        nsamples = samples
        markAsDirty()
    }

    fun setExtra(hasDepth: Boolean = true, hasStencil: Boolean = true) {
        if (this.hasDepth == hasDepth && this.hasStencil == hasStencil) return
        this.hasDepth = hasDepth
        this.hasStencil = hasStencil
        markAsDirty()
    }

    private fun markAsDirty() {
        //base.markAsDirty()
    }

    override fun toString(): String = "GlFrameBuffer($width, $height)"
}
