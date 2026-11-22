package com.pare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Observes only while the screen is in use; WorkManager owns background network constraints. */
class ForegroundConnectivity(context:Context,private val returned:()->Unit) {
    private val manager=context.getSystemService(ConnectivityManager::class.java)
    private var callback:ConnectivityManager.NetworkCallback?=null
    fun start() {
        if(callback!=null) return
        val next=object:ConnectivityManager.NetworkCallback() {
            private var available:Network?=null
            override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) {
                if(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && available!=network) {
                    available=network;returned()
                }
            }
            override fun onLost(network:Network) {if(available==network) available=null}
        }
        runCatching {manager.registerDefaultNetworkCallback(next)}.onSuccess {callback=next}
    }
    fun stop() {callback?.let {runCatching {manager.unregisterNetworkCallback(it)}};callback=null}
}
