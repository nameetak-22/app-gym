package com.programminghut.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    val paint = Paint()
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap: Bitmap
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    val client = OkHttpClient()
    private val TAG = "MyApp"
    private var frameCounter = 0
    private var sendingFrames = false // Flag to indicate if we should start sending frames


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        get_permission()
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageView = findViewById(R.id.imageView)

        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                frameCounter++

                // Process every frame
                if (frameCounter % 1 == 0) { // Adjust frame rate here
                    bitmap = textureView.bitmap!!

                    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, false)
                    val image = TensorImage(DataType.FLOAT32) // Create a TensorImage with the desired data type
                    image.load(resizedBitmap)
                    val inputFeature0 = image.tensorBuffer
                    val outputImage = imageProcessor.process(image)

                    // Send the frame to the backend for processing
                    sendFrameToBackend(outputImage.bitmap)
                    //var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    //val h = mutable.height
                    //val w = mutable.width
                    //imageView.setImageBitmap(mutable)

                }
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @SuppressLint("MissingPermission")
    fun open_camera() {
        val cameraId = getFrontCameraId()
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0
                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)
                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)
                cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(p0: CameraCaptureSession) {
                        p0.setRepeatingRequest(captureRequest.build(), null, null)
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {
                    }
                }, handler)
            }

            override fun onDisconnected(p0: CameraDevice) {
            }

            override fun onError(p0: CameraDevice, p1: Int) {
            }
        }, handler)
    }

    fun getFrontCameraId(): String {
        val cameraIdList = cameraManager.cameraIdList
        for (cameraId in cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                // Found the front camera
                return cameraId
            }
        }
        throw IllegalStateException("Front camera not found")
    }

    fun get_permission(){
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
            get_permission()
        }
    }


    // Send the frame to the backend for processing
    fun sendFrameToBackend(frame: Bitmap) {
        val url = "https://2120-2401-4900-1cb9-a755-e122-8514-7acb-468.ngrok.io/process_frame" // Replace with your backend URL
        val frameByteArray = BitmapToByteArray(frame)
        val base64FrameData = Base64.encodeToString(frameByteArray, Base64.DEFAULT)

        val jsonRequestBody = JSONObject()
        jsonRequestBody.put("frame", base64FrameData) // Send the base64-encoded frame

        val requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            jsonRequestBody.toString()
        )

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()


        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                val jsonObject = JSONObject(responseBody)
                val angleKnee = jsonObject.getDouble("angle_knee")
                val angleHip = jsonObject.getDouble("angle_hip")
                val prediction = jsonObject.getString("prediction")

                runOnUiThread {
                    // Display the results on the real-time input
                    bitmap = textureView.bitmap!!
                    var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val h = mutable.height
                    val w = mutable.width
                    val canvas = Canvas(mutable)
                    paint.textSize = h / 55f
                    paint.strokeWidth = h / 85f
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.FILL
                    val resultText = "Knee Angle: $angleKnee\nHip Angle: $angleHip\nPrediction: $prediction"
                    canvas.drawText(resultText, 10f, h - 20f, paint)
                    imageView.setImageBitmap(mutable)
                }
            }
        })
    }

    fun BitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }

}
