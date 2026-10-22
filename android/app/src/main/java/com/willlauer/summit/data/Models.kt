package com.willlauer.summit.data

import kotlinx.serialization.Serializable

// Wire format of the Summit server (src/summit/server.py). Only what the phone uses; unknown keys are ignored.

@Serializable
data class Track(
    val id: String,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val dur: Int = 0,
    val ext: String? = null,
    val bpm: Int? = null,
    val key: String? = null,
    val audition: Double = 0.0,
    val liked: Boolean = false,
    val added: Long = 0,
) {
    val name get() = title ?: "Untitled"
    val by get() = artist ?: "Unknown Artist"
}

@Serializable
data class Space(val x: List<Float>, val y: List<Float>, val c: List<Int>, val f: List<Int>, val xyz: List<List<Float>>? = null)

@Serializable
data class Group(val id: Int, val label: String? = null, val family: Int? = null)

@Serializable
data class SpaceInfo(val key: String, val label: String)

@Serializable
data class Playlist(val id: Int, val name: String, val kind: String = "playlist", val ids: List<String>, val version: Int = 0, val updated: Double? = null)

@Serializable
data class LibraryPayload(
    val tracks: List<Track>,
    val spaces: Map<String, Space> = emptyMap(),
    val clusters: Map<String, List<Group>> = emptyMap(),
    val families: Map<String, List<Group>> = emptyMap(),
    val spaceList: List<SpaceInfo> = emptyList(),
    val defaultSpace: String = "combined",
    val playlists: List<Playlist> = emptyList(),
    val liked: List<String> = emptyList(),
)

@Serializable
data class SearchHit(val i: Int, val score: Double = 0.0)

@Serializable
data class Created(val id: Int, val version: Int = 1)

@Serializable
data class Conflict(val conflict: Boolean = true, val playlist: Playlist)
