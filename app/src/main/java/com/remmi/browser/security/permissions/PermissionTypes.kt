package com.remmi.browser.security.permissions

enum class PermissionType {
  CAMERA,
  MICROPHONE,
  CAMERA_AND_MICROPHONE,
  GEOLOCATION,
  FILE_UPLOAD;

  val displayName: String
    get() = when (this) {
      CAMERA -> "Camera"
      MICROPHONE -> "Microphone"
      CAMERA_AND_MICROPHONE -> "Camera & Microphone"
      GEOLOCATION -> "Location"
      FILE_UPLOAD -> "File Access"
    }

  val description: String
    get() = when (this) {
      CAMERA -> "This site wants to use your camera for video capture."
      MICROPHONE -> "This site wants to use your microphone for audio recording."
      CAMERA_AND_MICROPHONE -> "This site wants to use your camera and microphone for live video and audio."
      GEOLOCATION -> "This site wants to access your current location."
      FILE_UPLOAD -> "This site is requesting file upload."
    }
}

enum class PermissionDecision {
  ALLOW_ONCE,
  ALLOW_SESSION,
  DENY
}

data class PermissionPromptRequest(
  val requestId: String,
  val tabId: String,
  val origin: String,
  val displayHost: String,
  val permissionType: PermissionType,
  val isGhost: Boolean = false,
  val isCoarseOnly: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val onDecision: (PermissionDecision) -> Unit
)

data class ActivePermissionState(
  val tabId: String,
  val origin: String,
  val isCameraActive: Boolean = false,
  val isMicrophoneActive: Boolean = false,
  val isGeolocationActive: Boolean = false
) {
  val hasAnyActive: Boolean
    get() = isCameraActive || isMicrophoneActive || isGeolocationActive
}
