package com.example.cameraprobe

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutionException

class MainActivity : Activity() {

    private val cameraPermissionRequestCode = 1001
    private lateinit var tvReport: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvReport = findViewById(R.id.tvReport)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)

        btnCopy.setOnClickListener {
            val text = tvReport.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Camera Probe Report", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, tvReport.text.toString())
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share Camera Probe Report"))
        }

        checkPermissionAndRun()
    }

    private fun checkPermissionAndRun() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            generateReport()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                cameraPermissionRequestCode
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                generateReport()
            } else {
                tvReport.text = "Camera permission was denied. Cannot probe camera capabilities."
            }
        }
    }

    private fun generateReport() {
        val report = StringBuilder()
        report.append("CAMERA PROBE REPORT\n")
        report.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        report.append("Android OS: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        report.append("========================================\n\n")

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            report.append("Error: CameraManager not available.\n")
            tvReport.text = report.toString()
            return
        }

        try {
            val cameraIdList = cameraManager.cameraIdList
            report.append("Total Cameras Found: ").append(cameraIdList.size).append("\n\n")

            for (cameraId in cameraIdList) {
                report.append("----------------------------------------\n")
                report.append("Camera ID: ").append(cameraId).append("\n")
                try {
                    val chars = cameraManager.getCameraCharacteristics(cameraId)
                    probeCamera(chars, report, isPhysical = false)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val physicalIds = chars.physicalCameraIds
                            if (physicalIds.isNotEmpty()) {
                                report.append("\n  [Physical Cameras for Logical ID ").append(cameraId).append("]\n")
                                for (physId in physicalIds) {
                                    report.append("  -- Physical Camera ID: ").append(physId).append(" --\n")
                                    try {
                                        val physChars = cameraManager.getCameraCharacteristics(physId)
                                        probeCamera(physChars, report, isPhysical = true)
                                    } catch (e: Exception) {
                                        report.append("    Failed to probe physical camera ").append(physId).append(": ").append(e.message).append("\n")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            report.append("  Physical Camera check error: ").append(e.message).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    report.append("Failed to probe camera ").append(cameraId).append(": ").append(e.message).append("\n")
                }
                report.append("\n")
            }
        } catch (e: Exception) {
            report.append("Camera enumeration error: ").append(e.message).append("\n")
        }

        probeCameraXExtensions(report)

        tvReport.text = report.toString()
    }

    private fun probeCamera(chars: CameraCharacteristics, report: StringBuilder, isPhysical: Boolean) {
        val indent = if (isPhysical) "    " else "  "

        // 1. Facing & Hardware Level
        try {
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            val level = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            report.append(indent).append("Facing: ").append(facing).append(" | Hardware Level: ").append(level).append("\n")
        } catch (e: Exception) {
            report.append(indent).append("Facing/Level error: ").append(e.message).append("\n")
        }

        // 2. Sensor & Lens Details
        try {
            val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

            report.append(indent).append("Pixel Array Size: ")
                .append(pixelArray?.let { "${it.width} x ${it.height}" } ?: "N/A").append("\n")
            report.append(indent).append("Physical Sensor Size: ")
                .append(physicalSize?.let { "${it.width} x ${it.height} mm" } ?: "N/A").append("\n")
            report.append(indent).append("Focal Lengths: ")
                .append(focalLengths?.joinToString(", ") { "${it}mm" } ?: "N/A").append("\n")
            report.append(indent).append("Apertures: ")
                .append(apertures?.joinToString(", ") { "f/$it" } ?: "N/A").append("\n")
        } catch (e: Exception) {
            report.append(indent).append("Sensor/Lens error: ").append(e.message).append("\n")
        }

        // 3. Capabilities
        try {
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val capSet = caps.toSet()

            fun checkCap(constValue: Int): Boolean = capSet.contains(constValue)

            val raw = checkCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val burst = checkCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
            val manual = checkCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val logical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                checkCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            } else false
            val ultraHighRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
            } else false
            val depth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                checkCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
            } else false

            report.append(indent).append("Capabilities:\n")
            report.append(indent).append("  - RAW: ").append(raw).append("\n")
            report.append(indent).append("  - BURST_CAPTURE: ").append(burst).append("\n")
            report.append(indent).append("  - MANUAL_SENSOR: ").append(manual).append("\n")
            report.append(indent).append("  - LOGICAL_MULTI_CAMERA: ").append(logical).append("\n")
            report.append(indent).append("  - ULTRA_HIGH_RESOLUTION_SENSOR: ").append(ultraHighRes).append("\n")
            report.append(indent).append("  - DEPTH_OUTPUT: ").append(depth).append("\n")
        } catch (e: Exception) {
            report.append(indent).append("Capabilities error: ").append(e.message).append("\n")
        }

        // 4. Supported Output Sizes (JPEG, YUV_420_888, RAW_SENSOR) - Largest 5 each
        try {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            report.append(indent).append("Supported Output Sizes (Top 5 largest):\n")

            fun appendTop5(formatName: String, format: Int) {
                val sizes = map?.getOutputSizes(format)
                if (sizes == null || sizes.isEmpty()) {
                    report.append(indent).append("  ").append(formatName).append(": Unsupported\n")
                } else {
                    val sorted = sizes.sortedByDescending { it.width.toLong() * it.height.toLong() }.take(5)
                    val str = sorted.joinToString(", ") { "${it.width}x${it.height}" }
                    report.append(indent).append("  ").append(formatName).append(": ").append(str).append("\n")
                }
            }

            appendTop5("JPEG", ImageFormat.JPEG)
            appendTop5("YUV_420_888", ImageFormat.YUV_420_888)
            appendTop5("RAW_SENSOR", ImageFormat.RAW_SENSOR)
        } catch (e: Exception) {
            report.append(indent).append("Output sizes error: ").append(e.message).append("\n")
        }

        // 5. Maximum-Resolution Stream Configuration Sizes (SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                if (maxResMap != null) {
                    report.append(indent).append("Maximum Resolution Output Sizes (Top 5):\n")
                    fun appendMaxResTop5(formatName: String, format: Int) {
                        val sizes = maxResMap.getOutputSizes(format)
                        if (sizes != null && sizes.isNotEmpty()) {
                            val sorted = sizes.sortedByDescending { it.width.toLong() * it.height.toLong() }.take(5)
                            val str = sorted.joinToString(", ") { "${it.width}x${it.height}" }
                            report.append(indent).append("  ").append(formatName).append(": ").append(str).append("\n")
                        } else {
                            report.append(indent).append("  ").append(formatName).append(": None\n")
                        }
                    }
                    appendMaxResTop5("JPEG", ImageFormat.JPEG)
                    appendMaxResTop5("YUV_420_888", ImageFormat.YUV_420_888)
                    appendMaxResTop5("RAW_SENSOR", ImageFormat.RAW_SENSOR)
                } else {
                    report.append(indent).append("Maximum Resolution Stream Configuration Map: Not Available\n")
                }
            } catch (e: Exception) {
                report.append(indent).append("Max resolution map error: ").append(e.message).append("\n")
            }
        } else {
            report.append(indent).append("Maximum Resolution Stream Map: Requires API 31+\n")
        }

        // 6. OIS, Max Digital Zoom, ISO Range, Exposure Time Range
        try {
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val hasOis = oisModes?.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON } ?: false
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

            report.append(indent).append("OIS Supported: ").append(hasOis).append("\n")
            report.append(indent).append("Max Digital Zoom: ").append(maxZoom?.let { "${it}x" } ?: "N/A").append("\n")
            report.append(indent).append("ISO Range: ")
                .append(isoRange?.let { "[${it.lower}, ${it.upper}]" } ?: "N/A").append("\n")
            report.append(indent).append("Exposure Time Range: ")
                .append(exposureRange?.let { "[${it.lower} ns, ${it.upper} ns]" } ?: "N/A").append("\n")
        } catch (e: Exception) {
            report.append(indent).append("Controls/Sensors error: ").append(e.message).append("\n")
        }
    }

    private fun probeCameraXExtensions(report: StringBuilder) {
        report.append("\n========================================\n")
        report.append("CAMERAX EXTENSIONS AVAILABILITY\n")
        report.append("========================================\n")

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            val cameraProvider = cameraProviderFuture.get()
            val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(this, cameraProvider)
            val extensionsManager = extensionsManagerFuture.get()

            val modes = listOf(
                "HDR" to ExtensionMode.HDR,
                "NIGHT" to ExtensionMode.NIGHT,
                "BOKEH" to ExtensionMode.BOKEH,
                "FACE_RETOUCH" to ExtensionMode.FACE_RETOUCH,
                "AUTO" to ExtensionMode.AUTO
            )

            val selectors = listOf(
                "Back Camera" to CameraSelector.DEFAULT_BACK_CAMERA,
                "Front Camera" to CameraSelector.DEFAULT_FRONT_CAMERA
            )

            for ((cameraName, selector) in selectors) {
                report.append(cameraName).append(":\n")
                var cameraAvailable = true
                try {
                    cameraProvider.hasCamera(selector)
                } catch (e: Exception) {
                    cameraAvailable = false
                }

                if (!cameraAvailable) {
                    report.append("  Camera not available on this device.\n")
                    continue
                }

                for ((modeName, mode) in modes) {
                    try {
                        val supported = extensionsManager.isExtensionAvailable(selector, mode)
                        report.append("  - ").append(modeName).append(": ").append(supported).append("\n")
                    } catch (e: Exception) {
                        report.append("  - ").append(modeName).append(": Error (").append(e.message).append(")\n")
                    }
                }
            }
        } catch (e: ExecutionException) {
            report.append("CameraX Extensions initialization failed: ").append(e.cause?.message ?: e.message).append("\n")
        } catch (e: Exception) {
            report.append("CameraX Extensions probe error: ").append(e.message).append("\n")
        }
    }
}
