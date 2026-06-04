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
    val isCalibrationEnabled: Boolean = true,
    val canStartMonitoring: Boolean = false,
)

/**
 * ViewModel for the Setup screen.
 * Responsible for managing permissions and monitoring configuration.
 */
@Suppress("unused")
class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        val ctx = getApplication<Application>()
        val cameraGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        _uiState.update {
            it.copy(
                isCameraPermissionGranted = cameraGranted,
                isLocationPermissionGranted = locationGranted,
                canStartMonitoring = cameraGranted,
            )
        }
    }

    fun onPermissionsResult(granted: Map<String, Boolean>) {
        val cameraGranted = granted[Manifest.permission.CAMERA] ?: _uiState.value.isCameraPermissionGranted
        val locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                _uiState.value.isLocationPermissionGranted

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
     * Starts the fatigue monitoring service.
     */
    fun startMonitoring() {
        if (_uiState.value.canStartMonitoring) {
            ServiceController.startMonitoring(getApplication(), _uiState.value.isCalibrationEnabled)
        }
    }

    /**
     * Stops the fatigue monitoring service.
     */
    fun stopMonitoring() {
        ServiceController.stopMonitoring(getApplication())
    }
}
