package app.droidnative.devices

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * One phone camera as /dev/video<index> in the guest. PRoot's dn_v4l2
 * extension emulates the V4L2 device; this side captures with Camera2 and
 * hands frames over through shared memory plus a token FIFO. Protocol:
 * native-host/patches/proot (dn_v4l2_proto.h), docs/device-passthrough.md.
 */
class CameraBridge(
    private val context: Context,
    val index: Int,
    private val cameraId: String,
    val label: String,
    val sizes: List<Size>,
    dir: File,
) {
    private val fifo = FifoWriter(File(dir, "video$index"))
    private val socketFile = File(dir, "video$index.sock")
    private val shmFile = File(dir, "video$index.shm")
    private val slotSize = sizes.maxOf { it.width * it.height * 2 }
    private val shmSize = SLOT_OFFSET + N_SLOTS * slotSize

    @Volatile private var running = false
    private var server: LocalServerSocket? = null
    private var shmRaf: RandomAccessFile? = null
    private var shm: MappedByteBuffer? = null

    private val thread = HandlerThread("dn-camera$index")
    private lateinit var handler: Handler
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var format = 0
    private var produced = 0
    private var frame = ByteArray(0)
    private var planes = Array(3) { ByteArray(0) }

    /** Creates the FIFO; must exist before PRoot starts (it scans at startup). */
    fun prepare() = fifo.create()

    fun start() {
        if (running) return
        running = true
        prepare()
        thread.start()
        handler = Handler(thread.looper)
        val raf = RandomAccessFile(shmFile, "rw")
        raf.setLength(shmSize.toLong())
        shmRaf = raf
        shm = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, shmSize.toLong())
            .apply { order(ByteOrder.LITTLE_ENDIAN) }
        socketFile.delete()
        val ls = LocalSocket(LocalSocket.SOCKET_STREAM)
        ls.bind(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
        server = LocalServerSocket(ls.fileDescriptor)
        Thread({ acceptLoop() }, "dn-camera$index-accept").start()
    }

    fun stop() {
        if (!running) return
        running = false
        // accept() is not interrupted by close(): wake it with a connection
        runCatching {
            LocalSocket().connect(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
        }
        runCatching { server?.close() }
        handler.post { stopCapture() }
        thread.quitSafely()
        socketFile.delete()
        fifo.close()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try { server?.accept() ?: break } catch (e: IOException) { break }
            if (!running) { client.close(); break }
            // Every PRoot process connects once and keeps the connection
            Thread({ serve(client) }, "dn-camera$index-client").start()
        }
    }

    private fun serve(client: LocalSocket) {
        try {
            client.setFileDescriptorsForSend(arrayOf(shmRaf!!.fd))
            client.outputStream.write(hello())
            client.setFileDescriptorsForSend(null)
            val input = DataInputStream(client.inputStream)
            val cmd = ByteArray(16)
            while (running) {
                input.readFully(cmd)
                val b = ByteBuffer.wrap(cmd).order(ByteOrder.LITTLE_ENDIAN)
                when (b.getInt(0)) {
                    CMD_START -> {
                        val w = b.getInt(4); val h = b.getInt(8); val fmt = b.getInt(12)
                        handler.post { startCapture(w, h, fmt) }
                    }
                    CMD_STOP -> handler.post { stopCapture() }
                }
            }
        } catch (_: IOException) {
            // PRoot process ended
        } finally {
            runCatching { client.close() }
        }
    }

    private fun hello(): ByteArray {
        val b = ByteBuffer.allocate(HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(HELLO_MAGIC).putInt(1).putInt(shmSize).putInt(N_SLOTS)
            .putInt(slotSize).putInt(SLOT_OFFSET).putInt(sizes.size)
        for (i in 0 until MAX_SIZES) {
            val s = sizes.getOrNull(i)
            b.putInt(s?.width ?: 0).putInt(s?.height ?: 0)
        }
        val name = label.toByteArray(Charsets.UTF_8).copyOf(31)
        b.put(name).put(0)
        return b.array()
    }

    /* Capture, on the camera thread */

    @SuppressLint("MissingPermission")   // checked through Passthrough before start()
    private fun startCapture(width: Int, height: Int, fourcc: Int) {
        stopCapture()
        format = fourcc
        produced = 0
        frame = ByteArray(if (fourcc == FOURCC_YUYV) width * height * 2 else width * height * 3 / 2)
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        val manager = context.getSystemService(CameraManager::class.java)
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (reader !== r) { camera.close(); return }
                    device = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (device !== camera) { s.close(); return }
                            session = s
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            req.addTarget(r.surface)
                            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            s.setRepeatingRequest(req.build(), null, handler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.w(TAG, "camera $cameraId: session failed")
                        }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "camera $cameraId: error $error")
                    camera.close(); device = null
                }
            }, handler)
            Log.i(TAG, "camera $cameraId: ${width}x$height ${fourccName(fourcc)}")
        } catch (e: Exception) {
            Log.w(TAG, "camera $cameraId cannot open: ${e.message}")
        }
    }

    private fun stopCapture() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
    }

    private fun onImage(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val mem = shm ?: return
            // Guest at most two frames behind: the next slot may still be read
            val consumed = mem.getInt(0)
            if (produced - consumed >= 2) return
            if (!fifo.hasReader()) return
            val bytes = convert(image)
            val slot = produced % N_SLOTS
            val dup = mem.duplicate()
            dup.position(SLOT_OFFSET + slot * slotSize)
            dup.put(frame, 0, bytes)
            val token = ByteBuffer.allocate(TOKEN_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(TOKEN_MAGIC).putInt(slot).putInt(bytes).putInt(produced)
                .putLong(System.nanoTime() / 1000).array()
            if (fifo.write(token)) produced++
        } finally {
            image.close()
        }
    }

    /** YUV_420_888 into [frame] as YUYV or NV12; returns the byte count. */
    private fun convert(image: Image): Int {
        val w = image.width
        val h = image.height
        val p = image.planes
        for (i in 0..2) {
            val buf = p[i].buffer
            if (planes[i].size < buf.remaining()) planes[i] = ByteArray(buf.remaining())
            buf.get(planes[i], 0, buf.remaining())
        }
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        val yStride = p[0].rowStride
        val cStride = p[1].rowStride
        val cPix = p[1].pixelStride
        val out = frame
        if (format == FOURCC_YUYV) {
            var o = 0
            for (row in 0 until h) {
                val yRow = row * yStride
                val cRow = (row shr 1) * cStride
                var x = 0
                while (x < w) {
                    val c = cRow + (x shr 1) * cPix
                    out[o] = y[yRow + x]
                    out[o + 1] = u[c]
                    out[o + 2] = y[yRow + x + 1]
                    out[o + 3] = v[c]
                    o += 4
                    x += 2
                }
            }
            return o
        }
        // NV12: Y plane, then interleaved UV at half resolution
        var o = 0
        for (row in 0 until h) {
            System.arraycopy(y, row * yStride, out, o, w)
            o += w
        }
        for (row in 0 until h / 2) {
            val cRow = row * cStride
            for (x in 0 until w / 2) {
                out[o] = u[cRow + x * cPix]
                out[o + 1] = v[cRow + x * cPix]
                o += 2
            }
        }
        return o
    }

    companion object {
        private const val TAG = "dn-camera"
        private const val HELLO_MAGIC = 0x31564e44
        private const val TOKEN_MAGIC = 0x54564e44
        private const val HELLO_SIZE = 7 * 4 + 16 * 8 + 32
        private const val TOKEN_SIZE = 24
        private const val MAX_SIZES = 16
        private const val N_SLOTS = 4
        private const val SLOT_OFFSET = 4096
        private const val CMD_START = 1
        private const val CMD_STOP = 2
        const val FOURCC_YUYV = 0x56595559
        const val FOURCC_NV12 = 0x3231564e

        private fun fourccName(f: Int) = String(ByteArray(4) { ((f shr (8 * it)) and 0xff).toByte() })

        /** One bridge per usable camera, back cameras first. */
        fun discover(context: Context, dir: File): List<CameraBridge> {
            val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
            val ids = try { manager.cameraIdList.toList() } catch (_: Exception) { return emptyList() }
            val out = ArrayList<CameraBridge>()
            for (id in ids) {
                if (out.size >= 8) break
                val ch = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizes = (map.getOutputSizes(ImageFormat.YUV_420_888) ?: continue)
                    .filter { it.width <= 1920 && it.height <= 1080 && it.width % 2 == 0 && it.height % 2 == 0 }
                    .distinct()
                    .sortedByDescending { it.width * it.height }
                    .take(MAX_SIZES)
                if (sizes.isEmpty()) continue
                val label = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Back camera"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front camera"
                    else -> "External camera"
                } + " ($id)"
                out += CameraBridge(context, out.size, id, label, sizes, dir)
            }
            return out
        }
    }
}
