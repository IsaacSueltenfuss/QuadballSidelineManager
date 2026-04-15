package com.example.quadballsidelinemanager.models

data class Shot(
    val shooterID: String,
    val assistantID: String ?= null,
    var shotType: ShotType,
    var hoopID: HoopID,
    val isGood: Boolean
) {

}

enum class ShotType { DUNK, FINISH, SHOT, MISC }
enum class HoopID { TALL, MEDIUM, SMALL }