package com.iamgasgass.gassplayer.data

import kotlinx.serialization.Serializable

@Serializable enum class SourceType { XTREAM, M3U }
@Serializable data class MediaSource(val id:String, val name:String, val type:SourceType, val url:String, val username:String="", val password:String="", val enabled:Boolean=true, val pinned:Boolean=false)
@Serializable data class Category(val id:String, val name:String, val parentId:Int=0)
@Serializable data class Channel(val id:String, val name:String, val streamUrl:String, val logo:String="", val group:String="", val epgId:String="", val number:Int=0, val catchup:Boolean=false, val sourceId:String="")
@Serializable data class Movie(val id:String, val name:String, val streamUrl:String, val poster:String="", val backdrop:String="", val categoryId:String="", val rating:Double=0.0, val year:String="", val plot:String="", val extension:String="mp4", val sourceId:String="")
@Serializable data class Series(val id:String, val name:String, val poster:String="", val backdrop:String="", val categoryId:String="", val rating:Double=0.0, val year:String="", val plot:String="", val sourceId:String="")
@Serializable data class Episode(val id:String, val title:String, val season:Int, val episode:Int, val streamUrl:String, val image:String="", val plot:String="", val duration:String="", val extension:String="mp4")
@Serializable data class EpgProgramme(val channelId:String, val title:String, val description:String="", val startMillis:Long, val endMillis:Long, val icon:String="", val category:String="", val catchupUrl:String="")
@Serializable data class WatchProgress(val mediaId:String, val title:String, val positionMs:Long, val durationMs:Long, val poster:String="", val streamUrl:String="", val updatedAt:Long=System.currentTimeMillis()) { val fraction:Float get() = if(durationMs<=0) 0f else (positionMs.toDouble()/durationMs).coerceIn(0.0,1.0).toFloat() }
@Serializable data class Catalog(val liveCategories:List<Category> = emptyList(), val vodCategories:List<Category> = emptyList(), val seriesCategories:List<Category> = emptyList(), val channels:List<Channel> = emptyList(), val movies:List<Movie> = emptyList(), val series:List<Series> = emptyList(), val updatedAt:Long=System.currentTimeMillis())
@Serializable data class AppBackup(val version:Int=1, val exportedAt:Long=System.currentTimeMillis(), val sources:List<MediaSource>, val favorites:Set<String>, val history:List<WatchProgress>)
data class Metadata(val title:String, val overview:String="", val poster:String="", val backdrop:String="", val year:String="", val rating:Double=0.0, val genres:List<String> = emptyList())
