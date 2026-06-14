package com.ollamabox

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

object VulkanDetector {
    enum class Availability {
        AVAILABLE,
        BACKEND_NOT_PACKAGED,
        DEVICE_UNSUPPORTED,
    }

    /**
     * Check if the device supports Vulkan at the hardware level.
     * Virtually all Android 8+ devices with a GPU support Vulkan,
     * but some older or emulated devices may not.
     */
    fun availability(context: Context): Availability {
        if (!isBackendPackaged(context)) {
            return Availability.BACKEND_NOT_PACKAGED
        }

        // Primary check: Android PackageManager Vulkan feature flags
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)) {
            return Availability.AVAILABLE
        }

        // Fallback: try loading the system Vulkan loader
        return try {
            System.loadLibrary("vulkan")
            Availability.AVAILABLE
        } catch (_: UnsatisfiedLinkError) {
            Availability.DEVICE_UNSUPPORTED
        } catch (_: Exception) {
            Availability.DEVICE_UNSUPPORTED
        }
    }

    /**
     * Native libraries may be loaded directly from an APK and therefore do not
     * necessarily exist as files under nativeLibraryDir.
     */
    private fun isBackendPackaged(context: Context): Boolean {
        val appInfo = context.applicationInfo
        if (File(appInfo.nativeLibraryDir, "libggml-vulkan.so").isFile) {
            return true
        }

        val apkPaths = listOfNotNull(appInfo.sourceDir) +
            (appInfo.splitSourceDirs?.toList() ?: emptyList())
        return apkPaths.any { apkPath ->
            try {
                ZipFile(apkPath).use { zip ->
                    Build.SUPPORTED_ABIS.any { abi ->
                        zip.getEntry("lib/$abi/libggml-vulkan.so") != null
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
