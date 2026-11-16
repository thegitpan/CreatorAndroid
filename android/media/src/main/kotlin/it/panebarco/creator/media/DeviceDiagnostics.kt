package it.panebarco.creator.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Local technical capabilities only: no media paths, serial numbers, accounts or uploads. */
object DeviceDiagnostics {
    fun collect(context: Context): String {
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cameraAcceptance", "pending_physical_preview_and_saved_file")
            .put("requestedCapture", JSONObject().put("width", 1920).put("height", 1080).put("fps", 30).put("dynamicRange", "SDR"))
            .put("permissions", JSONObject()
                .put("camera", permitted(context, Manifest.permission.CAMERA))
                .put("microphone", permitted(context, Manifest.permission.RECORD_AUDIO)))
        val cameras = JSONArray()
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            for ((index, id) in manager.cameraIdList.withIndex()) {
                val camera = JSONObject().put("index", index)
                try {
                    val characteristics = manager.getCameraCharacteristics(id)
                    camera
                        .put("lensFacing", characteristics[CameraCharacteristics.LENS_FACING])
                        .put("hardwareLevel", characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL])
                        .put("sensorOrientation", characteristics[CameraCharacteristics.SENSOR_ORIENTATION])
                        .put("maxDigitalZoom", characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM])
                        .put("capabilities", JSONArray(characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList() ?: emptyList<Int>()))
                        .put("physicalCameraCount", characteristics.physicalCameraIds.size)
                        .put("afModes", JSONArray(characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.toList() ?: emptyList<Int>()))
                        .put("maxAfRegions", characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF])
                        .put("maxAeRegions", characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AE])
                        .put("aeLockAvailable", characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE])
                        .put("minimumFocusDistanceDiopters", characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE])
                    val zoom = characteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
                    if (zoom != null) camera.put("zoomRatio", JSONObject().put("min", zoom.lower).put("max", zoom.upper))
                    camera.put("fpsRanges", JSONArray().apply {
                        characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.forEach { range ->
                            put(JSONObject().put("min", range.lower).put("max", range.upper))
                        }
                    })
                    camera.put("recorderSizes", JSONArray().apply {
                        characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                            ?.getOutputSizes(MediaRecorder::class.java)?.forEach { size ->
                                put(JSONObject().put("width", size.width).put("height", size.height))
                            }
                    })
                    if (Build.VERSION.SDK_INT >= 33) {
                        camera.put("dynamicRangeProfileBits", characteristics[CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]?.supportedProfiles?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                    } else {
                        camera.put("dynamicRangeProfileBits", JSONObject.NULL)
                    }
                } catch (error: Exception) {
                    camera.put("unavailable", error.javaClass.simpleName)
                }
                cameras.put(camera)
            }
            report.put("concurrentCameraSetSizes", JSONArray(manager.concurrentCameraIds.map(Set<String>::size)))
        } catch (error: Exception) {
            report.put("cameraInventoryUnavailable", error.javaClass.simpleName)
        }
        report.put("cameras", cameras)
        val codecs = JSONArray()
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { codec ->
                val types = codec.supportedTypes.filter { it.startsWith("video/") || it.startsWith("audio/") }
                if (types.isNotEmpty()) codecs.put(JSONObject()
                    .put("name", codec.name)
                    .put("encoder", codec.isEncoder)
                    .put("hardwareAccelerated", codec.isHardwareAccelerated)
                    .put("softwareOnly", codec.isSoftwareOnly)
                    .put("types", JSONArray(types)))
            }
        } catch (error: Exception) {
            report.put("codecInventoryUnavailable", error.javaClass.simpleName)
        }
        report.put("codecs", codecs)
        try {
            val manager = context.getSystemService(AudioManager::class.java)
            report.put("audioInputs", JSONArray().apply {
                manager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { device ->
                    // Product labels and device IDs can identify paired/user hardware; keep only type/capability.
                    put(JSONObject()
                        .put("type", device.type)
                        .put("builtIn", device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                        .put("sampleRates", JSONArray(device.sampleRates.toList()))
                        .put("channelCounts", JSONArray(device.channelCounts.toList()))
                        .put("encodings", JSONArray(device.encodings.toList())))
                }
            })
        } catch (error: Exception) {
            report.put("audioInventoryUnavailable", error.javaClass.simpleName)
        }
        try {
            val stats = StatFs(context.filesDir.absolutePath)
            report.put("storage", JSONObject().put("availableBytes", stats.availableBytes).put("totalBytes", stats.totalBytes))
        } catch (error: Exception) {
            report.put("storageUnavailable", error.javaClass.simpleName)
        }
        return report.toString(2)
    }

    private fun permitted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
