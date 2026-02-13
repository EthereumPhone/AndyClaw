package org.ethereumphone.andyclaw

enum class CameraHudKind {
  Photo,
  Recording,
  Success,
  Error,
}

data class CameraHudState(
  val token: Long,
  val kind: CameraHudKind,
  val message: String,
)
