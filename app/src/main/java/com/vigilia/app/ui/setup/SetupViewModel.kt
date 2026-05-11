package com.vigilia.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.vigilia.app.service.ServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI State for the Setup screen.
 */
data class SetupUiState(
    val isCameraPermissionGranted: Boolean = false,
    val isLocationPermissionGranted: Boolean = false,
    val isCalibrationEnabled: Boolean = false,
    val isVideoEnabled: Boolean = false,
    val canStartMonitoring: Boolean = false,
)

/**
 * ViewModel for the Setup screen.
 * Responsible for managing permissions and monitoring configuration.
 */
class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        val locationGranted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        _uiState.update {
            it.copy(
                isCameraPermissionGranted = cameraGranted,
                isLocationPermissionGranted = locationGranted,
                canStartMonitoring = cameraGranted,
            )
        }
    }

    /**
     * Updates permission states based on the result from the OS.
     *
     * @param granted Map of permission names to their granted status.
     */
    fun onPermissionsResult(granted: Map<String, Boolean>) {
        val cameraGranted = granted[Manifest.permission.CAMERA] ?: _uiState.value.isCameraPermissionGranted
        val locationGranted = granted[Manifest.permission.ACCESS_COARSE_LOCATION] ?: _uiState.value.isLocationPermissionGranted

        _uiState.update {
            it.copy(
                isCameraPermissionGranted = cameraGranted,
                isLocationPermissionGranted = locationGranted,
                canStartMonitoring = cameraGranted,
            )
        }
    }

    /**
     * Toggles the calibration mode.
     */
    fun onCalibrationToggled(enabled: Boolean) {
        _uiState.update { it.copy(isCalibrationEnabled = enabled) }
    }

    /**
     * Toggles video recording during sessions.
     */
    fun onVideoToggled(enabled: Boolean) {
        _uiState.update { it.copy(isVideoEnabled = enabled) }
    }

    /**
     * Starts the fatigue monitoring service.
     */
    fun startMonitoring() {
        if (_uiState.value.canStartMonitoring) {
            ServiceController.startMonitoring(getApplication())
        }
    }

    /**
     * Stops the fatigue monitoring service.
     */
    fun stopMonitoring() {
        ServiceController.stopMonitoring(getApplication())
    }
}
