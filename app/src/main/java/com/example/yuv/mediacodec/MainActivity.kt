package com.example.yuv.mediacodec

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var encoder: MediaCodec
    private var isVideoEncoding = false
    private var yuvData: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btn_encode_h264).setOnClickListener {
            encode(0)
        }

        displayYUV(800, 600)
        initMediaCodec(800, 600)
    }

    override fun onDestroy() {
        super.onDestroy()
        encoder.stop()
        encoder.release()
        isVideoEncoding = false
    }

    private fun initMediaCodec(width: Int, height: Int) {
        Log.d("MainActivity", "init media codec")
        val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    private fun encode(presentationTimeUs: Long) {
        /* 给编码器设置一帧输入数据 */
        // 获取一个可用的输入buffer
        val inputBufferIndex = encoder.dequeueInputBuffer(-1)
        if (inputBufferIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
            // 将数据放到buffer中
            inputBuffer?.put(yuvData!!)
            // 将buffer压入解码队列
            encoder.queueInputBuffer(inputBufferIndex, 0, yuvData!!.size, presentationTimeUs, 0)
        }

        /* 从编码器中取出一帧编码后的输出数据 */
        // 获取一个可用的输出buffer
        while (true) {
            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputBufferIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                // dump到文件
                // 用完后释放输出buffer
                encoder.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    private fun dump(data: ByteArray) {
        val outputFile = File(getExternalFilesDir("dump"), "bitstream.h264")
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.createNewFile()

        val outputFileStream = FileOutputStream(outputFile)
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
}
