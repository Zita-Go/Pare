package com.pare.app

import kotlinx.serialization.Serializable

@Serializable data class Session(val owner:String,val email:String,val accessToken:String,val refreshToken:String,val expiresAt:Long)
