package com.iamgasgass.gassplayer
import android.app.Application
import com.iamgasgass.gassplayer.data.AppStore
import com.iamgasgass.gassplayer.network.HttpClient
class GassPlayerApplication:Application(){val http by lazy{HttpClient()};val store by lazy{AppStore(this,http)}}
