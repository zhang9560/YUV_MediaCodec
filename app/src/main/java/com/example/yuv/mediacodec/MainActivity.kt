package com.example.yuv.mediacodec

import android.app.ProgressDialog
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
    private var yuvData: ByteArray? = null
    private lateinit var loadingDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val nanoTime = System.nanoTime()
        findViewById<Button>(R.id.btn_encode_h264).setOnClickListener {
            initMediaCodec(MediaFormat.MIMETYPE_VIDEO_AVC, 800, 600, (System.nanoTime() - nanoTime) / 1000)
        }
        findViewById<Button>(R.id.btn_encode_hevc).setOnClickListener {
            initMediaCodec(MediaFormat.MIMETYPE_VIDEO_HEVC, 800, 600, (System.nanoTime() - nanoTime) / 1000)
        }

        loadingDialog = ProgressDialog(this)
        loadingDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        loadingDialog.setMessage("Loading...")
        loadingDialog.setCancelable(false)

        displayYUV(800, 600)
    }

    private fun initMediaCodec(videoType: String, width: Int, height: Int, presentationTimeUs: Long) {
        loadingDialog.show()
        val videoFileName = when (videoType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "h264.mp4"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc.mp4"
            else -> "test.mp4"
        }
        Thread {
            val mediaMuxer = MediaMuxer(File(externalCacheDir, videoFileName).absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val mediaFormat = MediaFormat.createVideoFormat(videoType, width, height)
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6)
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoder = MediaCodec.createEncoderByType(videoType)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val yuv420sp = ByteArray(width * height * 3 / 2)
            // 必须要转格式，否则录制的内容播放出来为绿屏
            NV21ToNV12(yuvData!!, yuv420sp, width, height)

            /* 给编码器设置一帧输入数据 */
            // 获取一个可用的输入buffer
            val inputBufferIndex = encoder.dequeueInputBuffer(-1)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                // 将数据放到buffer中
                inputBuffer?.clear()
                inputBuffer?.put(yuv420sp)
                // 将buffer压入解码队列
                encoder.queueInputBuffer(inputBufferIndex, 0, yuv420sp.size, presentationTimeUs, 0)
            }

            /* 从编码器中取出一帧编码后的输出数据 */
            // 获取一个可用的输出buffer
            var videoTrackIndex = -1
            while (true) {
                val bufferInfo = BufferInfo()
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, -1)

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIndex = mediaMuxer.addTrack(encoder.outputFormat)
                    if (videoTrackIndex >= 0) {
                        mediaMuxer.start()
                    }
                } else if (outputBufferIndex > 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                    // dump到文件
                    mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer!!, bufferInfo)
                    dump(videoType, outputBuffer, bufferInfo)
                    // 用完后释放输出buffer
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    break
                }
            }

            Thread.sleep(1000)

            mediaMuxer.stop()
            mediaMuxer.release()
            encoder.stop()
            encoder.release()

            runOnUiThread {
                loadingDialog.dismiss()
                Toast.makeText(this@MainActivity, "encode completed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun dump(videoType: String, byteBuf: ByteBuffer, bufInfo: BufferInfo) {
        val dumpFileName = when (videoType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "bitstream.h264"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "bitstream.hevc"
            else -> "bitstream.bin"
        }
        byteBuf.position(0)
        val outputFile = File(externalCacheDir, dumpFileName)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.createNewFile()

        val outputFileStream = FileOutputStream(outputFile)
        val data = ByteArray(bufInfo.size)
        byteBuf.get(data)
        outputFileStream.write(data)
        outputFileStream.close()
    }

    private fun displayYUV(width: Int, height: Int) {
        val fileInputStream = assets.open("NV21_800_600.yuv")
        yuvData = fileInputStream.readBytes()

        val outputStream = ByteArrayOutputStream()
        val yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, outputStream)
        val jpegData = outputStream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        findViewById<ImageView>(R.id.iv_yuv).setImageBitmap(bitmap)
        outputStream.close()
        fileInputStream.close()
    }

    private fun NV21ToNV12(nv21: ByteArray, nv12: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        System.arraycopy(nv21, 0, nv12, 0, frameSize)

        var i = 0
        while (i < frameSize) {
            nv12[i] = nv21[i]
            i++
        }

        var j = 0
        while (j < frameSize / 2) {
            nv12[frameSize + j - 1] = nv21[j + frameSize]
            j += 2
        }

        j = 0
        while (j < frameSize / 2) {
            nv12[frameSize + j] = nv21[j + frameSize - 1]
            j += 2
        }
    }
}
